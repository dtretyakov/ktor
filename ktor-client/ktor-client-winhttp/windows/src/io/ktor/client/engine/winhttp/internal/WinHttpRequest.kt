/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp.internal

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.engine.winhttp.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.windows.*
import kotlin.coroutines.*

internal class WinHttpRequest(
    hSession: COpaquePointer,
    private val data: HttpRequestData,
    private val config: WinHttpEngineConfig,
    private val callContext: CoroutineContext
) {
    private val context: WinHttpContext
    private val requestTime = GMTDate()

    init {
        val hConnect = WinHttpConnect(hSession, data.url.host, data.url.port.convert(), 0)
            ?: throw createWinHttpError("Unable to create connection")
        val hRequest = openRequest(hConnect)

        configureFeatures(hRequest)

        context = WinHttpContext(hConnect, hRequest, callContext, data.isUpgradeRequest())
    }

    private fun openRequest(hConnect: COpaquePointer): COpaquePointer {
        var openFlags = WINHTTP_FLAG_ESCAPE_DISABLE or
            WINHTTP_FLAG_ESCAPE_DISABLE_QUERY or
            WINHTTP_FLAG_NULL_CODEPAGE

        if (data.url.protocol.isSecure()) {
            openFlags = openFlags or WINHTTP_FLAG_SECURE
        }

        return WinHttpOpenRequest(
            hConnect,
            data.method.value,
            data.url.fullPath,
            null,         // default HTTP protocol
            null,         // no referring document is specified
            null,         // no types are accepted by the client
            openFlags.convert()
        ) ?: throw createWinHttpError("Unable to open request")
    }

    suspend fun executeRequest(): HttpResponseData {
        if (config.enableHttp2Protocol) {
            context.enableHttp2Protocol()
        }

        if (!config.sslVerify) {
            context.disableTlsVerification()
        }

        if (data.isUpgradeRequest()) {
            context.upgradeToWebSocket()
        }

        val requestHeaders = data.headersToList()
        context.setHeaders(requestHeaders)
        context.sendRequestAsync().await()

        data.body.toByteChannel(callContext)?.let { channel ->
            val buffer = ByteArray(DEFAULT_HTTP_BUFFER_SIZE)
            while (true) {
                val readBytes = channel.readAvailable(buffer)
                if (readBytes < 0) break

                buffer.usePinned {
                    context.writeDataAsync(it, readBytes).await()
                }
            }
        }

        val rawResponse = context.receiveResponseAsync().await()

        return rawResponse.convert(requestTime, callContext)
    }

    private fun configureFeatures(hRequest: COpaquePointer) = memScoped {
        val options = alloc<DWORDVar> {
            value = (WINHTTP_DISABLE_COOKIES or WINHTTP_DISABLE_REDIRECTS).convert()
        }

        if (WinHttpSetOption(
                hRequest,
                WINHTTP_OPTION_DISABLE_FEATURE,
                options.ptr,
                sizeOf<DWORDVar>().convert()
            ) == 0
        ) {
            throw createWinHttpError("Unable to configure request options")
        }
    }

    private fun HttpRequestData.headersToList(): List<String> {
        val result = mutableListOf<String>()

        mergeHeaders(headers, body) { key, value ->
            val header = "$key: $value"
            result.add(header)
        }

        return result
    }

    private fun OutgoingContent.toByteChannel(coroutineContext: CoroutineContext): ByteReadChannel? = when (this) {
        is OutgoingContent.ByteArrayContent -> ByteReadChannel(bytes())
        is OutgoingContent.WriteChannelContent -> GlobalScope.writer(coroutineContext) {
            writeTo(channel)
        }.channel
        is OutgoingContent.ReadChannelContent -> readFrom()
        is OutgoingContent.NoContent -> null
        else -> throw UnsupportedContentTypeException(this)
    }
}
