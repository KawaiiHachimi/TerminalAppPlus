/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal.new2.core

import com.android.virtualization.terminal.AppStrings
import com.android.virtualization.terminal.R
/** Import accepts whole raw disks. File extensions alone are not a format check. */
object RawDiskFormat {
    fun validate(header: ByteArray, requireBootSector: Boolean = true) {
        fun starts(vararg values: Int) = header.size >= values.size && values.indices.all { header[it].toInt() and 255 == values[it] }
        require(!starts(0x51, 0x46, 0x49, 0xfb)) { AppStrings.get(R.string.plus_raw_required) }
        require(!starts(0x1f, 0x8b) && !starts(0xfd, 0x37, 0x7a, 0x58, 0x5a) && !starts(0x50, 0x4b)) {
            AppStrings.get(R.string.plus_uncompressed_raw_required)
        }
        require(header.size < 32774 || String(header, 32769, 5, Charsets.US_ASCII) != "CD001") {
            AppStrings.get(R.string.plus_installer_iso_unsupported)
        }
        require(!requireBootSector || (header.size >= 512 && header[510] == 0x55.toByte() && header[511] == 0xaa.toByte())) {
            AppStrings.get(R.string.plus_bootable_raw_required)
        }
    }
}
