package com.sweefl.player.data.common

import java.security.MessageDigest

/** Persistent schema: exact URI UTF-8 bytes, lower-case SHA-256; never normalize the URI. */
internal fun uriStorageKey(uri: String): String = MessageDigest.getInstance("SHA-256")
    .digest(uri.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
