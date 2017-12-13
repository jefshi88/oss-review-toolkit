/*
 * Copyright (c) 2017 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.analyzer.managers

import ch.frankel.slf4k.error

import com.here.ort.analyzer.Main
import com.here.ort.analyzer.PackageManager
import com.here.ort.analyzer.PackageManagerFactory
import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.AnalyzerResult
import com.here.ort.model.Package
import com.here.ort.model.PackageReference
import com.here.ort.model.Project
import com.here.ort.model.RemoteArtifact
import com.here.ort.model.Scope
import com.here.ort.model.VcsInfo
import com.here.ort.utils.OS
import com.here.ort.utils.ProcessCapture
import com.here.ort.utils.log

import java.io.File

const val COMPOSER_SCRIPT_FILENAME_WINDOWS = "composer.bat"
const val COMPOSER_BINARY = "composer.phar"
const val COMPOSER_SCRIPT_FILENAME = "composer"

const val SCOPE_REQUIRED_SECTION_HEADER = "requires"
const val SCOPE_REQUIRED_DEV_SECTION_HEADER = "requires (dev)"
const val PACKAGE_SCOPE_DEPENDENCIES_REGEX_PATTERN = "(?s)_SCOPE_\\n(?<dependencies>.*?)(?:(?:\\r*\\n){2,4})"

const val PACKAGE_VERSION_REGEX_PATTERN = "versions\\s+:\\s\\*+\\s(?<version>[\\w.]+)"
const val PACKAGE_NAME_REGEX_PATTERN = "name\\s*:\\s+(?<name>[\\w\\/\\-_]+)"
const val PACKAGE_DESC_REGEX_PATTERN = "descrip.\\s+:\\s+(?<desc>[\\w. ():\\/-]+)"
const val PACKAGE_LICENSE_REGEX_PATTERN = "license\\s+:\\s+(?<license>[\\w. ():\\/-]+)[#\\w]+"
const val PACKAGE_SOURCE_REGEX_PATTERN =
        "source\\s+:\\s+\\[(?<vcs>git|svn|fossil|hg)\\]\\s+(?<url>[\\w.:\\/-]+)\\s+(?<revision>\\w*)"
const val PACKAGE_DIST_REGEX_PATTERN =
        "dist\\s+:\\s+\\[(?<type>zip|tar)\\]\\s+(?<url>[\\w.:\\/-]+)\\s+(?<checksum>\\w*)"

class PhpComposer : PackageManager() {
    companion object : PackageManagerFactory<PhpComposer>(
            "https://getcomposer.org/",
            "PHP",
            listOf("composer.json")
    ) {
        override fun create() = PhpComposer()
    }

    override fun command(workingDir: File) =
            if (File(workingDir, COMPOSER_BINARY).isFile) {
                "php $COMPOSER_BINARY"
            } else {
                if (OS.isWindows) {
                    COMPOSER_SCRIPT_FILENAME_WINDOWS
                } else {
                    COMPOSER_SCRIPT_FILENAME
                }
            }

    // Currently single 'composer install' is performed on top level of project, which enables composer to produce
    // results for top level dependencies and their dependencies. If we need deeper dependency analysis, recursive
    // installing and parsing of dependencies should be implemented (probably with controlled recursion depth)
    override fun resolveDependencies(projectDir: File, workingDir: File, definitionFile: File): AnalyzerResult? {
        installDependencies(workingDir)

        val scopes = mutableSetOf<Scope>()
        val packages = mutableSetOf<Package>()
        val errors = mutableListOf<String>()
        val vcsDir = VersionControlSystem.forDirectory(projectDir)
        val projectDetails = showPackage(workingDir).stdout()
        val (packageManager, namespace, projectName, version, declaredLicenses, _, projectHomepageUrl, _, _, _, _) =
                parsePackageDetails(projectDetails, workingDir)

        try {
            parseScope(projectDetails, SCOPE_REQUIRED_SECTION_HEADER, scopes, packages, errors, workingDir)
            parseScope(projectDetails, SCOPE_REQUIRED_DEV_SECTION_HEADER, scopes, packages, errors, workingDir)

        } catch (e: Exception) {
            if (Main.stacktrace) {
                e.printStackTrace()
            }

            log.error { "Could not analyze '${definitionFile.absolutePath}': ${e.message}" }
            return null
        }
        val project = Project(
                packageManager = packageManager,
                namespace = namespace,
                name = projectName,
                version = version,
                declaredLicenses = declaredLicenses,
                aliases = emptyList(),
                vcs = vcsDir?.getInfo(projectDir) ?: VcsInfo.EMPTY,
                homepageUrl = projectHomepageUrl,
                scopes = scopes.toSortedSet()
        )
        return AnalyzerResult(true, project, packages.toSortedSet(), errors)

    }

    private fun parseScope(projectDetails: String, scopeName: String, scopes: MutableSet<Scope>,
                           packages: MutableSet<Package>, errors: MutableList<String>, workingDir: File) {
        log.info("Parsing dependencies for $scopeName")
        try {
            val regexStr = PACKAGE_SCOPE_DEPENDENCIES_REGEX_PATTERN.replace("_SCOPE_", scopeName)

            //FIXME: find does not work with this pattern
            val regex = Regex(regexStr)
            val packagesLines =  regex.find(projectDetails)?.groups?.get("dependencies")?.value?.lines()

            val dependencies = packagesLines?.map {
                val (scopeDependencyPkgName, scopeDependencyVersionConstraint) = it.split(Regex("\\s"))
                val dependencies2ndLevel = mutableSetOf<PackageReference>()
                val parsedPackage = parseDependencyPackage(workingDir, scopeDependencyPkgName, dependencies2ndLevel, scopeName,
                                                           errors)
                if (parsedPackage != null) {
                    packages.add(parsedPackage)
                }
                PackageReference(namespace = parsedPackage?.namespace ?: "",
                                 name = parsedPackage?.name ?: scopeDependencyPkgName,
                                 version = parsedPackage?.version ?: scopeDependencyVersionConstraint,
                                 dependencies = dependencies2ndLevel.toSortedSet())
            }
            scopes.add(Scope(name = scopeName,
                             delivered = true,
                             dependencies = dependencies?.toSortedSet() ?: sortedSetOf()))

        } catch (e: Exception) {
            if (Main.stacktrace) {
                e.printStackTrace()
            }


            val errorMsg = "Failed to parse scope $scopeName: ${e.message}"
            log.error { errorMsg }
            errors.add(errorMsg)
        }
    }

    private fun parseDependencyPackage(workingDir: File, packageName: String, dependencies: MutableSet<PackageReference>,
                                       scopeName: String, errors: MutableList<String>): Package? {
        log.info("Parsing package $packageName")
        if (packageName == "php" || packageName.startsWith("ext-")) {
            // Skip PHP itself along with PHP Extensions
            return null
        }

        return try {
            val pkgDetailLines = showPackage(workingDir, packageName).stdout().trim()
            val dependencyPackagesLines = Regex(PACKAGE_SCOPE_DEPENDENCIES_REGEX_PATTERN.replace("_SCOPE_", scopeName))
                    .matchEntire(pkgDetailLines)?.groups?.get("dependencies")?.value?.lines()
            if (dependencyPackagesLines != null) {
                dependencies.addAll(dependencyPackagesLines.map {
                    val (scopeDependencyPkgName, scopeDependencyVersionConstraint) = it.split(Regex("\\s"))
                    PackageReference(namespace = scopeDependencyPkgName.substringBefore("/"),
                                     name = scopeDependencyPkgName,
                                     version = scopeDependencyVersionConstraint,
                                     dependencies = sortedSetOf())
                })
            }
            parsePackageDetails(pkgDetailLines, workingDir)
        } catch (e: Exception) {
            if (Main.stacktrace) {
                e.printStackTrace()
            }

            val errorMsg = "Failed to parse package $packageName: ${e.message}"
            log.error { errorMsg }
            errors.add(errorMsg)
            null
        }
    }

    private fun parsePackageDetails(pkgShowCommandOutput: String, workingDir: File): Package {
        val pkgDetailsLines = pkgShowCommandOutput.lineSequence()
        val pkgName = getLineValues(Regex(PACKAGE_NAME_REGEX_PATTERN), pkgDetailsLines, "name").firstOrNull() ?: ""
        val namespace = pkgName.substringBefore("/")
        val version = getLineValues(Regex(PACKAGE_VERSION_REGEX_PATTERN), pkgDetailsLines, "version")
                .firstOrNull() ?: ""
        val licenses = getLineValues(Regex(PACKAGE_LICENSE_REGEX_PATTERN), pkgDetailsLines, "license").toSortedSet()
        val desc = getLineValues(Regex(PACKAGE_DESC_REGEX_PATTERN), pkgDetailsLines, "desc").firstOrNull() ?: ""
        val source = parseVcs(Regex(PACKAGE_SOURCE_REGEX_PATTERN), pkgDetailsLines)
        val sourceArtifact = parseSourceArtifact(Regex(PACKAGE_DIST_REGEX_PATTERN), pkgDetailsLines)
        val homepage = showHomepage(workingDir, pkgName).stdout().trim()
        return Package(
                packageManager = javaClass.simpleName,
                namespace = namespace,
                name = pkgName,
                version = version,
                declaredLicenses = licenses,
                description = desc,
                homepageUrl = homepage,
                binaryArtifact = RemoteArtifact.EMPTY,
                sourceArtifact = sourceArtifact,
                vcs = source
        )
    }

    private fun installDependencies(workingDir: File): ProcessCapture =
            ProcessCapture(workingDir, *command(workingDir).split(" ").toTypedArray(), "install").requireSuccess()

    /**
     * Show package details for given [pkgName] ("--self" used by default for project details on top of [workingDir])
     */
    private fun showPackage(workingDir: File, pkgName: String = "--self"): ProcessCapture =
            ProcessCapture(workingDir, *command(workingDir).split(" ").toTypedArray(), "show", pkgName).requireSuccess()

    private fun showHomepage(workingDir: File, pkgName: String): ProcessCapture =
            ProcessCapture(workingDir, *command(workingDir).split(" ").toTypedArray(), "home", "-s", pkgName)
                    .requireSuccess()

    private fun getLineValues(regex: Regex, lines: Sequence<String>, groupName: String): Sequence<String> {
        return lines.mapNotNull {
            regex.matchEntire(it.trim())
                    ?.groups
                    ?.get(groupName)
                    ?.value
        }
    }

    private fun parseVcs(regex: Regex, pkgDetailLines: Sequence<String>): VcsInfo {
        return pkgDetailLines.mapNotNull {
            val matchResult = regex.matchEntire(it.trim())
            val vcs = matchResult?.groups?.get("vcs")?.value
            val url = matchResult?.groups?.get("url")?.value
            val rev = matchResult?.groups?.get("revision")?.value
            if (vcs != null && url != null && rev != null) {
                VcsInfo(vcs, url, rev, "")
            } else {
                null
            }
        }.firstOrNull() ?: VcsInfo.EMPTY
    }

    private fun parseSourceArtifact(regex: Regex, pkgDetailLines: Sequence<String>): RemoteArtifact {
        return pkgDetailLines.mapNotNull {
            val matchResult = regex.matchEntire(it.trim())
            val url = matchResult?.groups?.get("url")?.value
            val hash = matchResult?.groups?.get("checksum")?.value
            if (url != null) {
                RemoteArtifact(url, hash ?: "", if (hash != null && hash.isNotBlank()) "sha1" else "")
            } else {
                null
            }
        }.firstOrNull() ?: RemoteArtifact.EMPTY
    }
}
