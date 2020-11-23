/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp.internal

import io.ktor.client.engine.winhttp.*
import kotlinx.atomicfu.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.windows.*
import kotlin.coroutines.*
import kotlin.native.concurrent.*

internal class WinHttpContext(
    hConnect: COpaquePointer,
    hRequest: COpaquePointer,
    private val callContext: CoroutineContext,
    val isUpgradeRequest: Boolean
) : WinHttpContextBase(hConnect, hRequest) {
    private val reference = StableRef.create(this)

    private val sendRequestResult = CompletableDeferred<Unit>()
    private val writeDataResult = atomic(CompletableDeferred<Unit>())
    private val receiveResponseResult = CompletableDeferred<WinHttpResponseData>()

    private val webSocket = atomic<WinHttpWebSocket?>(null)
    private val httpResponse = atomic<WinHttpResponseBody?>(null)

    private val closed = atomic(false)

    val isClosed: Boolean
        get() = closed.value

    init {
        freeze()
    }

    fun sendRequestAsync(): Deferred<Unit> {
        // Set status callback
        val callback = staticCFunction(::statusCallback)
        val notifications = WINHTTP_CALLBACK_FLAG_ALL_NOTIFICATIONS.convert<UInt>()
        if (WinHttpSetStatusCallback(hRequest, callback, notifications, 0) != null) {
            throw createWinHttpError("Unable to set request callback")
        }

        // Send request
        val reference = reference.asCPointer().rawValue.toLong()
        if (WinHttpSendRequest(hRequest, null, 0, null, 0, 0, reference.convert()) == 0) {
            throw createWinHttpError("Unable to send request")
        }

        return sendRequestResult
    }

    fun onSendRequestComplete() {
        sendRequestResult.complete(Unit)
    }

    fun writeDataAsync(body: Pinned<ByteArray>, size: Int = body.get().size): Deferred<Unit> {
        // Write request body
        if (WinHttpWriteData(hRequest, body.addressOf(0), size.convert(), null) == 0) {
            throw createWinHttpError("Unable to write request data")
        }

        return writeDataResult.value
    }

    fun onWriteComplete() {
        // Request body write completed
        if (!receiveResponseResult.isCompleted) {
            writeDataResult.getAndSet(CompletableDeferred()).complete(Unit)
            return
        }

        // WebSocket frame was sent
        if (isUpgradeRequest) {
            webSocket.value?.onSendFrame()
        }
    }

    fun receiveResponseAsync(): Deferred<WinHttpResponseData> {
        // Receive HTTP response
        if (WinHttpReceiveResponse(hRequest, null) == 0) {
            throw createWinHttpError("Unable to receive response")
        }

        return receiveResponseResult
    }

    fun onHeadersAvailable() {
        try {
            val responseData = getResponseData {
                if (isUpgradeRequest) {
                    // Create WebSocket response
                    WinHttpWebSocket(hRequest, callContext, reference.asCPointer()).also {
                        webSocket.value = it
                    }
                } else {
                    // Creating HTTP body response
                    WinHttpResponseBody(hRequest, callContext).also {
                        httpResponse.value = it
                    }.body
                }
            }
            receiveResponseResult.complete(responseData)
        } catch (e: Throwable) {
            receiveResponseResult.completeExceptionally(e)
            return
        }

        // Start response body producers
        if (isUpgradeRequest) {
            webSocket.value?.start()
        } else {
            httpResponse.value?.start()
        }
    }

    fun onDataAvailable(availableBytes: Int) {
        if (availableBytes <= 0) {
            close()
            return
        }

        httpResponse.value?.readData(availableBytes)
    }

    fun onReadComplete(readBytes: Int) {
        httpResponse.value?.onReadComplete(readBytes)
    }

    fun onFrameReceived(status: WINHTTP_WEB_SOCKET_STATUS) {
        webSocket.value?.onReceiveFrame(status)
    }

    fun onDisconnect() {
        webSocket.value?.onDisconnect()
    }

    fun onError(message: String) {
        val cause = WinHttpIllegalStateException(message)
        close(cause)
    }

    override fun close() {
        close(null)
    }

    private fun close(cause: Exception?) {
        if (!closed.compareAndSet(expect = false, update = true)) return

        WinHttpSetStatusCallback(hRequest, null, 0, 0)

        webSocket.value?.close(cause)
        httpResponse.value?.close(cause)

        super.close()
        reference.dispose()
    }
}
