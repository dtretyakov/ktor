/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp

import io.ktor.client.engine.*
import io.ktor.client.engine.winhttp.internal.WinHttpSession
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

internal class WinHttpEngine(
    override val config: WinHttpEngineConfig
) : HttpClientEngineBase("ktor-winhttp") {

    override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined

    override val supportedCapabilities: Set<HttpTimeout.Feature> = setOf(HttpTimeout)

    override val coroutineContext: CoroutineContext

    private val requestsJob = SilentSupervisor(super.coroutineContext[Job])

    private val session = WinHttpSession(config)

    init {
        coroutineContext = super.coroutineContext + requestsJob + CoroutineName("winhttp-engine")

        @OptIn(ExperimentalCoroutinesApi::class)
        GlobalScope.launch(super.coroutineContext, start = CoroutineStart.ATOMIC) {
            try {
                requestsJob[Job]!!.join()
            } finally {
                session.close()
            }
        }
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()
        return session.executeRequest(data, callContext)
    }
}

@Suppress("KDocMissingDocumentation")
public class WinHttpIllegalStateException(cause: String) : IllegalStateException(cause)
