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

package com.here.ort.scanner

import com.here.ort.downloader.DownloadException
import com.here.ort.downloader.Main
import com.here.ort.model.Package
import com.here.ort.scanner.scanners.*
import com.here.ort.utils.safeMkdirs

import java.io.File
import java.util.SortedSet

class Scanner {

    data class Result(val licenses: SortedSet<String>, val errors: SortedSet<String>)

    /**
     * Return the Java class name as a simply way to refer to the scanner.
     */
    override fun toString(): String = javaClass.simpleName

    /**
     * Scan the provided [pkg] for license information, writing results to [outputDirectory]. If a scan result is found
     * in the cache, it is used without running the actual scan. If no cached scan result is found, the package's source
     * code is downloaded and scanned afterwards.
     *
     * @param pkg The package to scan.
     * @param outputDirectory The base directory to store scan results in.
     * @param downloadDirectory The directory to download source code to. Defaults to [outputDirectory]/downloads if
     *                          null.
     *
     * @return The set of found licenses.
     *
     * @throws ScanException In case the package could not be scanned.
     */
    fun scan(pkg: Package, outputDirectory: File, downloadDirectory: File? = null): Result {
        val scanResultsDirectory = File(outputDirectory, "scanResults").apply { safeMkdirs() }
        val scannerName = toString().toLowerCase()

        val pkgRevision = pkg.version.let { if (it.isBlank()) pkg.vcs.revision.take(7) else it }

        val resultsFile = File(scanResultsDirectory,
                "${pkg.name}-${pkgRevision}_$scannerName.$resultFileExtension")

        if (ScanResultsCache.read(pkg, resultsFile)) {
            return getResult(resultsFile)
        }

        queueScan(pkg, scannerName)
        // TODO wait for result, or not depending on the use case. Not sure the best way to do this in Kotlin
        // in reality you want ot parallelize so that here we return and queue more stuff and then wait
        // for it all to be done
        do {
            // wait for sometime or a callback or ...
        }
        while (!ScanResultsCache.read(pkg, resultsFile)) 
        return getResult(resultsFile)
    }

    fun queueScan(pkg: Package, scannerName: String) {
        log.info { "Queuing request to process: ${request.url}" }

        // TODO structure request as a JSON object that looks like 
        // { "type": "$scannerName", "url": "cd:/pkg.packageManager/pkg.provider/pkg.namespace/pkg.name/pkg.version" }
        val requestString = null 
        val request = Request.Builder()
                .header("Authorization", apiToken)
                .post(OkHttpClientHelper.createRequestBody(requestString))
                .url("$cdUrl/harvest")
                .build()

        return OkHttpClientHelper.execute("scanner", request).use { response ->
            (response.code() == HttpURLConnection.HTTP_CREATED).also {
                log.info {
                    if (it) {
                        "Queued ${request.url} to be processed."
                    } else {
                        "Could not queue ${request.url} to be processed: ${response.code()} - " + response.message()
                    }
                }
            }
        }
    }

    /**
     * Scan the provided [path] for license information, writing results to [outputDirectory]. Note that no caching will
     * be used in this mode.
     *
     * @param path The directory or file to scan.
     * @param outputDirectory The base directory to store scan results in.
     *
     * @return The set of found licenses.
     *
     * @throws ScanException In case the package could not be scanned.
     */
    fun scan(path: File, outputDirectory: File): Result {
        throw new ScanException("Only scanning packages is supported")
    }

    /**
     * A property containing the file name extension of the scanner's native output format, without the dot.
     */
    protected abstract val resultFileExtension: String

    /**
     * Scan the provided [path] for license information, writing results to [resultsFile].
     */
    protected abstract fun scanPath(path: File, resultsFile: File): Result

    /**
     * Convert the scanner's native file format to a [Result].
     */
    internal abstract fun getResult(resultsFile: File): Result
}
