/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp

import io.ktor.client.*
import io.ktor.client.request.*
import kotlinx.coroutines.*
import kotlin.native.concurrent.*
import kotlin.test.*

class WinHttpTests {

    @Test
    fun testDownload() {
        val client = HttpClient(WinHttp)

        val responseText = runBlocking {
            client.post<String>("https://postman-echo.com/post") {
                body = "Hello"
            }
        }

        assertTrue { responseText.contains("Hello") }
    }

    @Test
    fun testDownloadInBackground() {
        withWorker {
            execute(TransferMode.SAFE, { }) {
                val client = HttpClient(WinHttp)
                runBlocking {
                    client.get<String>("http://google.com")
                }
            }.consume { assert(it.isNotEmpty()) }
        }
    }
}
