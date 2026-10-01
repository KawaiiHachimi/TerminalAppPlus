/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.files

import java.io.File
import java.io.FileNotFoundException

internal class DocumentPaths(roots: Map<String, File>) {
    val roots = roots.mapValues { it.value.canonicalFile }

    fun resolve(id: String): File {
        val root = roots[id.substringBefore(':')] ?: throw FileNotFoundException("Unknown root")
        if (':' !in id) throw FileNotFoundException("Invalid document ID")
        val relative = id.substringAfter(':')
        if (relative.isNotEmpty() && relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw FileNotFoundException("Invalid path")
        }
        val file = if (relative.isEmpty()) root else File(root, relative)
        if (file.canonicalFile != file.absoluteFile || !file.toPath().startsWith(root.toPath())) {
            throw FileNotFoundException("Links and paths outside the root are not supported")
        }
        return file
    }

    fun child(parent: String, name: String): String {
        if (name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\u0000' }) {
            throw FileNotFoundException("Invalid file name")
        }
        return parent + (if (isRoot(parent)) "" else "/") + name
    }

    fun isRoot(id: String) = id in roots.keys.map { "$it:" }
}
