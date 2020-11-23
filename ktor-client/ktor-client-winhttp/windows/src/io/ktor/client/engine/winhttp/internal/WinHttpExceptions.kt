/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.winhttp.internal

import io.ktor.client.engine.winhttp.WinHttpIllegalStateException
import kotlinx.cinterop.*
import platform.windows.*

private val formatSystemMessageFlags = (FORMAT_MESSAGE_FROM_SYSTEM or
    FORMAT_MESSAGE_IGNORE_INSERTS or
    FORMAT_MESSAGE_ARGUMENT_ARRAY or
    FORMAT_MESSAGE_ALLOCATE_BUFFER).convert<UInt>()

private val formatWinHttpMessageFlags = (FORMAT_MESSAGE_FROM_HMODULE or
    FORMAT_MESSAGE_IGNORE_INSERTS or
    FORMAT_MESSAGE_ARGUMENT_ARRAY or
    FORMAT_MESSAGE_ALLOCATE_BUFFER).convert<UInt>()

private val languageId = MakeLanguageId(LANG_NEUTRAL, SUBLANG_DEFAULT)

/**
 * Creates an exception from last WinHTTP error.
 */
internal fun createWinHttpError(message: String): WinHttpIllegalStateException {
    val errorCode = GetLastError()
    return WinHttpIllegalStateException("$message: ${getWinHttpErrorMessage(errorCode)}")
}

/**
 * Creates an error message from WinHTTP error.
 */
internal fun getWinHttpErrorMessage(errorCode: UInt): String {
    val moduleHandle = GetModuleHandleW("winhttp.dll")

    val errorMessage = memScoped {
        val bufferPtr = alloc<CPointerVar<UShortVar>>()

        // Try to get WinHTTP error message
        val winHttpResult = FormatMessageW(
            formatWinHttpMessageFlags,
            moduleHandle,
            errorCode,
            languageId,
            bufferPtr.reinterpret(),
            0,
            null
        )

        val formatResult = if (winHttpResult == 0u) {
            // If no message found in WinHTTP module,
            // try using system message
            if (GetLastError() == 317u) {
                FormatMessageW(
                    formatSystemMessageFlags,
                    null,
                    errorCode,
                    languageId,
                    bufferPtr.reinterpret(),
                    0,
                    null
                )
            } else 0u
        } else winHttpResult

        if (formatResult == 0u) {
            return@memScoped "Unknown error"
        }

        try {
            bufferPtr.value?.toKString() ?: error("Invalid value")
        } finally {
            LocalFree(bufferPtr.reinterpret())
        }
    }

    return "$errorMessage $errorCode (${GetHResultFromError(errorCode).toString(16)})"
}
