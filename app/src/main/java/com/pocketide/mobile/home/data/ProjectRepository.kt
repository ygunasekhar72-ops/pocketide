package com.pocketide.mobile.home.data

import java.io.File

/**
 * Real, safe local project operations over the workspace root.
 * A project is a directory directly under the workspace root — nothing more.
 * This is the genuine persistence layer for M0.5; no mocked data.
 */
class ProjectRepository(val root: File) {

    data class ProjectInfo(
        val name: String,
        val path: String,
        val itemCount: Int,
        val lastModifiedMillis: Long
    )

    sealed interface CreateResult {
        data class Created(val name: String) : CreateResult
        data object AlreadyExists : CreateResult
        data object InvalidName : CreateResult
    }

    sealed interface DeleteResult {
        data object Deleted : DeleteResult
        data object NotFound : DeleteResult
        data object InvalidName : DeleteResult
    }

    /** Lists project directories sorted by last modification, newest first. */
    fun listProjects(): List<ProjectInfo> {
        root.mkdirs()
        return root.listFiles { file -> file.isDirectory }
            .orEmpty()
            .filter { !HIDDEN.contains(it.name) }
            .map { dir ->
                ProjectInfo(
                    name = dir.name,
                    path = dir.absolutePath,
                    itemCount = dir.listFiles()?.size ?: 0,
                    lastModifiedMillis = dir.lastModified()
                )
            }
            .sortedByDescending { it.lastModifiedMillis }
    }

    fun createProject(rawName: String): CreateResult {
        val name = validName(rawName) ?: return CreateResult.InvalidName
        val dir = File(root, name)
        if (dir.exists()) return CreateResult.AlreadyExists
        return runCatching { dir.mkdirs() }
            .fold(
                onSuccess = { created ->
                    if (created) CreateResult.Created(name) else CreateResult.InvalidName
                },
                onFailure = { CreateResult.InvalidName }
            )
    }

    fun deleteProject(rawName: String): DeleteResult {
        val name = validName(rawName) ?: return DeleteResult.InvalidName
        val dir = File(root, name)
        if (!dir.exists() || !dir.isDirectory) return DeleteResult.NotFound
        val deleted = dir.deleteRecursively()
        return if (deleted) DeleteResult.Deleted else DeleteResult.InvalidName
    }

    private fun validName(rawName: String): String? =
        rawName.trim().takeIf { NAME_PATTERN.matches(it) && it !in HIDDEN }

    companion object {
        // Alphanumeric start; letters, digits, dot, dash, underscore; <= 64 chars.
        // Blocks path separators/traversal since '/' '\' and '..' are excluded.
        // Public so the create dialog and tests validate with the same rule.
        val NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,64}$")
        private val HIDDEN = setOf(".", "..")
    }
}
