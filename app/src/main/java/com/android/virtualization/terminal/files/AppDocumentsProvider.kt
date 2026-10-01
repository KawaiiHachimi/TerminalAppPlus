/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.files

import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.android.virtualization.terminal.R
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** SAF access to this application's data, granted by the Android system picker. */
class AppDocumentsProvider : DocumentsProvider() {
    private val authority get() = "${requireNotNull(context).packageName}.documents"
    private fun paths(): DocumentPaths {
        val app = requireNotNull(context)
        return DocumentPaths(buildMap {
            put("data", app.dataDir)
            put("user_de_data", app.createDeviceProtectedStorageContext().dataDir)
            app.getExternalFilesDir(null)?.parentFile?.let { put("android_data", it) }
            app.obbDir?.let { if (it.isDirectory) put("android_obb", it) }
        })
    }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        val values = mapOf(
            Root.COLUMN_ROOT_ID to "app",
            Root.COLUMN_DOCUMENT_ID to APP_ROOT,
            Root.COLUMN_TITLE to requireNotNull(context).getString(R.string.app_name),
            Root.COLUMN_FLAGS to (Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD),
            Root.COLUMN_ICON to R.mipmap.ic_launcher,
            Root.COLUMN_MIME_TYPES to "*/*",
            Root.COLUMN_AVAILABLE_BYTES to requireNotNull(context).dataDir.usableSpace,
        )
        addRow(columnNames.map { values[it] })
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?) =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { include(this, documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            if (parentDocumentId == APP_ROOT) {
                paths().roots.forEach { (name, directory) ->
                    if (directory.isDirectory) include(this, "$name:")
                }
                return@apply
            }
            val paths = paths()
            val parent = paths.resolve(parentDocumentId)
            val children = parent.listFiles() ?: throw FileNotFoundException("Cannot list directory")
            children.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).forEach {
                val id = paths.child(parentDocumentId, it.name)
                // Do not expose symlinks, sockets or other non-regular files.
                if (!Files.isSymbolicLink(it.toPath()) && (it.isDirectory || it.isFile)) include(this, id)
            }
            setNotificationUri(requireNotNull(context).contentResolver,
                DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId))
        }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (documentId == APP_ROOT) return false
        if (parentDocumentId == APP_ROOT) {
            paths().resolve(documentId)
            return true
        }
        if (parentDocumentId.substringBefore(':') != documentId.substringBefore(':')) return false
        val paths = paths()
        val parent = paths.resolve(parentDocumentId).toPath()
        val child = paths.resolve(documentId).toPath()
        return child != parent && child.startsWith(parent)
    }

    override fun getDocumentType(documentId: String) =
        if (documentId == APP_ROOT) Document.MIME_TYPE_DIR else mime(paths().resolve(documentId))

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val file = paths().resolve(documentId)
        if (!file.isFile) throw FileNotFoundException("Not a regular file")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val paths = paths()
        val id = paths.child(parentDocumentId, displayName)
        val file = paths.resolve(id)
        val created = if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        if (!created) throw FileNotFoundException("File already exists or cannot be created")
        changed(parentDocumentId)
        return id
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val paths = paths()
        if (paths.isRoot(documentId)) throw FileNotFoundException("Cannot rename root")
        val parent = parentId(documentId)
        val id = paths.child(parent, displayName)
        if (id == documentId) return id
        val target = paths.resolve(id)
        if (target.exists() || !paths.resolve(documentId).renameTo(target)) throw FileNotFoundException("Cannot rename file")
        changed(parent)
        return id
    }

    override fun deleteDocument(documentId: String) {
        val paths = paths()
        if (paths.isRoot(documentId)) throw FileNotFoundException("Cannot delete root")
        // walkFileTree does not follow symlinks, including links nested in a directory.
        Files.walkFileTree(paths.resolve(documentId).toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        changed(parentId(documentId))
    }

    private fun include(cursor: MatrixCursor, id: String) {
        if (id == APP_ROOT) {
            val values = mapOf(
                Document.COLUMN_DOCUMENT_ID to APP_ROOT,
                Document.COLUMN_DISPLAY_NAME to requireNotNull(context).getString(R.string.app_name),
                Document.COLUMN_MIME_TYPE to Document.MIME_TYPE_DIR,
                Document.COLUMN_FLAGS to 0,
            )
            cursor.addRow(cursor.columnNames.map { values[it] })
            return
        }
        val paths = paths()
        val file = paths.resolve(id)
        if (!file.exists()) throw FileNotFoundException(id)
        var flags = 0
        if (file.canWrite()) {
            flags = if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
            if (!paths.isRoot(id)) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        val values = mapOf(
            Document.COLUMN_DOCUMENT_ID to id,
            Document.COLUMN_DISPLAY_NAME to if (paths.isRoot(id)) id.substringBefore(':') else file.name,
            Document.COLUMN_MIME_TYPE to mime(file),
            Document.COLUMN_SIZE to if (file.isDirectory) null else file.length(),
            Document.COLUMN_LAST_MODIFIED to file.lastModified(),
            Document.COLUMN_FLAGS to flags,
        )
        cursor.addRow(cursor.columnNames.map { values[it] })
    }

    private fun mime(file: File) = if (file.isDirectory) Document.MIME_TYPE_DIR else
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    private fun parentId(id: String) = if ('/' in id) id.substringBeforeLast('/') else id.substringBefore(':') + ":"
    private fun changed(parent: String) = requireNotNull(context).contentResolver.notifyChange(
        DocumentsContract.buildChildDocumentsUri(authority, parent), null)

    companion object {
        private const val APP_ROOT = "app:"
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    }
}
