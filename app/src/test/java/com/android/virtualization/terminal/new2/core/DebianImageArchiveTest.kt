package com.android.virtualization.terminal.new2.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.*
import org.junit.Test

class DebianImageArchiveTest : com.android.virtualization.terminal.LocalizedResourcesTest() {
    private val config = """{"name":"debian","platform_version":"~1.0","kernel":"${'$'}PAYLOAD_DIR/vmlinuz","memory_mib":4096,"cpu_topology":"match_host","console_input_device":"ttyS0","console_out":true,"connect_console":true,"disks":[{"image":"${'$'}PAYLOAD_DIR/root_part","writable":true}]}"""
    private fun archive(vararg files: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(bytes)).use { tar ->
            files.forEach { (name, content) ->
                val entry = TarArchiveEntry(name).apply { size = content.size.toLong() }
                tar.putArchiveEntry(entry); tar.write(content); tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }
    private fun inDirectory(action: (File) -> Unit) {
        val dir = Files.createTempDirectory("debian-import").toFile()
        try { action(dir) } finally { dir.deleteRecursively() }
    }
    @Test fun importsBundleAndAdaptsVmIdentityWithoutChangingResources() = inDirectory { dir ->
        val bytes = archive("vm_config.json" to config.toByteArray(), "vmlinuz" to byteArrayOf(1), "root_part" to ByteArray(1024 * 1024))
        runBlocking { DebianImageArchive.extract(ByteArrayInputStream(bytes), dir) {} }
        val json = VmConfigDocument.parse(DebianImageArchive.configuration(dir, "test"))
        assertEquals("plus-test", json.get("name").asString)
        assertEquals(4096, json.get("memory_mib").asInt)
        assertFalse(json.has("platform_version"))
        assertFalse(json.get("connect_console").asBoolean)
        assertEquals(1024 * 1024L, File(dir, "root_part").length())
    }
    @Test fun rejectsTraversalAndDuplicatePaths() = inDirectory { dir ->
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { DebianImageArchive.extract(ByteArrayInputStream(archive("../escape" to byteArrayOf(1))), dir) {} }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { DebianImageArchive.extract(ByteArrayInputStream(archive("a" to byteArrayOf(1), "./a" to byteArrayOf(2))), dir) {} }
        }
    }
    @Test fun rejectsIncompleteBundle() = inDirectory { dir ->
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { DebianImageArchive.extract(ByteArrayInputStream(archive("disk.img" to byteArrayOf(1))), dir) {} }
        }
    }
    @Test fun rejectsConfigReferencingAnotherVm() = inDirectory { dir ->
        File(dir, "vm_config.json").writeText(config.replace("${'$'}PAYLOAD_DIR/vmlinuz", "/other-vm/vmlinuz"))
        assertThrows(IllegalArgumentException::class.java) { DebianImageArchive.configuration(dir, "test") }
    }
}
