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

package com.here.ort.scanner

import ch.frankel.slf4k.*

import com.beust.jcommander.IStringConverter
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.ParameterException

import com.fasterxml.jackson.module.kotlin.*

import com.here.ort.downloader.DownloadException
import com.here.ort.downloader.Main
import com.here.ort.model.*
import com.here.ort.scanner.scanners.ScanCode
import com.here.ort.utils.collectMessages
import com.here.ort.utils.jsonMapper
import com.here.ort.utils.log
import com.here.ort.utils.yamlMapper
import com.here.ort.utils.safeMkdirs

import java.io.File
import java.util.SortedSet

import kotlin.system.exitProcess

/**
 * The main entry point of the server application.
 */
object ServerMain {
    private class ScannerConverter : IStringConverter<Scanner> {
        override fun convert(scannerName: String): Scanner {
            // TODO: Consider allowing to enable multiple scanners (and potentially running them in parallel).
            return Scanner.ALL.find { it.javaClass.simpleName.toUpperCase() == scannerName.toUpperCase() }
                    ?: throw ParameterException("The scanner must be one of ${Scanner.ALL}.")
        }
    }
    data class Person(val name: String, val age: Int, val messages: List<String>) {
    }

    @Parameter(description = "The file of scan requests.",
            names = ["--requests-file", "-r"],
            // required = true,
            order = 0)
    private var requestsFile: File? = null

    @Parameter(description = "The output directory to store the scan results in.",
            names = ["--output-dir", "-o"],
            required = true,
            order = 0)
    @Suppress("LateinitUsage")
    private lateinit var outputDir: File

    @Parameter(description = "The output directory for downloaded entities. Defaults to <output-dir>/downloads.",
            names = ["--download-dir"],
            order = 0)
    private var downloadDir: File? = null

    @Parameter(description = "A comma-separated list of scanners to use. Defaults to ALL",
            names = ["--scanners", "-s"],
            order = 0)
    private var scannerSpecs: List<String> = listOf()

    @Parameter(description = "The path to the configuration file.",
            names = ["--config", "-c"],
            order = 0)
    @Suppress("LateinitUsage")
    private var configFile: File? = null

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
        jc.programName = "scanner"

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

        require(!outputDir.exists()) {
            "The output directory '${outputDir.absolutePath}' must not exist yet."
        }

        downloadDir?.let {
            require(!it.exists()) {
                "The download directory '${it.absolutePath}' must not exist yet."
            }
        }

        if (configFile != null) {
            ScanResultsCache.configure(yamlMapper.readTree(configFile))
        }

        val mapper = jacksonObjectMapper()
        val envRequests : String? = System.getenv("ORT_REQUESTS")
        var requests: List<ScanRequestSpec> = listOf()
        if (envRequests != null) {
            requests = mapper.readValue(envRequests)
        } else {
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
    }

    private fun processEntry(pkg: Package) {
        try {
            var packageDownloadDirectory: File? = null
            val scanners = if (scannerSpecs.isEmpty()) Scanner.ALL else scannerSpecs.map { ScannerConverter().convert(it) }
            scanners.forEach { scanner ->
                if (!scanner.canScan(pkg)) {
                    return
                }
                val qualifiedScannerName = scanner.getName()
                val extension = scanner.resultFileExtension
                val resultsFile = getScanResultsFilename(pkg, qualifiedScannerName, extension)
                if (ScanResultsCache.read(pkg, qualifiedScannerName, resultsFile, false)) {
                    println("Found package '$pkg.identifier' for $scanner in the cache.")
                    return
                }

                if (packageDownloadDirectory == null) {
                    // we need to scan so ensure the package is downloaded
                    packageDownloadDirectory = try {
                        println("Downloading package '$pkg.identifier' ...")
                        Main.download(pkg, downloadDir ?: File(outputDir, "downloads"))
                    } catch (e: DownloadException) {
                        if (Main.stacktrace) {
                            e.printStackTrace()
                        }
                        throw ScanException("Package '${pkg.identifier}' could not be scanned.", e)
                    }
                }
                println("Scanning package '$pkg.identifier' using $scanner ...")
                if (packageDownloadDirectory != null) {
                    scanner.scanPath(packageDownloadDirectory as File, resultsFile).also {
                        ScanResultsCache.write(pkg, qualifiedScannerName, resultsFile)
                    }
                }
            }
        } catch (e: ScanException) {
            if (Main.stacktrace) {
                e.printStackTrace()
            }

            log.error { "Could not scan '$pkg.identifier': ${e.message}" }
        }
    }

    private fun getScanResultsFilename(pkg: Package, scannerName: String, extension: String): File {
        val scanResultsDirectory = File(outputDir, "scanResults").apply { safeMkdirs() }
        val pkgRevision = pkg.version.let { if (it.isBlank()) pkg.vcs.revision.take(7) else it }
        return File(scanResultsDirectory, "${pkg.name}-${pkgRevision}_$scannerName.$extension")
    }
}
