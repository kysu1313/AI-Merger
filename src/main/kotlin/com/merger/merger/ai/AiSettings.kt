import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "AiMergeSettings", storages = [Storage("ai-merge-settings.xml")])
class AiSettings : PersistentStateComponent<AiSettings.SettingsState> {

    data class SettingsState(
        var baseUrl: String = "https://api.openai.com/v1",
        var model: String = "gpt-4o-mini"
    )

    private var state = SettingsState()

    override fun getState(): SettingsState = state
    override fun loadState(state: SettingsState) { this.state = state }

    companion object {
        fun getInstance(): AiSettings = service()
    }
}
