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

/**
 * Bundles general Version Control System information.
 */
data class VcsInfo(
        /**
         * The name of the VCS provider, for example Git, Hg or SVN.
         */
        val provider: String,

        /**
         * The URL to the VCS repository.
         */
        val url: String,

        /**
         * The VCS-specific revision (tag, branch, SHA1) that the version of the package maps to.
         */
        val revision: String,

        /**
         * The path inside the VCS to take into account, if any. The actual meaning depends on the VCS provider. For
         * example, for Git only this subdirectory of the repository should be cloned, or for Git Repo it is
         * interpreted as the path to the manifest file.
         */
        val path: String
) {
    companion object {
        /**
         * A constant for a [VcsInfo] where all properties are empty strings.
         */
        @JvmField
        val EMPTY = VcsInfo(
                provider = "",
                url = "",
                revision = "",
                path = ""
        )
    }

    /**
     * Merge two sources of VCS information by mixing and matching fields to get as complete information as possible.
     * If in question, information in this instance has precedence over information in the other instance.
     */
    fun merge(other: VcsInfo): VcsInfo {
        var provider = this.provider
        if (provider.equals(other.provider, true)) {
            // Prefer the other provider only if its spelling matches the VCS class names.
            if (other.provider.toLowerCase().capitalize() == other.provider) {
                provider = other.provider
            }
        }

        var url = this.url
        if (url.isBlank() && other.url.isNotBlank()) {
            url = other.url
        }

        var revision = this.revision
        if (revision.isBlank() && other.revision.isNotBlank()) {
            revision = other.revision
        }

        var path = this.path
        if (path.isBlank() && other.path.isNotBlank()) {
            path = other.path
        }

        return VcsInfo(provider, url, revision, path)
    }
}
