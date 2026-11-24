package io.nativeforge.app.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/**
 * Projects outside the IDE's private storage.
 *
 * The IDE targets API 28 (see NativeForgeConfig), so Android gives it the classic storage
 * model: with the "Files and media" permission it reads and writes shared storage and SD
 * cards through ordinary paths, which the compiler, CMake, Make, clangd and git need (they
 * cannot work on `content://` URIs). Folders are chosen with the system folder picker; a
 * folder of the device's own storage maps to its path. Folders from other providers (cloud
 * drives) cannot be built in place and are copied into the workspace instead.
 * MANAGE_EXTERNAL_STORAGE ("all files access") is not needed.
 */
object DeviceFolders {

    val PERMISSIONS = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun hasStorageAccess(context: Context): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** The app's page in system settings, where a permanently denied permission can be granted. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Path of a folder picked with ACTION_OPEN_DOCUMENT_TREE, when it is on the device's
     * storage ("primary:Projects/Game" or "1A2B-3C4D:Game" on an SD card); null otherwise.
     */
    fun pathOf(context: Context, treeUri: Uri): File? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "")
        val base = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            volumeRoot(context, volume) ?: return null
        }
        return if (relative.isEmpty()) base else File(base, relative)
    }

    /** Root of a removable volume, found through the app's own folder on it (`<root>/Android/data/<package>/files`). */
    private fun volumeRoot(context: Context, volume: String): File? =
        context.getExternalFilesDirs(null).filterNotNull()
            .mapNotNull { dir -> generateSequence(dir) { it.parentFile }.firstOrNull { it.name == volume } }
            .firstOrNull()
            ?: File("/storage/$volume").takeIf { it.isDirectory }

    /** Display name of a picked folder. */
    fun nameOf(context: Context, treeUri: Uri): String? = runCatching {
        val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /**
     * Copies the picked folder (any provider) into [target], skipping build outputs and
     * `.git`. Returns the number of files copied.
     */
    fun copyTree(context: Context, treeUri: Uri, target: File, maxFiles: Int = 20_000): Int {
        val resolver = context.contentResolver
        var copied = 0
        fun copy(documentId: String, dir: File, depth: Int) {
            if (depth > 32) return
            dir.mkdirs()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            val entries = resolver.query(children, columns, null, null, null)?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))) }
            }.orEmpty()
            for ((id, name, mime) in entries) {
                // Names come from another app: keep them to one plain path segment.
                if (name.isNullOrBlank() || name == "." || name == ".." || '/' in name || '\u0000' in name) continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth == 0 && name == "build" || name == ".git") continue
                    copy(id, File(dir, name), depth + 1)
                } else {
                    if (++copied > maxFiles) throw IOException("The folder has more than $maxFiles files")
                    val source = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    resolver.openInputStream(source)?.use { input ->
                        File(dir, name).outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
        copy(DocumentsContract.getTreeDocumentId(treeUri), target, 0)
        return copied
    }
}
