/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp

import io.ktor.client.engine.*

private val initHook = WinHttp

/**
 * [HttpClientEngineFactory] using a [WinHttp] in implementation
 * with the the associated configuration [HttpClientEngineConfig].
 */
public object WinHttp : HttpClientEngineFactory<WinHttpEngineConfig> {
    init {
        engines.append(this)
    }

    override fun create(block: WinHttpEngineConfig.() -> Unit): HttpClientEngine {
        return WinHttpEngine(WinHttpEngineConfig().apply(block))
    }

    override fun toString(): String {
        return "WinHTTP"
    }
}
