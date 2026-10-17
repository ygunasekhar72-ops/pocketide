package com.pocketide.mobile.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketide.mobile.home.data.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Reads/writes the real workspace via [ProjectRepository] on IO; the UI only
 * renders the resulting [HomeUiState].
 */
class HomeViewModel(private val repository: ProjectRepository) : ViewModel() {

    private val mutableState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    /** @param query client-side filter on project names (null/blank = all). */
    fun filtered(query: String): List<ProjectRepository.ProjectInfo> {
        val s = mutableState.value
        if (s !is HomeUiState.Ready) return emptyList()
        if (query.isBlank()) return s.projects
        return s.projects.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.listProjects() }.fold(
                onSuccess = { projects ->
                    mutableState.value = HomeUiState.Ready(
                        projects = projects,
                        workspacePath = repository.root.absolutePath
                    )
                },
                onFailure = { throwable ->
                    mutableState.value =
                        HomeUiState.Error(throwable.message ?: "Could not read workspace")
                }
            )
        }
    }

    fun createProject(
        rawName: String,
        onResult: (ProjectActionResult) -> Unit
    ) = viewModelScope.launch(Dispatchers.IO) {
        val result = when (val created = repository.createProject(rawName)) {
            is ProjectRepository.CreateResult.Created ->
                ProjectActionResult.Success("Created project \"${created.name}\"")
            ProjectRepository.CreateResult.AlreadyExists ->
                ProjectActionResult.Failure("A project with that name already exists")
            ProjectRepository.CreateResult.InvalidName ->
                ProjectActionResult.Failure(INVALID_NAME_HINT)
        }
        onResult(result)
        refreshBlocking()
    }

    fun deleteProject(
        rawName: String,
        onResult: (ProjectActionResult) -> Unit
    ) = viewModelScope.launch(Dispatchers.IO) {
        val result = when (repository.deleteProject(rawName)) {
            ProjectRepository.DeleteResult.Deleted ->
                ProjectActionResult.Success("Deleted \"$rawName\"")
            ProjectRepository.DeleteResult.NotFound ->
                ProjectActionResult.Failure("\"$rawName\" was not found")
            ProjectRepository.DeleteResult.InvalidName ->
                ProjectActionResult.Failure(INVALID_NAME_HINT)
        }
        onResult(result)
        refreshBlocking()
    }

    private fun refreshBlocking() {
        runCatching { repository.listProjects() }.fold(
            onSuccess = { projects ->
                mutableState.value = HomeUiState.Ready(
                    projects = projects,
                    workspacePath = repository.root.absolutePath
                )
            },
            onFailure = { throwable ->
                mutableState.value =
                    HomeUiState.Error(throwable.message ?: "Could not read workspace")
            }
        )
    }

    companion object {
        const val INVALID_NAME_HINT =
            "Names use letters, numbers, dot, dash or underscore (max 64), starting with a letter or number"
    }
}
