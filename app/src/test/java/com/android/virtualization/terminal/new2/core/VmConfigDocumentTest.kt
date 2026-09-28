package com.android.virtualization.terminal.new2.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class VmConfigDocumentTest {
    private val valid = """{"name":"test","bootloader":"${'$'}PAYLOAD_DIR/u-boot.bin","memory_mib":2048,"cpu_topology":"match_host","protected":false,"console_out":true,"connect_console":false,"console_input_device":"ttyS0","disks":[{"image":"${'$'}PAYLOAD_DIR/system.raw","writable":true}]}"""
    @Test fun roundTripPreservesEditedResources() {
        val parsed = VmConfigDocument.parse(valid)
        parsed.addProperty("memory_mib", 3072)
        parsed.addProperty("cpu_topology", "one_cpu")
        val saved = VmConfigDocument.parse(VmConfigDocument.format(parsed))
        assertEquals(3072, saved.get("memory_mib").asInt)
        assertEquals("one_cpu", saved.get("cpu_topology").asString)
    }
    @Test fun rejectsAmbiguousBootAndUnknownFields() {
        for ((key, value) in listOf("kernel" to "/kernel", "unknown" to "x")) {
            val json = VmConfigDocument.parse(valid).apply { addProperty(key, value) }
            assertThrows(IllegalArgumentException::class.java) { VmConfigDocument.parse(json.toString()) }
        }
    }
    @Test fun rejectsInvalidResourceAndConsoleSettings() {
        for ((key, value) in listOf("memory_mib" to "0", "memory_mib" to "1024.5", "cpu_topology" to "4", "console_input_device" to "../../etc/passwd")) {
            val json = VmConfigDocument.parse(valid).apply { addProperty(key, value) }
            assertThrows(IllegalArgumentException::class.java) { VmConfigDocument.parse(json.toString()) }
        }
    }
    @Test fun rejectsUnknownNestedOptions() {
        val json = VmConfigDocument.parse(valid)
        json.add("gpu", com.google.gson.JsonObject().apply { addProperty("typo_backend", "2d") })
        assertThrows(IllegalArgumentException::class.java) { VmConfigDocument.parse(json.toString()) }
    }
    @Test fun writableDisksCannotEscapeVmDirectory() {
        val root = Files.createTempDirectory("vm-config-test").toFile()
        try {
            val vm = java.io.File(root, "vm").apply { mkdir() }
            java.io.File(vm, "u-boot.bin").writeText("boot")
            java.io.File(vm, "system.raw").writeText("disk")
            val config = VmConfigDocument.parse(valid)
            VmConfigDocument.validateFiles(config, vm)
            val outside = java.io.File(root, "other.raw").apply { writeText("other VM") }
            config.getAsJsonArray("disks")[0].asJsonObject.addProperty("image", outside.path)
            assertThrows(IllegalArgumentException::class.java) { VmConfigDocument.validateFiles(config, vm) }
        } finally { root.deleteRecursively() }
    }
}
