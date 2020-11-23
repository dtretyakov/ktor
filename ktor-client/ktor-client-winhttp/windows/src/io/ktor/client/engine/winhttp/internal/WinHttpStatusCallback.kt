/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp.internal

import kotlinx.cinterop.*
import platform.windows.*

internal fun statusCallback(
    @Suppress("UNUSED_PARAMETER") hInternet: HINTERNET?,
    dwContext: DWORD_PTR,
    dwStatus: DWORD,
    statusInfo: LPVOID?,
    statusInfoLength: DWORD
) {
    initRuntimeIfNeeded()

    val contextPtr = dwContext.toLong().toCPointer<COpaque>() ?: return
    val context = contextPtr.asStableRef<WinHttpContext>().get()
    if (context.isClosed) return

    when (dwStatus) {
        WINHTTP_CALLBACK_STATUS_WRITE_COMPLETE.convert<UInt>() -> {
            context.onWriteComplete()
        }
        WINHTTP_CALLBACK_STATUS_SENDREQUEST_COMPLETE.convert<UInt>() -> {
            context.onSendRequestComplete()
        }
        WINHTTP_CALLBACK_STATUS_HEADERS_AVAILABLE.convert<UInt>() -> {
            context.onHeadersAvailable()
        }
        WINHTTP_CALLBACK_STATUS_DATA_AVAILABLE.convert<UInt>() -> {
            val size = statusInfo!!.toLong().toCPointer<ULongVar>()!![0]
            context.onDataAvailable(size.convert())
        }
        WINHTTP_CALLBACK_STATUS_READ_COMPLETE.convert<UInt>() -> {
            if (context.isUpgradeRequest) {
                val status = statusInfo!!.reinterpret<WINHTTP_WEB_SOCKET_STATUS>().pointed
                context.onFrameReceived(status)
            } else {
                val size = statusInfoLength.convert<Int>()
                context.onReadComplete(size)
            }
        }
        WINHTTP_CALLBACK_STATUS_CLOSE_COMPLETE.convert<UInt>() -> {
            context.onDisconnect()
        }
        WINHTTP_CALLBACK_STATUS_REQUEST_ERROR.convert<UInt>() -> {
            val result = statusInfo!!.reinterpret<WINHTTP_ASYNC_RESULT>().pointed
            val errorMessage = getWinHttpErrorMessage(result.dwError)
            context.onError(errorMessage)
        }
        WINHTTP_CALLBACK_STATUS_SECURE_FAILURE.convert<UInt>() -> {
            val securityCode = statusInfo!!.reinterpret<UIntVar>().pointed.value
            val errorMessage = getWinHttpErrorMessage(securityCode)
            context.onError(errorMessage)
        }
    }
}
