package com.android.virtualization.terminal

import com.android.virtualization.terminal.new2.core.VmConfigDocument
import org.junit.Assert.*
import org.junit.Test

class GraphicsConfigTest {
    private val base = """{"protected":false,"name":"test","cpu_topology":"one_cpu","memory_mib":1024,"console_out":true,"console_input_device":"ttyS0","bootloader":"test","disks":[]}"""
    @Test fun optInAndInvalidOptions() {
        val json = VmConfigDocument.parse(base)
        json.addProperty("display_transport", "h264")
        VmConfigDocument.parse(json.toString())
        json.addProperty("display_transport", "raw")
        VmConfigDocument.parse(json.toString())
        json.addProperty("display_transport", "unknown")
        assertThrows(IllegalArgumentException::class.java) { VmConfigDocument.parse(json.toString()) }
    }
}
