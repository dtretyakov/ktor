/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp.internal

import io.ktor.client.engine.winhttp.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import kotlin.coroutines.*
import kotlin.native.concurrent.SharedImmutable

@SharedImmutable
internal val EMPTY_BYTE_ARRAY = ByteArray(0)

internal class WinHttpResponseData(
    val statusCode: Int,
    val httpProtocol: String,
    val headers: String,
    val body: Any
)

internal suspend fun WinHttpResponseData.convert(
    requestTime: GMTDate,
    callContext: CoroutineContext
): HttpResponseData {
    val response = parseResponse(ByteReadChannel(headers))
        ?: throw WinHttpIllegalStateException("Failed to parse response header")

    val headers = HeadersImpl(response.headers.toMap())
    response.release()

    return HttpResponseData(
        HttpStatusCode.fromValue(statusCode),
        requestTime,
        headers,
        HttpProtocolVersion.parse(httpProtocol),
        body,
        callContext
    )
}

private fun HttpHeadersMap.toMap(): Map<String, List<String>> {
    val result = mutableMapOf<String, MutableList<String>>()

    for (index in 0 until size) {
        val key = nameAt(index).toString()
        val value = valueAt(index).toString()

        if (result[key]?.add(value) == null) {
            result[key] = mutableListOf(value)
        }
    }

    return result
}
