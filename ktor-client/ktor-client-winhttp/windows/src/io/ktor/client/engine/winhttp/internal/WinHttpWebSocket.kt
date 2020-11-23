/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp.internal

import io.ktor.client.utils.*
import io.ktor.http.cio.websocket.*
import io.ktor.utils.io.core.*
import kotlinx.atomicfu.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import platform.windows.*
import kotlin.coroutines.*
import kotlin.native.concurrent.*

internal class WinHttpWebSocket(
    hRequest: COpaquePointer,
    callContext: CoroutineContext,
    context: COpaquePointer
) : WebSocketSession {

    private val closed = atomic(false)
    private val socketJob = Job(callContext[Job])

    private val hWebSocket: COpaquePointer
    private val readBuffer = atomic(EMPTY_BYTE_ARRAY.pin())
    private val writeBuffer = atomic(EMPTY_BYTE_ARRAY.pin())

    private val _incoming = Channel<Frame>(Channel.UNLIMITED)
    private val _outgoing = Channel<Frame>(Channel.UNLIMITED)

    override val coroutineContext: CoroutineContext = callContext + socketJob

    override var maxFrameSize: Long
        get() = Long.MAX_VALUE
        set(_) {}

    override val incoming: ReceiveChannel<Frame>
        get() = _incoming

    override val outgoing: SendChannel<Frame>
        get() = _outgoing

    init {
        val contextPtr = context.rawValue.toLong()
        hWebSocket = WinHttpWebSocketCompleteUpgrade(hRequest, contextPtr.convert())
            ?: throw createWinHttpError("Unable to upgrade websocket")

        @OptIn(ExperimentalCoroutinesApi::class)
        GlobalScope.launch(callContext, start = CoroutineStart.ATOMIC) {
            try {
                socketJob[Job]!!.join()
            } finally {
                close()
            }
        }
    }

    fun start() {
        // Start receiving frames
        launch {
            receiveNextFrame()
        }

        // Start sending frames
        launch {
            sendNextFrame()
        }
    }

    private fun receiveNextFrame() {
        if (closed.value) return

        val buffer = readBuffer.updateAndGet { oldBuffer ->
            oldBuffer.unpin()
            ByteArray(DEFAULT_HTTP_BUFFER_SIZE).freeze().pin()
        }

        if (WinHttpWebSocketReceive(
                hWebSocket,
                buffer.addressOf(0),
                buffer.get().size.convert(),
                null,
                null
            ) != 0u
        ) {
            socketJob.complete()
        }
    }

    fun onReceiveFrame(status: WINHTTP_WEB_SOCKET_STATUS) {
        val bufferType = status.eBufferType
        val bytesRead = status.dwBytesTransferred.toInt()

        when (bufferType) {
            WINHTTP_WEB_SOCKET_BINARY_MESSAGE_BUFFER_TYPE -> {
                val data = readBuffer.value.get().copyOf(bytesRead)
                _incoming.sendBlocking(Frame.Binary(fin = true, data = data))
            }
            WINHTTP_WEB_SOCKET_BINARY_FRAGMENT_BUFFER_TYPE -> {
                val data = readBuffer.value.get().copyOf(bytesRead)
                _incoming.sendBlocking(Frame.Binary(fin = false, data = data))
            }
            WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE -> {
                val data = readBuffer.value.get().copyOf(bytesRead)
                _incoming.sendBlocking(Frame.Text(fin = true, data = data))
            }
            WINHTTP_WEB_SOCKET_UTF8_FRAGMENT_BUFFER_TYPE -> {
                val data = readBuffer.value.get().copyOf(bytesRead)
                _incoming.sendBlocking(Frame.Text(fin = false, data = data))
            }
            WINHTTP_WEB_SOCKET_CLOSE_BUFFER_TYPE -> {
                val data = readBuffer.value.get().copyOf(bytesRead)
                _incoming.sendBlocking(Frame.Close(data))
            }
        }

        receiveNextFrame()
    }

    private suspend fun sendNextFrame() {
        if (closed.value) return

        val frame = _outgoing.receive()

        if (closed.value) return

        when (frame.frameType) {
            FrameType.TEXT -> {
                val type = if (frame.fin) {
                    WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE
                } else {
                    WINHTTP_WEB_SOCKET_UTF8_FRAGMENT_BUFFER_TYPE
                }
                sendFrame(type, frame.data)
            }
            FrameType.BINARY,
            FrameType.PING,
            FrameType.PONG -> {
                val type = if (frame.fin) {
                    WINHTTP_WEB_SOCKET_BINARY_MESSAGE_BUFFER_TYPE
                } else {
                    WINHTTP_WEB_SOCKET_BINARY_FRAGMENT_BUFFER_TYPE
                }
                sendFrame(type, frame.data)
            }
            FrameType.CLOSE -> {
                val data = buildPacket { writeFully(frame.data) }
                val code = data.readShort().toInt()
                val reason = data.readText()
                sendClose(code, reason)
                socketJob.complete()
            }
        }
    }

    private fun sendFrame(
        type: WINHTTP_WEB_SOCKET_BUFFER_TYPE,
        data: ByteArray
    ) {
        val buffer = writeBuffer.updateAndGet { oldBuffer ->
            oldBuffer.unpin()
            data.copyOf().pin()
        }

        if (WinHttpWebSocketSend(
                hWebSocket,
                type,
                buffer.addressOf(0),
                buffer.get().size.convert()
            ) != 0u
        ) {
            throw createWinHttpError("Unable to send data to WebSocket")
        }
    }

    private fun sendClose(code: Int, reason: String) {
        val buffer = reason.ifEmpty { null }?.let {
            writeBuffer.updateAndGet { oldBuffer ->
                oldBuffer.unpin()
                reason.toByteArray().pin()
            }
        }

        if (WinHttpWebSocketShutdown(
                hWebSocket,
                code.convert(),
                buffer?.addressOf(0),
                buffer?.get()?.size?.convert() ?: 0u
            ) != 0u
        ) {
            throw createWinHttpError("Unable to close WebSocket")
        }
    }

    fun onSendFrame() {
        writeBuffer.getAndSet(EMPTY_BYTE_ARRAY.pin()).unpin()

        launch {
            sendNextFrame()
        }
    }

    fun onDisconnect() = memScoped {
        if (closed.value) return@memScoped

        val status = alloc<UShortVar>()
        val reason = allocArray<ShortVar>(123)
        val reasonLengthConsumed = alloc<UIntVar>()

        try {
            if (WinHttpWebSocketQueryCloseStatus(
                    hWebSocket,
                    status.ptr,
                    null,
                    0,
                    reasonLengthConsumed.ptr
                ) != 0u
            ) {
                return@memScoped
            }

            _incoming.sendBlocking(
                Frame.Close(
                    CloseReason(
                        code = status.value.convert<Short>(),
                        message = reason.toKStringFromUtf16()
                    )
                )
            )
        } finally {
            socketJob.complete()
        }
    }

    override suspend fun flush() = Unit

    @Deprecated(
        "Use cancel() instead.",
        ReplaceWith("cancel()", "kotlinx.coroutines.cancel")
    )
    override fun terminate() {
        socketJob.cancel()
    }

    fun close(cause: Exception? = null) {
        if (!closed.compareAndSet(expect = false, update = true)) return

        _incoming.close()
        _outgoing.cancel()

        WinHttpWebSocketClose(
            hWebSocket,
            WINHTTP_WEB_SOCKET_SUCCESS_CLOSE_STATUS.convert(),
            NULL,
            0
        )
        WinHttpCloseHandle(hWebSocket)

        if (cause == null) {
            socketJob.complete()
        } else {
            socketJob.completeExceptionally(cause)
        }

        readBuffer.getAndSet(EMPTY_BYTE_ARRAY.pin()).unpin()
    }
}
