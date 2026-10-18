package com.example.sportstreamer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.FileDescriptor
import java.io.IOException

/**
 * Локальная запись трансляции в MediaStore (scoped storage).
 *
 * Файл создаётся с IS_PENDING = 1 и становится видимым в галерее только после корректной
 * остановки записи, поэтому «битые» видео не появляются. Отдельный класс, чтобы Activity
 * не занималась файловыми дескрипторами.
 */
class VideoRecorder(private val context: Context) {

    private var pendingUri: Uri? = null
    private var descriptor: ParcelFileDescriptor? = null

    val isActive: Boolean
        get() = descriptor != null

    /** @return дескриптор для энкодера или null, если запись начать не удалось. */
    fun start(displayName: String): FileDescriptor? {
        if (isActive) return descriptor?.fileDescriptor

        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$RELATIVE_FOLDER"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver
                .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Log.e(TAG, "MediaStore не создал запись для $displayName")
                return null
            }

            val pfd = context.contentResolver.openFileDescriptor(uri, "w")
            if (pfd == null) {
                Log.e(TAG, "Не удалось открыть дескриптор для $uri")
                context.contentResolver.delete(uri, null, null)
                return null
            }

            pendingUri = uri
            descriptor = pfd
            pfd.fileDescriptor
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка начала записи", e)
            discard()
            null
        }
    }

    /** Останавливает запись: закрывает дескриптор и публикует файл в галерее. */
    fun stop(): Boolean {
        val uri = pendingUri
        closeDescriptor()
        pendingUri = null
        if (uri == null) return false

        return try {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось опубликовать запись $uri", e)
            false
        }
    }

    /** Закрывает дескриптор и удаляет недописанный файл (например, при ошибке старта). */
    fun discard() {
        val uri = pendingUri
        closeDescriptor()
        pendingUri = null
        if (uri == null) return

        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось удалить недописанный файл $uri", e)
        }
    }

    private fun closeDescriptor() {
        try {
            descriptor?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Ошибка закрытия дескриптора", e)
        } finally {
            descriptor = null
        }
    }

    private companion object {
        const val TAG = "VideoRecorder"
        const val MIME_TYPE = "video/mp4"
        const val RELATIVE_FOLDER = "SportStreamer"
    }
}
