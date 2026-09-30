/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import com.android.virtualization.terminal.AppStrings
import com.android.virtualization.terminal.R
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** Validate the actual AVF configuration before replacing the last working file. */
object VmConfigDocument {
    private val keys = setOf("protected", "name", "cpu_topology", "memory_mib", "hugepages", "console_input_device", "bootloader", "kernel", "initrd", "params", "debuggable", "console_out", "connect_console", "network", "input", "audio", "usb_config", "disks", "sharedPath", "display", "gpu", "auto_memory_balloon", "boot_timeout_secs")
    fun parse(text: String): JsonObject {
        require(text.length <= 256 * 1024) { AppStrings.get(R.string.plus_config_too_large) }
        val root = JsonParser.parseString(text)
        require(root.isJsonObject) { AppStrings.get(R.string.plus_config_object_required) }
        val json = root.asJsonObject
        require(json.keySet().all { it in keys }) { AppStrings.get(R.string.plus_unsupported_fields , json.keySet() - keys) }
        fun present(key: String) = json.get(key)?.let { !it.isJsonNull && it.asString.isNotBlank() } == true
        require(present("kernel") xor present("bootloader")) { AppStrings.get(R.string.plus_kernel_bootloader_exclusive) }
        require(!present("bootloader") || !present("initrd")) { AppStrings.get(R.string.plus_uboot_no_initrd) }
        val memory = json.get("memory_mib")?.asString?.toIntOrNull()
        require(memory != null && memory in 256..262144) { AppStrings.get(R.string.plus_invalid_memory) }
        require(json.get("cpu_topology")?.asString in listOf("one_cpu", "match_host")) { AppStrings.get(R.string.plus_invalid_cpu_topology) }
        require(json.get("protected")?.asBoolean != true) { AppStrings.get(R.string.plus_protected_vm_unsupported) }
        require(json.get("console_out")?.asBoolean == true && json.get("connect_console")?.asBoolean != true) { AppStrings.get(R.string.plus_console_flags_required) }
        require(json.get("console_input_device")?.asString in listOf("hvc0", "ttyS0")) { AppStrings.get(R.string.plus_invalid_console_device) }
        require(json.get("name")?.asString?.isNotBlank() == true) { AppStrings.get(R.string.plus_config_name_required) }
        require(json.get("disks")?.isJsonArray == true) { AppStrings.get(R.string.plus_disks_array_required) }
        fun objectFields(value: com.google.gson.JsonElement?, allowed: Set<String>, label: String) {
            if (value == null || value.isJsonNull) return
            require(value.isJsonObject) { AppStrings.get(R.string.plus_config_object_label , label) }
            require(value.asJsonObject.keySet().all { it in allowed }) { AppStrings.get(R.string.plus_unsupported_object_fields , label, value.asJsonObject.keySet() - allowed) }
        }
        objectFields(json.get("input"), setOf("touchscreen", "keyboard", "mouse", "switches", "trackpad"), "input")
        objectFields(json.get("usb_config"), setOf("controller"), "usb_config")
        objectFields(json.get("audio"), setOf("microphone", "speaker"), "audio")
        objectFields(json.get("display"), setOf("scale", "refresh_rate", "width_pixels", "height_pixels"), "display")
        objectFields(json.get("gpu"), setOf("backend", "pci_address", "renderer_features", "renderer_use_egl", "renderer_use_gles", "renderer_use_glx", "renderer_use_surfaceless", "renderer_use_vulkan", "context_types"), "gpu")
        json.getAsJsonArray("disks").forEach { disk ->
            objectFields(disk, setOf("image", "partitions", "writable"), "disks")
            val d = disk.asJsonObject
            require(d.has("image") xor d.has("partitions")) { AppStrings.get(R.string.plus_image_partitions_exclusive) }
            d.getAsJsonArray("partitions")?.forEach { part -> objectFields(part, setOf("writable", "label", "path", "guid"), "partitions") }
        }
        return json
    }
    fun format(json: JsonObject): String = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n"
    fun validateFiles(json: JsonObject, directory: File) {
        val base = directory.canonicalFile
        fun checkPath(value: String, writable: Boolean = false) {
            val path = File(value.replace("\$PAYLOAD_DIR", base.path)).canonicalFile
            require(path.isFile && path.canRead()) { AppStrings.get(R.string.plus_file_unreadable , value) }
            if (writable) require(path.path.startsWith(base.path + File.separator)) { AppStrings.get(R.string.plus_writable_disk_location) }
        }
        for (key in listOf("kernel", "initrd", "bootloader")) json.get(key)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { checkPath(it) }
        json.getAsJsonArray("disks").forEach { item ->
            val disk = item.asJsonObject
            val writable = disk.get("writable")?.asBoolean == true
            disk.get("image")?.takeUnless { it.isJsonNull }?.let { checkPath(it.asString, writable) }
            disk.getAsJsonArray("partitions")?.forEach { partition ->
                val part = partition.asJsonObject
                checkPath(part.get("path").asString, writable && part.get("writable")?.asBoolean == true)
            }
        }
    }
}
