/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp

import io.ktor.client.engine.*

public class WinHttpEngineConfig : HttpClientEngineConfig() {

    /**
     * A value indicating whether HTTP 2.0 protocol is enabled in WinHTTP.
     * The default value is false.
     */
    public var enableHttp2Protocol: Boolean = false

    /**
     * A value that allows to set required security protocol versions.
     * By default will be used system setting.
     */
    public var securityProtocols: WinHttpSecurityProtocol = WinHttpSecurityProtocol.Default

    /**
     * A value that disables TLS verification for outgoing requests.
     */
    public var sslVerify: Boolean = true
}
