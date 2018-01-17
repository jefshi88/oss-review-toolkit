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

package com.here.ort.downloader

import ch.frankel.slf4k.*

import com.beust.jcommander.IStringConverter
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.ParameterException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory

import com.fasterxml.jackson.module.kotlin.*

import com.here.ort.model.OutputFormat
import com.here.ort.model.Package
import com.here.ort.model.AnalyzerResult
import com.here.ort.model.VcsInfo
import com.here.ort.utils.jsonMapper
import com.here.ort.utils.log
import com.here.ort.utils.safeMkdirs
import com.here.ort.utils.yamlMapper

import java.io.File

import kotlin.system.exitProcess

/**
 * The main entry point of the application.
 */
object ServerMain {

    @Parameter(description = "The file of scan requests.",
            names = ["--requests-file", "-r"],
            // required = true,
            order = 0)
    private var requestsFile: File? = null

    @Parameter(description = "The output directory to download the source code to.",
            names = ["--output-dir", "-o"],
            required = true,
            order = 0)
    @Suppress("LateinitUsage")
    private lateinit var outputDir: File

    @Parameter(description = "Enable info logging.",
            names = ["--info"],
            order = 0)
    private var info = false

    @Parameter(description = "Enable debug logging and keep any temporary files.",
            names = ["--debug"],
            order = 0)
    private var debug = false

    @Parameter(description = "Print out the stacktrace for all exceptions.",
            names = ["--stacktrace"],
            order = 0)
    var stacktrace = false

    @Parameter(description = "Display the command line help.",
            names = ["--help", "-h"],
            help = true,
            order = 100)
    private var help = false

    /**
     * The entry point for the application.
     *
     * @param args The list of application arguments.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val jc = JCommander(this)
        jc.parse(*args)
        jc.programName = "downloader"

        if (info) {
            log.level = ch.qos.logback.classic.Level.INFO
        }

        if (debug) {
            log.level = ch.qos.logback.classic.Level.DEBUG
        }

        if (help) {
            jc.usage()
            exitProcess(1)
        }

        require(System.getenv("ORT_REQUESTS") != null || (requestsFile != null && requestsFile.isFile)) {
            "Either the requestsfile (-r) or ORT_REQUESTS env var must be set"
        }

        require(dependenciesFile.isFile) {
            "Provided path is not a file: ${dependenciesFile.absolutePath}"
        }

        val envRequests : String? = System.getenv("ORT_REQUESTS")
        var requests: List<ScanRequestSpec> = listOf()
        if (envRequests != null) {
            requests = jacksonObjectMapper().readValue(envRequests)
        } else {
            val mapper = when (requestsFile.extension) {
                OutputFormat.JSON.fileEnding -> jacksonObjectMapper()
                OutputFormat.YAML.fileEnding -> ObjectMapper(YAMLFactory())
                else -> throw IllegalArgumentException("Provided input file is neither JSON nor YAML.")
            }
            requestsFile?.let { requestsFile ->
                require(requestsFile.isFile) {
                    "Provided path is not a file: ${requestsFile.absolutePath}"
                }
                requests = mapper.readValue(requestsFile)
            }
        }
        requests.forEach { request ->
            processEntry(request.toPackage())
        }


        val mapper = when (dependenciesFile.extension) {
            OutputFormat.JSON.fileEnding -> jsonMapper
            OutputFormat.YAML.fileEnding -> yamlMapper
            else -> throw IllegalArgumentException("Provided input file is neither JSON nor YAML.")
        }

        val analyzerResult = mapper.readValue(dependenciesFile, AnalyzerResult::class.java)
        val packages = mutableListOf<Package>()

        if (entities.contains(DataEntity.PROJECT)) {
            packages.add(analyzerResult.project.toPackage())
        }

        if (entities.contains(DataEntity.PACKAGES)) {
            packages.addAll(analyzerResult.packages)
        }

        packages.forEach { pkg ->
            try {
                download(pkg, outputDir)
            } catch (e: DownloadException) {
                if (stacktrace) {
                    e.printStackTrace()
                }

                log.error { "Could not download '${pkg.identifier}': ${e.message}" }
            }
        }
    }

    /**
     * Download the source code of the [target] package to a folder inside [outputDirectory]. The folder name is created
     * from the [name][Package.name] and [version][Package.version] of the [target] package.
     *
     * @param target The description of the package to download.
     * @param outputDirectory The parent directory to download the source code to.
     *
     * @return The directory containing the source code, or null if the source code could not be downloaded.
     */
    @Suppress("ComplexMethod")
    fun download(target: Package, outputDirectory: File): File {
        // TODO: return also SHA1 which was finally cloned
        val p = fun(string: String) = println("${target.identifier}: $string")

        // TODO: add namespace to path
        val targetDir = File(outputDirectory, "${target.normalizedName}/${target.version}").apply { safeMkdirs() }
        p("Downloading source code to '${targetDir.absolutePath}'...")

        if (target.vcsProcessed.url.isNotBlank()) {
            p("Trying to download from URL '${target.vcsProcessed.url}'...")

            if (target.vcsProcessed.url != target.vcs.url) {
                p("URL was normalized, original URL was '${target.vcs.url}'.")
            }

            if (target.vcsProcessed.revision.isBlank()) {
                p("WARNING: No VCS revision provided, downloaded source code does likely not match revision " +
                        target.version)
            } else {
                p("Downloading revision '${target.vcsProcessed.revision}'.")
            }

            var applicableVcs: VersionControlSystem? = null

            p("Trying to detect VCS...")

            if (target.vcsProcessed.provider.isNotBlank()) {
                p("from provider name '${target.vcsProcessed.provider}'...")
                applicableVcs = VersionControlSystem.forProvider(target.vcsProcessed.provider)
            }

            if (applicableVcs == null) {
                p("from URL '${target.vcsProcessed.url}'...")
                applicableVcs = VersionControlSystem.forUrl(target.vcsProcessed.url)
            }

            if (applicableVcs == null) {
                throw DownloadException("Could not find an applicable VCS provider.")
            }

            p("Using VCS provider '$applicableVcs'.")

            try {
                val revision = applicableVcs.download(target.vcsProcessed.url, target.vcsProcessed.revision,
                        target.vcsProcessed.path, target.version, targetDir)
                p("Finished downloading source code revision '$revision' to '${targetDir.absolutePath}'.")

                val result = VcsInfo(target.vcsProcessed.provider, target.vcsProcessed.url, revision, target.vcsProcessed.path)
                writeResultFile(targetDir, result)
                return targetDir
            } catch (e: DownloadException) {
                if (stacktrace) {
                    e.printStackTrace()
                }

                throw DownloadException("Could not download source code.", e)
            }
        } else {
            p("No VCS URL provided.")
            // TODO: This should also be tried if the VCS checkout does not work.
            p("Trying to download source package ...")
            // TODO: Implement downloading of source package.
            throw DownloadException("No source code package URL provided.")
        }
    }

    private fun writeResultFile(targetDir: File, result: VcsInfo) {
        val outputFile = File(targetDir.absolutePath + "-vscinfo.json") 
        println("Writing VCS download results to $outputFile")
        jsonMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, result)
    }
}
