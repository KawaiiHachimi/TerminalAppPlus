/* Copyright 2026 Terminal Plus contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.virtualization.terminal

import android.content.Context
import androidx.annotation.StringRes

/** Application resources for user-facing messages produced outside the UI. */
internal object AppStrings {
    internal lateinit var resolve: (Int, Array<out Any>) -> String

    fun initialize(context: Context) {
        val application = context.applicationContext
        resolve = { id, args -> application.getString(id, *args) }
    }

    fun get(@StringRes id: Int, vararg args: Any): String = resolve(id, args)
}
