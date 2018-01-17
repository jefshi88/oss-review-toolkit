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

package com.here.ort.downloader

import com.here.ort.utils.safeMkdirs

import java.io.File
import java.io.IOException

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

private val TAR_GZ_EXTENSIONS = listOf(".tar.gz", ".tgz")
private val ZIP_EXTENSIONS = listOf(".aar", ".egg", ".jar", ".war", ".whl", ".zip")

fun File.unpack(targetDirectory: File) {
    when {
        TAR_GZ_EXTENSIONS.any { this.name.toLowerCase().endsWith(it) } -> unpackTarGz(targetDirectory)
        ZIP_EXTENSIONS.any { this.name.toLowerCase().endsWith(it) } -> unpackZip(targetDirectory)
        else -> throw IOException("Unknown archive type for file '$absolutePath'.")
    }
}

/**
 * Unpack the file assuming that it is a tar gz file. This implementation ignores empty directories and symbolic links.
 *
 * @param targetDirectory The target directory to store the unpacked content of this archive.
 */
fun File.unpackTarGz(targetDirectory: File) {
    val gzipInputStream = GzipCompressorInputStream(inputStream())
    TarArchiveInputStream(gzipInputStream).use {
        while (true) {
            val entry = it.nextTarEntry ?: break

            if (!entry.isFile) {
                continue
            }

            val target = File(targetDirectory, entry.name)

            target.parentFile.safeMkdirs()

            target.outputStream().use { output ->
                it.copyTo(output)
            }
        }
    }
}

/**
 * Unpack the file assuming that it is a zip file. This implementation ignores empty directories and symbolic links.
 *
 * @param targetDirectory The target directory to store the unpacked content of this archive.
 */
fun File.unpackZip(targetDirectory: File) {
    ZipArchiveInputStream(inputStream()).use {
        while (true) {
            val entry = it.nextZipEntry ?: break

            if (entry.isDirectory || entry.isUnixSymlink) {
                continue
            }

            val target = File(targetDirectory, entry.name)

            target.parentFile.safeMkdirs()

            target.outputStream().use { output ->
                it.copyTo(output)
            }
        }
    }
}
