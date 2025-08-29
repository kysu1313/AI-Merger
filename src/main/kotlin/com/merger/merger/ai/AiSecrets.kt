package com.merger.merger.ai

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

data class State(
    var baseUrl: String = "https://api.openai.com/v1",
    var model: String = "gpt-4o-mini"
)

object AiSecrets {
    private val ATTR = CredentialAttributes(
        generateServiceName("Multi-Repo Merger", "OpenAI API Key")
    )

    private val ps get() = PasswordSafe.instance

    fun get(): String? = ps.getPassword(ATTR)

    fun save(newKey: String?) {
        ps.setPassword(ATTR, newKey)
    }
}
