/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp.internal

import io.ktor.utils.io.*
import kotlinx.atomicfu.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.windows.*
import kotlin.coroutines.*

internal class WinHttpResponseBody(
    private val hRequest: COpaquePointer,
    callContext: CoroutineContext
) : CoroutineScope {

    private val consumerJob = Job(callContext[Job])
    private val responseChannel = ByteChannel().apply {
        attachJob(consumerJob)
    }

    private val readBuffer = atomic(EMPTY_BYTE_ARRAY.pin())
    private val closed = atomic(false)

    val body: ByteReadChannel
        get() = responseChannel

    override val coroutineContext: CoroutineContext = callContext + consumerJob

    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        GlobalScope.launch(callContext, start = CoroutineStart.ATOMIC) {
            try {
                consumerJob[Job]!!.join()
            } finally {
                close()
            }
        }
    }

    fun start() {
        queryDataAvailable()
    }

    private fun queryDataAvailable() {
        if (WinHttpQueryDataAvailable(hRequest, null) == 0) {
            throw createWinHttpError("Unable to query data length")
        }
    }

    fun readData(availableBytes: Int) {
        val buffer = readBuffer.updateAndGet { oldBuffer ->
            oldBuffer.unpin()
            ByteArray(availableBytes).pin()
        }

        if (WinHttpReadData(hRequest, buffer.addressOf(0), availableBytes.convert(), null) == 0) {
            throw createWinHttpError("Unable to read response data")
        }
    }

    fun onReadComplete(readBytes: Int) {
        val buffer = readBuffer.value.get()

        runBlocking {
            responseChannel.writeFully(buffer, 0, readBytes)
        }

        queryDataAvailable()
    }

    fun close(cause: Exception? = null) {
        if (!closed.compareAndSet(expect = false, update = true)) return

        if (cause == null) {
            consumerJob.complete()
            responseChannel.close()
        } else {
            consumerJob.completeExceptionally(cause)
            responseChannel.close(cause)
        }

        readBuffer.getAndSet(EMPTY_BYTE_ARRAY.pin()).unpin()
    }
}
