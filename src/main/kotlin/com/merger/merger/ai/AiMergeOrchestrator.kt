package com.merger.merger.ai

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsNotifier
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository

class AiMergeOrchestrator(
    private val project: Project,
    private val log: (String) -> Unit
) {
    private val notifier = VcsNotifier.getInstance(project)
    private val git = Git.getInstance()

    private fun runGitText(repo: GitRepository, command: GitCommand, vararg args: String): Pair<Boolean, String> {
        val h = GitLineHandler(project, repo.root, command).apply { addParameters(*args) }
        val r = git.runCommand(h)
        val text = (r.output + r.errorOutput).joinToString("\n")
        return r.success() to text
    }

    private fun indexBlob(repo: GitRepository, stage: Int, relPath: String): String? {
        val (ok, txt) = runGitText(repo, GitCommand.SHOW, ":$stage:$relPath")
        return if (ok) txt else null
    }

    private fun listConflictedRelPaths(repo: GitRepository, log: (String) -> Unit): List<String> {
        val h = GitLineHandler(project, repo.root, GitCommand.DIFF).apply {
            addParameters("--name-only", "--diff-filter=U")
        }
        val r = git.runCommand(h)
        if (!r.success()) {
            log("   (AI) failed to list conflicts: ${(r.errorOutput + r.output).joinToString("\n")}")
            return emptyList()
        }
        return r.output.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun detectLanguageFromPath(rel: String): String? = when (rel.substringAfterLast('.', "" ).lowercase()) {
        "cs"      -> "C#"
        "csx"     -> "C# script"
        "json"    -> "JSON"
        "xml", "csproj", "props", "targets" -> "XML"
        "yml","yaml" -> "YAML"
        "md"      -> "Markdown"
        "ts","tsx"-> "TypeScript"
        "js"      -> "JavaScript"
        "java"    -> "Java"
        "kt","kts"-> "Kotlin"
        "sql"     -> "SQL"
        else      -> null
    }

    fun tryResolveWithAi(repo: GitRepository, engine: AiMergeEngine, prefs: AiMergePrefs, languagePrefs: String) {
        val conflictedRel = listConflictedRelPaths(repo, log)
        if (conflictedRel.isEmpty()) {
            log("   (AI) no conflicted files detected.")
            return
        }
        log("   (AI) attempting to resolve ${conflictedRel.size} conflicted file(s)…")

        for (rel in conflictedRel) {
            val perFileHint = when (languagePrefs) {
                "Auto" -> detectLanguageFromPath(rel) ?: "Plain text"
                else   -> languagePrefs
            }
            val vf = VfsUtilCore.findRelativeFile(rel, repo.root)
            if (vf == null) { log("      skip (cannot resolve): $rel"); continue }

            val base   = indexBlob(repo, 1, rel)
            val ours   = indexBlob(repo, 2, rel)
            val theirs = indexBlob(repo, 3, rel)
            if (base == null || ours == null || theirs == null) {
                log("      skip $rel (missing 3-way blobs)")
                continue
            }

            val prefsForFile = prefs.copy(languageHint = perFileHint)
            val ctx = ConflictContext(rel, base, ours, theirs)
            when (val result = engine.merge(ctx, prefsForFile)) {
                is AiMergeResult.Success -> {
                    val mergedRaw = result.mergedContent
                    val mergedClean = sanitizeAiMergedText(mergedRaw, exemplarForStyle = ours)
                    val ok = writeAndStage(repo, vf, mergedClean, prefs.stageOnSuccess)
                    if (ok) log("      $rel: ✅ auto-merged by AI")
                    else    log("      $rel: ❌ could not write/stage merged content")
                }
                is AiMergeResult.Failure -> log("      $rel: ❌ AI failed: ${result.reason}")
            }
        }
    }

    private fun writeAndStage(repo: GitRepository, vf: VirtualFile, text: String, stage: Boolean): Boolean {
        var writeOk = false
        WriteCommandAction.runWriteCommandAction(project, "AI Merge Apply", null, Runnable {
            VfsUtil.saveText(vf, text)

            // vf.setBinaryContent(text.toByteArray(StandardCharsets.UTF_8))

            writeOk = true
        })

        if (!writeOk) return false
        if (!stage) return true

        val rel = vf.path.removePrefix(repo.root.path.trimEnd('/') + "/")
        val (ok, msg) = runGitText(repo, GitCommand.ADD, rel)
        if (!ok) log("         git add failed: $msg")
        return ok
    }

    private fun sanitizeAiMergedText(raw: String, exemplarForStyle: String): String {
        var s = raw.trim()

        val fenced = Regex("^```[a-zA-Z0-9+_.-]*\\s*\\n([\\s\\S]*?)\\n```\\s*$", RegexOption.DOT_MATCHES_ALL)
        val m = fenced.find(s)
        if (m != null) s = m.groupValues[1]

        s = s.replace(Regex("^```[a-zA-Z0-9+_.-]*\\s*$", RegexOption.MULTILINE), "")
            .replace(Regex("^```\\s*$", RegexOption.MULTILINE), "")

        s = s.replace(Regex("[\\u200B\\u200C\\u200D\\u200E\\u200F\\u202A-\\u202E\\u2060\\uFEFF]"), "")

        val exemplarHasBom = exemplarForStyle.firstOrNull() == '\uFEFF'
        s = s.trimStart('\uFEFF')
        if (exemplarHasBom) s = "\uFEFF$s"

        val exemplarIsCRLF = exemplarForStyle.contains("\r\n")
        s = if (exemplarIsCRLF) {
            s.replace(Regex("(?<!\r)\n"), "\r\n")
        } else {
            s.replace("\r\n", "\n")
        }

        return s
    }
}
