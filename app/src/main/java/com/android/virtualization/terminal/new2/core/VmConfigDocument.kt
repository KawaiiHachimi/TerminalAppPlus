/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** Validate the actual AVF configuration before replacing the last working file. */
object VmConfigDocument {
    private val keys = setOf("protected", "name", "cpu_topology", "memory_mib", "hugepages", "console_input_device", "bootloader", "kernel", "initrd", "params", "debuggable", "console_out", "connect_console", "network", "input", "audio", "usb_config", "disks", "sharedPath", "display", "gpu", "auto_memory_balloon", "boot_timeout_secs")
    fun parse(text: String): JsonObject {
        require(text.length <= 256 * 1024) { "配置超过 256 KiB" }
        val root = JsonParser.parseString(text)
        require(root.isJsonObject) { "配置必须是 JSON 对象" }
        val json = root.asJsonObject
        require(json.keySet().all { it in keys }) { "不支持的字段：${json.keySet() - keys}" }
        fun present(key: String) = json.get(key)?.let { !it.isJsonNull && it.asString.isNotBlank() } == true
        require(present("kernel") xor present("bootloader")) { "kernel 与 bootloader 必须且只能指定一个" }
        require(!present("bootloader") || !present("initrd")) { "U-Boot 模式不直接加载 initrd" }
        val memory = json.get("memory_mib")?.asString?.toIntOrNull()
        require(memory != null && memory in 256..262144) { "memory_mib 必须为 256～262144 的整数" }
        require(json.get("cpu_topology")?.asString in listOf("one_cpu", "match_host")) { "CPU 支持 one_cpu 或 match_host" }
        require(json.get("protected")?.asBoolean != true) { "当前仅支持非保护 VM" }
        require(json.get("console_out")?.asBoolean == true && json.get("connect_console")?.asBoolean != true) { "必须保持 console_out=true、connect_console=false，以供 App 控制台使用" }
        require(json.get("console_input_device")?.asString in listOf("hvc0", "ttyS0")) { "控制台设备支持 hvc0、ttyS0" }
        require(json.get("name")?.asString?.isNotBlank() == true) { "name 不得为空" }
        require(json.get("disks")?.isJsonArray == true) { "disks 必须是数组" }
        fun objectFields(value: com.google.gson.JsonElement?, allowed: Set<String>, label: String) {
            if (value == null || value.isJsonNull) return
            require(value.isJsonObject) { "$label 必须是对象" }
            require(value.asJsonObject.keySet().all { it in allowed }) { "$label 中有不支持的字段：${value.asJsonObject.keySet() - allowed}" }
        }
        objectFields(json.get("input"), setOf("touchscreen", "keyboard", "mouse", "switches", "trackpad"), "input")
        objectFields(json.get("usb_config"), setOf("controller"), "usb_config")
        objectFields(json.get("audio"), setOf("microphone", "speaker"), "audio")
        objectFields(json.get("display"), setOf("scale", "refresh_rate", "width_pixels", "height_pixels"), "display")
        objectFields(json.get("gpu"), setOf("backend", "pci_address", "renderer_features", "renderer_use_egl", "renderer_use_gles", "renderer_use_glx", "renderer_use_surfaceless", "renderer_use_vulkan", "context_types"), "gpu")
        json.getAsJsonArray("disks").forEach { disk ->
            objectFields(disk, setOf("image", "partitions", "writable"), "disks")
            val d = disk.asJsonObject
            require(d.has("image") xor d.has("partitions")) { "每个磁盘必须指定 image 或 partitions 之一" }
            d.getAsJsonArray("partitions")?.forEach { part -> objectFields(part, setOf("writable", "label", "path", "guid"), "partitions") }
        }
        return json
    }
    fun format(json: JsonObject): String = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n"
    fun validateFiles(json: JsonObject, directory: File) {
        val base = directory.canonicalFile
        fun checkPath(value: String, writable: Boolean = false) {
            val path = File(value.replace("\$PAYLOAD_DIR", base.path)).canonicalFile
            require(path.isFile && path.canRead()) { "无法读取文件：$value" }
            if (writable) require(path.path.startsWith(base.path + File.separator)) { "可写磁盘必须位于当前 VM 目录内，不能共享其他 VM 的磁盘" }
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
