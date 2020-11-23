/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp.internal

import io.ktor.client.engine.*
import io.ktor.client.engine.winhttp.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.utils.io.core.*
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.*
import platform.windows.*
import kotlin.coroutines.*

internal class WinHttpSession(private val config: WinHttpEngineConfig) : Closeable {

    private var hSession: COpaquePointer
    private val closed = atomic(false)
    private val timeoutConfigured = atomic(false)

    init {
        hSession = WinHttpOpen(
            null, // User agent will be set in request headers
            WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
            null, null,
            WINHTTP_FLAG_ASYNC.convert()
        ) ?: throw createWinHttpError("Unable to create session")

        setSecurityProtocols()

        config.proxy?.let { proxy ->
            setProxy(proxy)
        }
    }

    suspend fun executeRequest(data: HttpRequestData, callContext: CoroutineContext): HttpResponseData {
        configureTimeouts(data)
        val httpRequest = WinHttpRequest(hSession, data, config, callContext)
        return httpRequest.executeRequest()
    }

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        WinHttpCloseHandle(hSession)
    }

    private fun setSecurityProtocols() = memScoped {
        val options = alloc<UIntVar> {
            value = config.securityProtocols.value.convert()
        }
        val dwSize = sizeOf<UIntVar>().convert<UInt>()
        WinHttpSetOption(hSession, WINHTTP_OPTION_SECURE_PROTOCOLS, options.ptr, dwSize)
    }

    private fun configureTimeouts(data: HttpRequestData) {
        if (!timeoutConfigured.compareAndSet(expect = false, update = true)) return

        val resolveTimeout = 10_000 // Domain name resolution timeout
        var connectTimeout = 60_000 // Server connection request timeout
        var sendTimeout = 30_000    // Sending request timeout
        var receiveTimeout = 30_000 // Receive response timeout

        data.getCapabilityOrNull(HttpTimeout)?.let { timeoutExtension ->
            timeoutExtension.connectTimeoutMillis?.let { value ->
                connectTimeout = value.toInt()
            }
            timeoutExtension.socketTimeoutMillis?.let { value ->
                sendTimeout = value.toInt()
                receiveTimeout = value.toInt()
            }
        }

        setTimeouts(resolveTimeout, connectTimeout, sendTimeout, receiveTimeout)
    }

    private fun setTimeouts(resolveTimeout: Int, connectTimeout: Int, sendTimeout: Int, receiveTimeout: Int) {
        if (WinHttpSetTimeouts(hSession, resolveTimeout, connectTimeout, sendTimeout, receiveTimeout) == 0) {
            throw createWinHttpError("Unable to set session timeouts")
        }
    }

    private fun setProxy(proxy: ProxyConfig) = memScoped {
        when (val type = proxy.type) {
            ProxyType.HTTP -> {
                val proxyInfo = alloc<WINHTTP_PROXY_INFO> {
                    dwAccessType = WINHTTP_ACCESS_TYPE_NAMED_PROXY.convert()
                    lpszProxy = proxy.url.toString().wcstr.ptr
                }

                if (WinHttpSetOption(hSession, WINHTTP_OPTION_PROXY, proxyInfo.ptr, sizeOf<WINHTTP_PROXY_INFO>().convert()) == 0) {
                    throw createWinHttpError("Unable to set proxy")
                }
            }
            else -> throw IllegalStateException("Proxy of type $type is unsupported by WinHTTP engine.")
        }
    }
}
