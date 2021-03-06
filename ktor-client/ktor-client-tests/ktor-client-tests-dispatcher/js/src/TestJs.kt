package io.ktor.client.tests.utils.dispatcher

import kotlinx.coroutines.*
import kotlin.coroutines.*

/**
 * Test runner for js suspend tests.
 */
actual fun testSuspend(
    context: CoroutineContext,
    block: suspend CoroutineScope.() -> Unit
): dynamic = GlobalScope.promise(block = block, context = context)
