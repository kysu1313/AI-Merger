package com.merger.merger

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.layout.ComponentPredicate
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import com.intellij.openapi.observable.properties.PropertyGraph
import com.merger.merger.ai.AiSecrets
import com.intellij.openapi.options.ShowSettingsUtil

data class MergeOptions(
    var sourceBranch: String = "",
    var fetchBeforeMerge: Boolean = true,
    var stashIfDirty: Boolean = true,
    var allowDirtyMerge: Boolean = false,
    var noFastForward: Boolean = false,
    var squash: Boolean = false,
    var pushAfterMerge: Boolean = false,
    var stopOnConflict: Boolean = true,
    var languageModeProp: String = "Auto"
){

}

class GitMergerPanel(private val project: Project) {
    private val pg = PropertyGraph()
    private val languageModeProp = pg.property("Auto")
    val component: JComponent get() = root
    private val root = JPanel(BorderLayout())
    private val repoListContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private var repos: List<GitRepository> =
        GitRepositoryManager.getInstance(project).repositories
    private lateinit var runButton: JButton
    private var apiKeyUi: String = AiSecrets.get().orEmpty()

    private val repoChecks = mutableMapOf<GitRepository, JBCheckBox>()
    private val options = MergeOptions()
    private var autoScroll: Boolean = true

    private val logArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        rows = 12
        background = JBColor.PanelBackground
    }

    private val panel: DialogPanel = panel {
        group("Repositories (${repos.size})") {
            row {
                button("Refresh") { rebuildRepoList() }
                button("Select All") {
                    repoChecks.values.forEach { it.isSelected = true }
                    updateRunEnabled()
                }
                button("Select None") {
                    repoChecks.values.forEach { it.isSelected = false }
                    updateRunEnabled()
                }
            }
            row {
                val repoScroll = JScrollPane(repoListContainer).apply {
                    preferredSize = Dimension(0, 70)
                    minimumSize = Dimension(0, 50)
                }
                cell(repoScroll).align(Align.FILL)
            }.resizableRow()
        }

        group("Merge Options") {
            row("Source branch:") {
                textField()
                    .bindText(options::sourceBranch)
                    .validationOnInput { if (it.text.isNullOrBlank()) error("Required") else null }
                    .focused()
            }
            row("Output language:") {
                comboBox(listOf("Auto", "C#", "JSON", "XML", "Plain text"))
                    .bindItem(
                        getter = { options::languageModeProp.get() },
                        setter = { options::languageModeProp }
                    )
                    .comment("Auto uses file extension; override if needed")
            }
            row {
                checkBox("Fetch before merge").bindSelected(options::fetchBeforeMerge)
                checkBox("Auto-stash if dirty").bindSelected(options::stashIfDirty)
            }
            row {
                checkBox("Allow dirty merge (attempt without stashing)").bindSelected(options::allowDirtyMerge)
            }
            row {
                checkBox("Use --no-ff").bindSelected(options::noFastForward)
                checkBox("Use --squash").bindSelected(options::squash)
            }
            row {
                checkBox("Push after merge").bindSelected(options::pushAfterMerge)
                checkBox("Stop on first conflict").bindSelected(options::stopOnConflict)
            }
        }

        separator()

//        row("API Key:")  {
//            passwordField()
//                .bindText(::apiKeyUi)
//                .comment("Stored in Password Safe (encrypted). Not written to project files.")
//        }
//        row {
//            button("Clear saved key") {
//                AiSecrets.save(null)
//                apiKeyUi = ""
//            }
//        }
        row {
            runButton = button("Run Sequential Merge") {
                val validations = panel.validateAll()
                if (validations.isNotEmpty()) return@button
                panel.apply()
                runMerges()
            }.component
            runButton.isEnabled = false
        }


        group("Log") {
            row {
                button("AI Settings…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "AI Merge")
                    // or: ShowSettingsUtil.getInstance().showSettingsDialog(project, AiSettingsConfigurable::class.java)
                }
            }

            row {
                checkBox("Auto-scroll")
                    .applyToComponent { isSelected = true }
                    .onChanged { autoScroll = it.isSelected }

                button("Clear") {
                    clearLog()
                }
//                 button("Copy") { copyLogToClipboard() }
            }
            row {
                scrollCell(logArea)
                    .align(Align.FILL)
            }.resizableRow()
        }
    }.apply { reset() }

    private fun clearLog() {
        logArea.text = ""
    }

    init {
        root.add(panel, BorderLayout.CENTER)
    }

    private fun rebuildRepoList() {
        repos = GitRepositoryManager.getInstance(project).repositories
        repoChecks.clear()
        repoListContainer.removeAll()

        for (r in repos) {
            val cb = JBCheckBox("${r.root.name} — ${r.root.presentableUrl}", true)
            cb.addChangeListener { updateRunEnabled() }
            repoChecks[r] = cb

            val line = JPanel(BorderLayout())
            line.add(cb, BorderLayout.WEST)
            repoListContainer.add(line)
        }

        repoListContainer.revalidate()
        repoListContainer.repaint()
        updateRunEnabled()
        log("Refreshed repositories: ${repos.size} found.")
    }

    private fun updateRunEnabled() {
        runButton.isEnabled = repoChecks.values.any { it.isSelected }
    }

    private fun reposChosen(): ComponentPredicate = object : ComponentPredicate() {
        override fun addListener(listener: (Boolean) -> Unit) {
            repoChecks.values.forEach { it.addChangeListener { listener(invoke()) } }
        }
        override fun invoke(): Boolean = repoChecks.values.any { it.isSelected }
    }

    private fun log(line: String) {
        invokeLater {
            logArea.append(line + "\n")
            if (autoScroll) {
                logArea.caretPosition = logArea.document.length
            }
        }
    }

    private fun runMerges() {
        val selected = repoChecks.filterValues { it.isSelected }.keys.toList()
        if (selected.isEmpty()) {
            log("No repositories selected.")
            return
        }

        log("Starting sequential merge of '${options.sourceBranch}' into ${selected.size} repos…")
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                MergeRunner(project, ::log).run(selected, options)
            },
            "Merging '${options.sourceBranch}' across repositories…",
            true,
            project
        )
    }
}
