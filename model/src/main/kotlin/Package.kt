/*
 * Copyright (c) 2017-2018 HERE Europe B.V.
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

package com.here.ort.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

import com.here.ort.utils.fileSystemEncode
import com.here.ort.utils.normalizeVcsUrl

import java.util.SortedSet

/**
 * A generic descriptor for a software package. It contains all relevant meta-data about a package like the name,
 * version, and how to retrieve the package and its source code. It does not contain information about the package's
 * dependencies, however. This is because at this stage we would only be able to get the declared dependencies, whereas
 * we are interested in the resolved dependencies. Resolved dependencies might differ from declared dependencies due to
 * specified version ranges, or change depending on how the package is used in a project due to the build system's
 * dependency resolution process. For example, if multiple versions of the same package are used in a project, the build
 * system might decide to align on a single version of that package.
 */
@JsonIgnoreProperties("normalizedName", "identifier", "normalizedVcsUrl")
data class Package(
        /**
         * The name of the package manager that was used to discover this package, for example Maven or NPM.
         */
        @JsonProperty("package_manager")
        val packageManager: String,

        /**
         * The namespace of the package, for example the group id in Maven or the scope in NPM.
         */
        val namespace: String,

        /**
         * The name of the package.
         */
        val name: String,

        /**
         * The version of the package.
         */
        val version: String,

        /**
         * The list of licenses the authors have declared for this package. This does not necessarily correspond to the
         * licenses as detected by a scanner. Both need to be taken into account for any conclusions.
         */
        @JsonProperty("declared_licenses")
        val declaredLicenses: SortedSet<String>,

        /**
         * The description of the package, as provided by the package manager.
         */
        val description: String,

        /**
         * The homepage of the package.
         */
        @JsonProperty("homepage_url")
        val homepageUrl: String,

        /**
         * The remote artifact where the binary package can be downloaded.
         */
        @JsonProperty("binary_artifact")
        val binaryArtifact: RemoteArtifact,

        /**
         * The remote artifact where the source package can be downloaded.
         */
        @JsonProperty("source_artifact")
        val sourceArtifact: RemoteArtifact,

        /**
         * Original VCS-related information as defined in the [Package]'s meta-data.
         */
        val vcs: VcsInfo,

        /**
         * Processed VCS-related information about the [Package] that has e.g. common mistakes corrected.
         */
        @JsonProperty("vcs_processed")
        val vcsProcessed: VcsInfo = VcsInfo(vcs.provider, normalizeVcsUrl(vcs.url), vcs.revision, vcs.path)
) : Comparable<Package> {
    /**
     * The normalized package name, can be used to create directories.
     */
    val normalizedName = name.fileSystemEncode()

    /**
     * The unique identifier for this package, created from [packageManager], [namespace], [name], and [version].
     */
    val identifier = "$packageManager:$namespace:$normalizedName:$version"

    /**
     * Return a template [PackageReference] to refer to this [Package]. It is only a template because e.g. the
     * dependencies still need to be filled out.
     */
    fun toReference(dependencies: SortedSet<PackageReference> = sortedSetOf()) =
            PackageReference(namespace, name, version, dependencies)

    /**
     * A comparison function to sort packages by their identifier.
     */
    override fun compareTo(other: Package) = compareValuesBy(this, other, { it.identifier })
}
