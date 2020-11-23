/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp.internal

import io.ktor.utils.io.core.*
import kotlinx.cinterop.*
import platform.windows.*

internal abstract class WinHttpContextBase(
    private val hConnect: COpaquePointer,
    protected val hRequest: COpaquePointer
) : Closeable {

    fun enableHttp2Protocol() = memScoped {
        val flags = alloc<UIntVar> {
            value = WINHTTP_PROTOCOL_FLAG_HTTP2.convert()
        }
        if (WinHttpSetOption(hRequest, WINHTTP_OPTION_ENABLE_HTTP_PROTOCOL, flags.ptr, UINT_SIZE) == 0) {
            throw createWinHttpError("Unable to set HTTP2 protocol")
        }
    }

    fun disableTlsVerification() = memScoped {
        val flags = alloc<UIntVar> {
            value = (SECURITY_FLAG_IGNORE_UNKNOWN_CA or
                SECURITY_FLAG_IGNORE_CERT_WRONG_USAGE or
                SECURITY_FLAG_IGNORE_CERT_CN_INVALID or
                SECURITY_FLAG_IGNORE_CERT_DATE_INVALID).convert()
        }
        if (WinHttpSetOption(hRequest, WINHTTP_OPTION_SECURITY_FLAGS, flags.ptr, UINT_SIZE) == 0) {
            throw createWinHttpError("Unable to disable TLS verification")
        }
    }

    fun upgradeToWebSocket() {
        if (WinHttpSetOption(hRequest, WINHTTP_OPTION_UPGRADE_TO_WEB_SOCKET, null, 0) == 0) {
            throw createWinHttpError("Unable to request WebSocket upgrade")
        }
    }

    fun setHeaders(headersList: List<String>) {
        val headers = headersList.joinToString("\r\n")
        val modifiers = WINHTTP_ADDREQ_FLAG_ADD.convert<UInt>() or
            WINHTTP_ADDREQ_FLAG_REPLACE

        if (WinHttpAddRequestHeaders(
                hRequest,
                headers,
                headers.length.convert(),
                modifiers
            ) == 0
        ) {
            throw createWinHttpError("Unable to set request headers")
        }
    }

    private fun getLength(dwSize: UIntVar) = (dwSize.value / sizeOf<ShortVar>().convert()).convert<Int>()

    protected fun getResponseData(produceBody: () -> Any) = memScoped {
        val dwStatusCode = alloc<UIntVar>()
        val dwSize = alloc<UIntVar> {
            value = UINT_SIZE
        }

        // Get status code
        val statusCodeFlags = WINHTTP_QUERY_STATUS_CODE or WINHTTP_QUERY_FLAG_NUMBER
        if (WinHttpQueryHeaders(hRequest, statusCodeFlags.convert(), null, dwStatusCode.ptr, dwSize.ptr, null) == 0) {
            throw createWinHttpError("Unable to query status code")
        }

        val httpVersion = if (isHttp2Response()) {
            "HTTP/2.0"
        } else {
            getHeader(WINHTTP_QUERY_VERSION)
        }

        WinHttpResponseData(
            statusCode = dwStatusCode.value.convert(),
            httpProtocol = httpVersion,
            headers = getHeader(WINHTTP_QUERY_RAW_HEADERS_CRLF),
            body = produceBody()
        )
    }

    private fun getHeader(headerId: Int): String = memScoped {
        val dwSize = alloc<UIntVar>()

        // Get headers length
        if (WinHttpQueryHeaders(hRequest, headerId.convert(), null, null, dwSize.ptr, null) == 0) {
            val errorCode = GetLastError()
            if (errorCode != ERROR_INSUFFICIENT_BUFFER.convert<UInt>()) {
                throw createWinHttpError("Unable to query response headers length")
            }
        }

        // Read headers into buffer
        val buffer = allocArray<ShortVar>(getLength(dwSize) + 1)
        if (WinHttpQueryHeaders(hRequest, headerId.convert(), null, buffer, dwSize.ptr, null) == 0) {
            throw createWinHttpError("Unable to query response headers")
        }

        buffer.toKStringFromUtf16()
    }

    private fun isHttp2Response() = memScoped {
        val flags = alloc<UIntVar>()
        val dwSize = alloc<UIntVar> {
            value = UINT_SIZE
        }
        if (WinHttpQueryOption(hRequest, WINHTTP_OPTION_HTTP_PROTOCOL_USED, flags.ptr, dwSize.ptr) != 0) {
            if ((flags.value.convert<Int>() and WINHTTP_PROTOCOL_FLAG_HTTP2) != 0) {
                return true
            }
        }
        false
    }

    override fun close() {
        WinHttpCloseHandle(hRequest)
        WinHttpCloseHandle(hConnect)
    }

    companion object {
        private const val WINHTTP_OPTION_ENABLE_HTTP_PROTOCOL = 133u
        private const val WINHTTP_OPTION_HTTP_PROTOCOL_USED = 134u
        private const val WINHTTP_PROTOCOL_FLAG_HTTP2 = 0x1

        private val UINT_SIZE: UInt = sizeOf<UIntVar>().convert()
    }
}
