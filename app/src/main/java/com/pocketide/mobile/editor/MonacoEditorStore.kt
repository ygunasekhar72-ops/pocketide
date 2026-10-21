package com.pocketide.mobile.editor

import com.pocketide.mobile.home.data.ProjectRepository
import java.io.File

/** Editor document content + Monaco language id. */
data class MonacoDocument(val content: String = "", val language: String = DEFAULT_LANGUAGE) {
    init {
        require(language.isNotBlank()) { "language must be non-blank" }
    }

    companion object {
        const val DEFAULT_LANGUAGE: String = "plaintext"
    }
}

/**
 * Per-project editor document persistence for the M2 PoC (file-backed).
 *
 * Scope decision (documented arrival report): document storage uses the
 * app-private `editor-docs` directory UNDER the milestone-3 file-manage-
 * ment scope; M3 will move real project files. Each project name is
 * validated with the EXACT public pattern of the projects UI
 * ([ProjectRepository.NAME_PATTERN]) so a file name can never escape
 * [root]: traversal shapes are rejected outright (this check is itself
 * covered by the corpus-consistency test with the projects API tests).
 *
 * Storage format is the same JSON the editor bridge speaks (one object:
 * "content", "language"), encoded via [MonacoJson] — no extra dependency.
 */
class MonacoEditorStore(private val root: File) {

    init {
        require(!root.path.endsWith(File.separator)) { "stray separator" }
    }

    fun save(projectName: String, document: MonacoDocument) {
        requireValidName(projectName)
        require(document.content.length <= MAX_DOCUMENT_CHARS) {
            "document exceeds the ${MAX_DOCUMENT_CHARS} character store bound"
        }
        val charset = Charsets.UTF_8
        root.mkdirs()
        val file = fileFor(projectName)
        val encodingBody = MonacoJson.Json.Obj(
            mapOf(
                "content" to MonacoJson.Json.Str(document.content),
                "language" to MonacoJson.Json.Str(document.language)
            )
        )
        val rendered = renderObj(encodingBody)
        // Write-then-rename keeps a crash from storing half a document.
        val tmp = File(root, file.name + ".tmp")
        tmp.writeText(rendered, charset)
        if (!tmp.renameTo(file) || !file.isFile) {
            tmp.delete()
            throw IllegalStateException("unable to persist editor document")
        }
    }

    fun load(projectName: String): MonacoDocument? {
        requireValidName(projectName)
        val file = fileFor(projectName)
        if (!file.isFile) return null
        return parseDocument(file.readText(Charsets.UTF_8))
            ?: throw IllegalStateException("stored document is corrupt")
    }

    private fun fileFor(projectName: String): File = File(root, "$projectName.json")

    private fun parseDocument(raw: String): MonacoDocument? {
        val obj = MonacoJson.parse(raw) as? MonacoJson.Json.Obj ?: return null
        val content = (obj.entries["content"] as? MonacoJson.Json.Str)?.value ?: return null
        val language = (obj.entries["language"] as? MonacoJson.Json.Str)?.value
            ?: return null
        if (content.length > MAX_DOCUMENT_CHARS) return null
        return MonacoDocument(content, language)
    }

    private fun requireValidName(projectName: String) {
        require(ProjectRepository.NAME_PATTERN.matches(projectName)) {
            "project name must match the projects pattern: '$projectName'"
        }
    }

    private fun renderObj(obj: MonacoJson.Json.Obj): String {
        val contentField = obj.entries["content"] as? MonacoJson.Json.Str
            ?: throw IllegalArgumentException("document must carry string content")
        val langField = obj.entries["language"] as? MonacoJson.Json.Str
            ?: throw IllegalArgumentException("document must carry string language")
        return "{\"content\":${MonacoJson.encodeString(contentField.value)}," +
            "\"language\":${MonacoJson.encodeString(langField.value)}}"
    }

    companion object {
        /** Documents are capped: honest usage bound for the bridge payload. */
        const val MAX_DOCUMENT_CHARS: Int = 2 * 1024 * 1024
    }
}
