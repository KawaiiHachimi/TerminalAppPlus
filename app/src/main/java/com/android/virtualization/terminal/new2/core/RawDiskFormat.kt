/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

/** Import accepts whole raw disks. File extensions alone are not a format check. */
object RawDiskFormat {
    fun validate(header: ByteArray) {
        fun starts(vararg values: Int) = header.size >= values.size && values.indices.all { header[it].toInt() and 255 == values[it] }
        require(!starts(0x51, 0x46, 0x49, 0xfb)) { "请选择 raw 磁盘；qcow2 尚不支持，请先转换" }
        require(!starts(0x1f, 0x8b) && !starts(0xfd, 0x37, 0x7a, 0x58, 0x5a) && !starts(0x50, 0x4b)) {
            "请选择已解压的 raw 磁盘镜像"
        }
        require(header.size < 32774 || String(header, 32769, 5, Charsets.US_ASCII) != "CD001") {
            "暂不支持 ISO 安装镜像，请选择已安装系统的 raw 磁盘"
        }
        require(header.size >= 512 && header[510] == 0x55.toByte() && header[511] == 0xaa.toByte()) {
            "未识别到可启动的 raw 磁盘（MBR/GPT）；不支持 ISO、单独 rootfs 或任意数据文件"
        }
    }
}
