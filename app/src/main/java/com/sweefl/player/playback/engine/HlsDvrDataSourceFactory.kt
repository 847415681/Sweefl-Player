@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.playback.engine

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** One policy is shared by all loaders created for this engine. */
internal class HlsDvrDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val policy: HlsDvrUriPolicy,
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val delegate = upstream.createDataSource()
        return object : DataSource by delegate {
            override fun open(dataSpec: DataSpec): Long {
                val original = dataSpec.uri.toString()
                val rewritten = policy.rewrite(original)
                val request = if (rewritten == original) dataSpec else dataSpec.withUri(Uri.parse(rewritten))
                val length = delegate.open(request)
                policy.onSuccessfulOpen(original, delegate.uri?.toString())
                return length
            }
        }
    }
}
