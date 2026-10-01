package com.android.virtualization.terminal.files

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

class DocumentPathsTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun resolvesRootsAndNestedFiles() {
        val root = temp.newFolder("data")
        val paths = DocumentPaths(mapOf("data" to root))
        assertEquals(root.canonicalFile, paths.resolve("data:"))
        assertEquals(File(root.canonicalFile, "files/a.json"), paths.resolve("data:files/a.json"))
        assertEquals("data:files/a.json", paths.child("data:files", "a.json"))
        assertEquals("data:folder:/child", paths.child("data:folder:", "child"))
        assertTrue(paths.isRoot("data:"))
    }
    @Test fun rejectsTraversalAndInvalidNames() {
        val paths = DocumentPaths(mapOf("data" to temp.newFolder()))
        listOf("data:../outside", "data:/etc", "data:a/../../outside", "data:a//b", "other:", "data").forEach {
            assertThrows(FileNotFoundException::class.java) { paths.resolve(it) }
        }
        listOf("../x", "/x", ".", "..", "a\u0000b").forEach {
            assertThrows(FileNotFoundException::class.java) { paths.child("data:", it) }
        }
    }
    @Test fun rejectsSymlinksInsideAndOutsideRoot() {
        val root = temp.newFolder("data")
        val inside = File(root, "target").apply { mkdir() }
        Files.createSymbolicLink(File(root, "internal").toPath(), inside.toPath())
        Files.createSymbolicLink(File(root, "external").toPath(), temp.newFolder("outside").toPath())
        val paths = DocumentPaths(mapOf("data" to root))
        listOf("data:internal", "data:internal/new", "data:external/new").forEach {
            assertThrows(FileNotFoundException::class.java) { paths.resolve(it) }
        }
    }
}
