package com.merger.merger

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

object GitFacade {
    fun repositories(project: Project): List<GitRepository> =
        GitRepositoryManager.getInstance(project).repositories
}
