package com.pocketide.mobile.home

import com.pocketide.mobile.home.data.ProjectRepository

/** Immutable home screen state; rendering is a pure function of this. */
sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(
        val projects: List<ProjectRepository.ProjectInfo>,
        val workspacePath: String
    ) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

sealed interface ProjectActionResult {
    data class Success(val message: String) : ProjectActionResult
    data class Failure(val message: String) : ProjectActionResult
}
