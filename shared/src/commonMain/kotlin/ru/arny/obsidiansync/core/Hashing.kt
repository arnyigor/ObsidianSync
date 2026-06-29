package ru.arny.obsidiansync.core

import okio.ByteString.Companion.toByteString

fun sha256Hex(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

fun verifySha256(bytes: ByteArray, expectedHash: String): Boolean =
    sha256Hex(bytes).equals(expectedHash, ignoreCase = true)
