package app.jarvis.glasses.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.IOException

/**
 * [bytes] saved in Downloads/Jarvis as [name], made safe ([safeName]): a file a job made, a project's,
 * a specialty exported. Saved, never opened by the app. No permission is needed for it from Android
 * 10 on. What it was saved as, in words.
 */
fun saveDownload(context: Context, name: String, bytes: ByteArray): String {
  val safe = safeName(name)
  val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(safe.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
  val resolver = context.contentResolver
  val values =
      ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, safe)
        put(MediaStore.Downloads.MIME_TYPE, type)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Jarvis")
        put(MediaStore.Downloads.IS_PENDING, 1)
      }
  val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Couldn't save it in Downloads")
  try {
    (resolver.openOutputStream(uri) ?: throw IOException("Couldn't write it")).use { it.write(bytes) }
    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
  } catch (e: IOException) {
    resolver.delete(uri, null, null)
    throw e
  }
  return "Saved in Downloads/Jarvis as $safe"
}

/** A file's name as it may be saved: its last part only, without what a path or a hidden file is made of. Someone else may have named it. */
fun safeName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\x00-\\x1f:*?\"<>|]"), "_").trim().trimStart('.').trim().take(120).ifEmpty { "file" }
