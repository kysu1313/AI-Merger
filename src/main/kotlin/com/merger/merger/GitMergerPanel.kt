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
import com.merger.merger.ai.AiSecrets
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.JBSplitter
import com.jetbrains.rider.multiPlatform.xcAssets.models.colorComponentFromColor
import java.awt.Color

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
)

class GitMergerPanel(private val project: Project) {
    val component: JComponent get() = root
    private val root = JPanel(BorderLayout())

    private val repoListContainer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private var repos: List<GitRepository> = GitRepositoryManager.getInstance(project).repositories
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

    private val reposSubPanel: DialogPanel by lazy {
        panel {
            group("Repositories") {
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
                    scrollCell(repoListContainer).align(Align.FILL)
                }.resizableRow()
            }
        }
    }

    private val mainPanel: DialogPanel by lazy {
        panel {
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
                            getter = { options.languageModeProp },
                            setter = { options.languageModeProp = it ?: "Auto" }
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

            row {
                runButton = button("Run Sequential Merge") {
                    // capture UI → model
                    mainPanel.apply()
                    val validations = mainPanel.validateAll()
                    if (validations.isNotEmpty()) return@button
                    runMerges()
                }.applyToComponent {
                    putClientProperty("JButton.buttonType", "default")
                }.component
                runButton.isEnabled = false

                SwingUtilities.invokeLater {
                    SwingUtilities.getRootPane(runButton)?.defaultButton = runButton
                }

                button("AI Settings…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "AI Merge")
                }
            }

            group("Log") {
                row {
                    checkBox("Auto-scroll")
                        .applyToComponent { isSelected = true }
                        .onChanged { autoScroll = it.isSelected }
                    button("Clear") { clearLog() }
                }
                row {
                    scrollCell(logArea).align(Align.FILL)
                }.resizableRow()
            }
        }.apply { reset() }
    }

    private val splitter: JBSplitter by lazy {
        JBSplitter(true, 0.35f).apply {
            setHonorComponentsMinimumSize(false)
            firstComponent  = reposSubPanel.apply { minimumSize = java.awt.Dimension(0, 0) }
            secondComponent = mainPanel.apply    { minimumSize = java.awt.Dimension(0, 0) }
        }
    }

    init {
        root.add(splitter, BorderLayout.CENTER)
        rebuildRepoList()
    }

    private fun clearLog() { logArea.text = "" }

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
        if (::runButton.isInitialized) {
            runButton.isEnabled = repoChecks.values.any { it.isSelected }
        }
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
            if (autoScroll) logArea.caretPosition = logArea.document.length
        }
    }

    private fun runMerges() {
        val selected = repoChecks.filterValues { it.isSelected }.keys.toList()
        if (selected.isEmpty()) { log("No repositories selected."); return }

        log("Starting sequential merge of '${options.sourceBranch}' into ${selected.size} repos…")
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { MergeRunner(project, ::log).run(selected, options) },
            "Merging '${options.sourceBranch}' across repositories…",
            true,
            project
        )
    }
}
