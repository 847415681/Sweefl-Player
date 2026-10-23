package com.sweefl.player.data.media

import com.sweefl.player.core.model.Video

import android.content.Context
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface VideoRepository {
    suspend fun videos(): List<Video>
}

class MediaStoreVideoRepository(private val context: Context) : VideoRepository {
    override suspend fun videos(): List<Video> = withContext(Dispatchers.IO) { queryVideos(context) }
}

private fun queryVideos(context: Context): List<Video> {
    val folderColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.DATA
    val columns = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, folderColumn)
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    return buildList {
        context.contentResolver.query(collection, columns, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(5).orEmpty()
                val folder = if (Build.VERSION.SDK_INT >= 29) path.trimEnd('/') else path.substringBeforeLast('/', "视频")
                add(Video(ContentUris.withAppendedId(collection, cursor.getLong(0)),
                    cursor.getString(1) ?: "未命名视频", folder.ifBlank { "视频" },
                    cursor.getLong(2), cursor.getLong(3), cursor.getLong(4)))
            }
        }
    }
}
