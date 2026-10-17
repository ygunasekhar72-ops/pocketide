package com.pocketide.mobile.home.data

import android.content.Context
import java.io.File

/**
 * Single source of truth for the IDE workspace root.
 * The editor and the terminal must share this mapping; verified by
 * ProjectRepositoryTest in M0.5 and re-verified by the terminal gate in M1.
 */
object Workspace {
    const val DIR_NAME = "workspace"

    fun root(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }
}
