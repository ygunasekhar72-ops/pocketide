package com.pocketide.mobile.editor

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Editor controller (M2): loads the per-project document from
 * [MonacoEditorStore], relays save requests between the app toolbar and the
 * [MonacoHostEngine] content callback, and surfaces errors honestly.
 *
 * Save protocol: `requestSave()` arms a pending save (false when a save is
 * already in flight or there is nothing to save); the screen then calls
 * `MonacoHostEngine.pullContent()`; when the harness reports content,
 * [onContentLoaded] persists the document and clears the pending save.
 * Malformed/oversized payloads never persist (the engine refuses them).
 */
class EditorViewModel(
    private val store: MonacoEditorStore,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.NoProject)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var pendingSaveProject: String? = null

    /** Called by the screen when the engine receives any content event. */
    fun markEdited() {
        _uiState.update { state ->
            if (state is EditorUiState.Ready && !state.dirtyWithinSession) {
                state.copy(dirtyWithinSession = true)
            } else {
                state
            }
        }
    }

    fun openProject(projectName: String?) {
        if (projectName == null) {
            _uiState.value = EditorUiState.NoProject
            return
        }
        scope.launch {
            val (document, loadError) = try {
                (store.load(projectName) ?: MonacoDocument()) to null
            } catch (e: Exception) {
                // Corrupt/unreadable store: open empty, keep working, and
                // surface the corruption honestly.
                MonacoDocument() to ("stored document could not be loaded: ${e.message}")
            }
            _uiState.update { current ->
                if (current is EditorUiState.Ready &&
                    current.projectName == projectName &&
                    current.dirtyWithinSession
                ) {
                    // Do not clobber an in-flight buffer reload with a
                    // fresh store snapshot for the same project.
                    return@update current
                }
                EditorUiState.Ready(
                    projectName = projectName,
                    document = document,
                    loadFailed = loadError,
                    dirtyWithinSession = false,
                    saveMessage = null
                )
            }
        }
    }

    /** Arms a save; the screen then calls `MonacoHostEngine.pullContent()`. */
    fun requestSave(): Boolean {
        val current = _uiState.value as? EditorUiState.Ready ?: return false
        if (pendingSaveProject != null) return false
        pendingSaveProject = current.projectName
        return true
    }

    /** Handles the bridge `reportContent` round trip. */
    fun onContentLoaded(content: String, language: String) {
        val project = pendingSaveProject
        pendingSaveProject = null
        if (project == null) return
        scope.launch {
            val document = MonacoDocument(content, language)
            try {
                store.save(project, document)
                _uiState.update { state ->
                    if (state is EditorUiState.Ready && state.projectName == project) {
                        state.copy(saveMessage = "saved", dirtyWithinSession = false)
                    } else {
                        state
                    }
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    if (state is EditorUiState.Ready && state.projectName == project) {
                        state.copy(saveMessage = "save failed: ${e.message}")
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun onEngineError(detail: String) {
        _uiState.update { state ->
            when (state) {
                is EditorUiState.Ready -> state.copy(saveMessage = detail)
                else -> state
            }
        }
    }

    // Public on purpose: test harnesses drive the same cancellation path
    // without reflection; the lifecycle owner still calls it normally.
    public override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}

sealed interface EditorUiState {
    data object NoProject : EditorUiState

    data class Ready(
        val projectName: String,
        val document: MonacoDocument,
        /** Corrupt/unreadable persisted entries are surfaced, not hidden. */
        val loadFailed: String?,
        /** The editor buffer changed since the last successful save. */
        val dirtyWithinSession: Boolean,
        /** Transient save/engine message. */
        val saveMessage: String?
    ) : EditorUiState
}
