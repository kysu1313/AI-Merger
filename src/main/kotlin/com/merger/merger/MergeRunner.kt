// src/main/kotlin/com/merger/merger/MergeRunner.kt
package com.merger.merger

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.util.NlsContexts
import git4idea.GitLocalBranch
import git4idea.branch.GitBrancher
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.changes.GitChangeUtils
import java.nio.charset.StandardCharsets
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.vcs.AbstractVcsHelper

import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vcs.merge.MergeDialogCustomizer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.merger.merger.ai.AiMergeOrchestrator
import com.merger.merger.ai.AiMergePrefs
import com.merger.merger.ai.AiSecrets
import com.merger.merger.ai.OpenAICompatibleMergeEngine
import git4idea.GitVcs


class MergeRunner(
    private val project: Project,
    private val log: (String) -> Unit
) {
    private val notifications = NotificationGroupManager.getInstance()
        .getNotificationGroup("multi.repo.merge.notifications")
    private val git = Git.getInstance()
    private val brancher = GitBrancher.getInstance(project)



    private fun isRepoClean(repo: GitRepository, includeUntracked: Boolean = false): Boolean {
        val handler = GitLineHandler(repo.project, repo.root, GitCommand.STATUS).apply {
            addParameters("--porcelain")
            if (!includeUntracked) addParameters("--untracked-files=no") // or "-uno"
        }
        val result = Git.getInstance().runCommand(handler)
        val lines = (result.output + result.errorOutput).map { it.trim() }
        return lines.all { it.isBlank() }
    }


    fun run(repos: List<GitRepository>, opts: MergeOptions) {
        val indicator: ProgressIndicator = ProgressManager.getInstance().progressIndicator
        indicator.isIndeterminate = false

        for (repo in repos) {
            val rootName = repo.root.name
            val current = repo.currentBranchName ?: repo.currentRevision ?: "(detached)"
            log("▶ [$rootName] current=$current")

            // 1) Fetch
            if (opts.fetchBeforeMerge) {
                log("   fetching…")
                val fetchOk = runHandler(repo, GitCommand.FETCH) { addParameters("--all", "--prune") }
                if (!fetchOk) {
                    log("   ❌ fetch failed."); if (opts.stopOnConflict) return else continue
                }
            }

            // 2) Dirty handling
            var stashed = false
            val dirty = !isRepoClean(repo, includeUntracked = false)
            if (dirty) {
                when {
                    opts.stashIfDirty -> {
                        log("   repo dirty → auto-stashing…")
                        stashed = runHandler(repo, GitCommand.STASH) {
                            addParameters("push", "-u", "-m", "multi-repo-merger-auto-stash")
                        }
                        if (!stashed && !opts.allowDirtyMerge) {
                            log("   ❌ stash failed; skipping."); if (opts.stopOnConflict) return else continue
                        }
                    }
                    opts.allowDirtyMerge -> log("   repo dirty → proceeding without stashing")
                    else -> { log("   ⚠ repo has local changes; skipping."); continue }
                }
            }

            // 3) Resolve merge ref
            val userBranch = opts.sourceBranch.trim()
            val mergeRef = resolveMergeRef(repo, userBranch)
            if (mergeRef == null) {
                log("   ❌ Branch '$userBranch' not found locally or on origin; skipping.")
                if (opts.stopOnConflict) return else continue
            }

            // 4) Merge
            log("   merging '$mergeRef' into '$current'…")
            val mergeOk = runHandler(repo, GitCommand.MERGE) {
                if (opts.noFastForward) addParameters("--no-ff")
                if (opts.squash) addParameters("--squash")
                addParameters(mergeRef)
            }
            repo.update()

            // AI auto resolution
            if (!mergeOk) {
                val remaining = listConflictedRelPaths(repo, log)
                if (remaining.isEmpty()) {
                    log("   ❌ merge failed without conflicts (likely bad ref or non-mergeable state).")
                    if (opts.stopOnConflict) return else continue
                }

                log("   ❌ merge has conflicts → trying AI auto-merge…")
                val s = AiSettings.getInstance().state
                val key = AiSecrets.get()
                    ?: ""
                if (key.isEmpty() && s.requireApiKey) {
                    notifications.createNotification(
                        "AI Merge",
                        "No API key found. Please set one in the plugin settings.",
                        NotificationType.ERROR
                    ).notify(project)
                    return
                }
                val eng = OpenAICompatibleMergeEngine(s.baseUrl, key, s.model ,s.requireApiKey)
                AiMergeOrchestrator(project, log).tryResolveWithAi(
                    repo, eng, AiMergePrefs(maxTokens = 8000, temperature = 0.1, stageOnSuccess = true), opts.languageModeProp
                )
                repo.update()

                val remaining2 = listConflictedRelPaths(repo, log)
                if (remaining2.isNotEmpty()) {
                    log("   conflicts remain → opening Resolve Conflicts UI…")
                    showResolveConflictsDialog(repo, remaining2)
                    if (opts.stopOnConflict) return
                }
            }

            // 6) Pop stash
            if (stashed) {
                log("   restoring stashed changes…")
                val popped = runHandler(repo, GitCommand.STASH) { addParameters("pop") }
                if (!popped) {
                    log("   ❌ stash pop had conflicts or failed; resolve manually.")
                    if (opts.stopOnConflict) return
                } else {
                    log("   ✅ stash restored.")
                }
            }

            // 7) Optional push
            if (opts.pushAfterMerge) {
                log("   pushing…")
                val pushed = runHandler(repo, GitCommand.PUSH) { }
                if (!pushed) {
                    log("   ❌ push failed."); if (opts.stopOnConflict) return
                } else log("   ✅ pushed.")
            }
        }

        log("All done.")
    }

    private fun listConflictedRelPaths(repo: GitRepository, log: (String) -> Unit): List<String> {
        val h = GitLineHandler(repo.project, repo.root, GitCommand.DIFF).apply {
            addParameters("--name-only", "--diff-filter=U")
        }
        val r = Git.getInstance().runCommand(h)
        if (!r.success()) {
            log("   (AI) failed to list conflicts: ${(r.errorOutput + r.output).joinToString("\n")}")
            return emptyList()
        }
        return r.output.map { it.trim() }.filter { it.isNotEmpty() }
    }


    private fun conflictedVFiles(repo: GitRepository, rels: List<String>): List<VirtualFile> =
        rels.mapNotNull { rel -> VfsUtilCore.findRelativeFile(rel, repo.root) }


    private fun showResolveConflictsDialog(repo: GitRepository, conflictedRelPaths: List<String>) {
        val files: List<VirtualFile> = conflictedRelPaths
            .mapNotNull { rel -> VfsUtilCore.findRelativeFile(rel, repo.root) }

        if (files.isEmpty()) return

        val gitVcs = GitVcs.getInstance(repo.project)
        val mergeProvider = gitVcs.mergeProvider ?: return

        val customizer = object : MergeDialogCustomizer() {
            override fun getMultipleFileMergeDescription(files: MutableCollection<VirtualFile>): @NlsContexts.Label String =
                "Resolve Git conflicts"
            override fun getLeftPanelTitle(file: VirtualFile): String = "Current branch"
            override fun getRightPanelTitle(
                file: VirtualFile,
                revisionNumber: VcsRevisionNumber?
            ): @NlsContexts.Label String = "Merging branch"
        }

        ApplicationManager.getApplication().invokeLater {
            AbstractVcsHelper.getInstance(repo.project)
                .showMergeDialog(files.toMutableList(), mergeProvider, customizer)
        }
    }

    private fun refExists(repo: GitRepository, ref: String): Boolean {
        val h = GitLineHandler(project, repo.root, GitCommand.REV_PARSE).apply {
            addParameters("--verify", "--quiet", ref)
        }
        return git.runCommand(h).success()
    }

    private fun resolveMergeRef(repo: GitRepository, userInput: String): String? {
        val b = userInput.trim()
        if (b.isEmpty()) return null
        return when {
            refExists(repo, b) -> b
            refExists(repo, "origin/$b") -> "origin/$b"
            else -> null
        }
    }

    private inline fun runHandler(
        repo: GitRepository,
        command: GitCommand,
        block: GitLineHandler.() -> Unit = {}
    ): Boolean {
        val handler = GitLineHandler(project, repo.root, command)
        handler.block()
        val result = git.runCommand(handler)
        if (!result.success()) {
            val out = result.errorOutputAsJoinedString.ifBlank { result.outputAsJoinedString }
            log("      git ${handler.printableCommandLine()} → $out")
        } else {
            log("      git ${handler.printableCommandLine()} ✓")
        }
        return result.success()
    }
}
