package com.merger.merger.ai

import com.intellij.openapi.options.Configurable
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class AiSettingsConfigurable : Configurable {
    private val settings = AiSettings.getInstance()
    private var baseUrl = settings.state.baseUrl
    private var model   = settings.state.model
    private var apiKeyUi: String = AiSecrets.get().orEmpty()

    override fun getDisplayName() = "AI Merge"
    override fun createComponent(): JComponent = panel {
        group("Provider") {
            row("Base URL:") { textField().bindText(::baseUrl) }
            row("Model:")    { textField().bindText(::model) }
            row("API Key:") {
                val pf = passwordField().component
                pf.text = apiKeyUi
                pf.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                    fun sync() { apiKeyUi = pf.text }
                    override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = sync()
                    override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = sync()
                    override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = sync()
                })
                comment("Stored in Password Safe (encrypted). Not written to project files.")
            }
            row {
                button("Clear saved key") { AiSecrets.save(null); apiKeyUi = "" }
            }
        }
    }

    override fun isModified(): Boolean =
        baseUrl != settings.state.baseUrl ||
        model   != settings.state.model   ||
        apiKeyUi != (AiSecrets.get().orEmpty())

    override fun apply() {
        AiSettings.getInstance().loadState(
            AiSettings.SettingsState(baseUrl.trim(), model.trim())
        )
        AiSecrets.save(apiKeyUi.trim().ifBlank { null })
    }
}
