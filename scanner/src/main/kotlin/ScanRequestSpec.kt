/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
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

import com.fasterxml.jackson.annotation.JsonProperty

import com.here.ort.utils.normalizeVcsUrl

import java.util.SortedSet

/**
 * A spec for a request to scan a project/package. This details as much information as we might have about the software
 * and leave ORT to interpret and fill in any gaps.
 */
data class ScanRequestSpec(

        /**
         * The type of the entity to be processed. For example, NPM, Git, Maven, ...
         */
        val type: String,

        /**
         * The fully qualified name of the entity. Depending on the type, this may include a namespace.
         */
        val name: String,

        /**
         * The provider of the entity. This is largely, though not exclusively tied to type. Examples are
         * npmjs, maven-central, github. Note that GitHub may host different types (e.g., Bower, Go, NPM, ...)
         */
        val provider: String,

        /**
         * The revision of the entity. This is expressed in whatever native terms the provider uses.
         */
        val revision: String,

        /**
         * The paths (globs) in the entity to include in the scan (if supported by the scanner).
         */
        val includes: List<String> = listOf(),

        /**
         * The paths (globs) in the entity to exclude from the scan (if supported by the scanner).
         */
        val excludes: List<String> = listOf()
) {

    /**
     * Return a [Package] representation of this request for use in scanning APIs.
     */
    fun toPackage() = Package(
            packageManager = type,
            namespace = "",
            name = name,
            version = revision,
            declaredLicenses = sortedSetOf(),
            description = "",
            homepageUrl = "",
            binaryArtifact = RemoteArtifact.EMPTY,
            sourceArtifact = RemoteArtifact.EMPTY,
            vcs = VcsInfo.EMPTY,
            vcsProcessed = VcsInfo.EMPTY
    )

    companion object {
        /**
         * A constant for a [Project] where all properties are empty strings or empty collections.
         */
        @JvmField
        val EMPTY = ScanRequestSpec(
                type = "",
                provider = "",
                name = "",
                revision = "",
                includes = listOf<String>(),
                excludes = listOf<String>()
        )
    }
}
