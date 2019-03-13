package io.ktor.client.engine.winhttp

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.engine.winhttp.internal.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.util.date.*
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.*
import kotlinx.coroutines.io.*
import kotlin.coroutines.*
import kotlin.math.min

internal class WinHttpClientEngine(override val config: WinHttpClientEngineConfig) : HttpClientEngine {
    override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    override val coroutineContext: CoroutineContext = dispatcher + SupervisorJob()

    override suspend fun execute(
        call: HttpClientCall,
        data: HttpRequestData
    ): HttpEngineCall {
        val callContext = coroutineContext + CompletableDeferred<Unit>()
        val request = DefaultHttpRequest(call, data)
        val requestTime = GMTDate()

        val isAsyncMode = config.isAsynchronousWorkingMode

        val response = if (isAsyncMode) {
            executeAsyncRequest(request, requestTime, callContext)
        } else {
            executeSyncRequest()
        }

        WinHttpSession(isAsyncMode).use { session ->
            session.setTimeouts(
                config.resolveTimeout,
                config.connectTimeout,
                config.sendTimeout,
                config.receiveTimeout
            )

            if (config.securityProtocols != WinHttpSecurityProtocol.Default) {
                session.setSecurityProtocols(config.securityProtocols)
            }

            session.createRequest(request.method, request.url).use { httpRequest ->
                if (config.enableHttp2Protocol) {
                    httpRequest.enableHttp2Protocol()
                }

                httpRequest.addHeaders(request.headersToList())

                if (isAsyncMode) {
                    httpRequest.sendAsync().await()
                } else {
                    httpRequest.send()
                }

                request.content.toByteArray()?.let { body ->
                    body.usePinned {
                        if (isAsyncMode) {
                            httpRequest.writeBodyAsync(it).await()
                        } else {
                            httpRequest.writeBody(it)
                        }
                    }
                }

                val responseData = if (isAsyncMode) {
                    httpRequest.readResponseAsync().await()
                } else {
                    httpRequest.readResponse()
                }
                with(responseData) {
                    val status = HttpStatusCode.fromValue(status)
                    val httpVersion = HttpProtocolVersion.parse(version)

                    val headerBytes = ByteReadChannel(headers).apply {
                        readUTF8Line()
                    }

                    val headers = parseHeaders(headerBytes)

                    val body = writer(coroutineContext) {
                        while (true) {
                            val dataAvailable = if (isAsyncMode) {
                                httpRequest.queryDataAvailableAsync().await()
                            } else {
                                httpRequest.queryDataAvailable()
                            }
                            if (dataAvailable <= 0) break

                            val bufferSize = min(dataAvailable, Int.MAX_VALUE.toLong()).toInt()
                            val buffer = ByteArray(bufferSize)
                            val size = buffer.usePinned {
                                if (isAsyncMode) {
                                    httpRequest.readDataAsync(it).await()
                                } else {
                                    httpRequest.readData(it)
                                }
                            }
                            channel.writeFully(buffer, 0, size)
                        }
                    }.channel


                    callContext[Job]!!.invokeOnCompletion {
                        headers.release()
                    }

                    WinHttpResponse(
                        call, status, CIOHeaders(headers), requestTime,
                        body, callContext, httpVersion
                    )
                }
            }
        }

        return HttpEngineCall(request, response)
    }

    private suspend fun executeAsyncRequest(
        request: HttpRequest,
        requestTime: GMTDate,
        callContext: CoroutineContext
    ): HttpResponse {
        val session = WinHttpSession(true)

        with(config) {
            session.setTimeouts(resolveTimeout, connectTimeout, sendTimeout, receiveTimeout)


            if (securityProtocols != WinHttpSecurityProtocol.Default) {
                session.setSecurityProtocols(securityProtocols)
            }
        }

        val rawRequest = session.createRequest(request.method, request.url)
        if (config.enableHttp2Protocol) rawRequest.enableHttp2Protocol()
        rawRequest.addHeaders(request.headersToList())
        rawRequest.sendAsync().await()

        val body = request.content.toByteArray()
        body?.usePinned {
            rawRequest.writeBodyAsync(it).await()
        }

        val responseData = rawRequest.readResponseAsync().await()
        with(responseData) {
            val status = HttpStatusCode.fromValue(status)
            val httpVersion = HttpProtocolVersion.parse(version)

            val headerBytes = ByteReadChannel(headers).apply {
                readUTF8Line()
            }

            val headers = parseHeaders(headerBytes)

            callContext[Job]!!.invokeOnCompletion {
                headers.release()
            }

            val responseBody = rawRequest.readBodyAsync()

            return WinHttpResponse(
                request.call, status, CIOHeaders(headers), requestTime,
                responseBody, callContext, httpVersion
            )
        }
    }

    private suspend fun executeSyncRequest(): HttpResponse {
        TODO()
    }

    private fun WinHttpRequest.readBodyAsync(): ByteReadChannel = writer(coroutineContext) {
        while (true) {
            val dataAvailable = queryDataAvailableAsync().await()
            if (dataAvailable <= 0) break

            val bufferSize = min(dataAvailable, Int.MAX_VALUE.toLong()).toInt()
            val buffer = ByteArray(bufferSize)
            val size = buffer.usePinned {
                readDataAsync(it).await()
            }
            channel.writeFully(buffer, 0, size)
        }
    }.channel

    override fun close() {
        coroutineContext.cancel()
    }
}

@Suppress("KDocMissingDocumentation")
class WinHttpIllegalStateException(cause: String) : IllegalStateException(cause)
