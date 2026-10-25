package com.sweefl.player.data.online

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/** Bilibili's public web request signature; this does not grant account permissions. */
object BilibiliWbi {
    private val permutation = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13)
    fun mixinKey(imageUrl: String, subUrl: String): String {
        fun stem(url: String) = URI(url).path.substringAfterLast('/').substringBefore('.')
        val raw = stem(imageUrl) + stem(subUrl)
        require(raw.length >= 64) { "Invalid WBI key response" }
        return permutation.map { raw[it] }.joinToString("")
    }
    fun sign(parameters: Map<String, String>, mixinKey: String, epochSeconds: Long): String {
        val values = parameters.filterKeys { it != "w_rid" }.toMutableMap()
        values["wts"] = epochSeconds.toString()
        val query = values.toSortedMap().entries.joinToString("&") { (key, value) ->
            encode(key) + "=" + encode(value.filterNot { it in "!'()*" })
        }
        val hash = MessageDigest.getInstance("MD5").digest((query + mixinKey).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$query&w_rid=$hash"
    }
    internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")
}
