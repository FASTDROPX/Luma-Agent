package dev.luma.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.luma.agent.AppGraph
import dev.luma.agent.core.WebSearch
import dev.luma.agent.core.WebSearchSettings
import dev.luma.agent.core.validateEndpoint

/** Settings → «Поиск в интернете»: how replies search, which search service Luma uses, its key or address, and a test search. */
@Composable fun SearchSettingsCards(graph: AppGraph) {
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    val check by graph.searchCheck.collectAsStateWithLifecycle()
    val search = settings.search
    fun change(update: (WebSearchSettings) -> WebSearchSettings) { graph.update { it.copy(search = update(it.search)) }; graph.searchCheck.value = dev.luma.agent.ConnectionCheck() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SoftCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Поиск в ответах", style = MaterialTheme.typography.bodyLarge)
                    Text("То же, что кнопка «Поиск» в чате", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Switch(settings.webSearch, { on -> graph.update { it.copy(webSearch = on) } }, Modifier.testTag("search-enabled"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Встроенный поиск провайдера", style = MaterialTheme.typography.bodyLarge)
                    Text("Gemini, Claude, OpenAI и OpenRouter ищут сами. Выключите, чтобы и они искали через Luma.", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Switch(search.mode == "auto", { on -> change { it.copy(mode = if (on) "auto" else "luma") } }, Modifier.testTag("search-native"))
            }
            val now = if (search.native(settings.provider)) "${settings.provider.label} ищет сам" else "через Luma: ${WebSearchSettings.label(search.engine)}"
            Text("Сейчас: $now.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        SoftCard {
            Text("Поисковик Luma", style = MaterialTheme.typography.titleMedium)
            Text("Для моделей без своего поиска: DeepSeek, локальных и любых других. Модель сама решает, что искать; Luma ищет и открывает страницы.",
                style = MaterialTheme.typography.bodySmall, color = muted)
            val options = listOf(
                Triple(WebSearchSettings.DUCKDUCKGO, "DuckDuckGo", "Без ключа и регистрации. Неофициальный способ: при частых запросах может включить проверку на бота."),
                Triple(WebSearchSettings.TAVILY, "Tavily", "Поиск для ИИ, сразу с выжимкой страниц. Около 1000 бесплатных запросов в месяц, ключ на tavily.com."),
                Triple(WebSearchSettings.BRAVE, "Brave Search", "Собственный индекс, хорошо ищет по-русски. Бесплатный лимит, ключ на brave.com/search/api."),
                Triple(WebSearchSettings.SEARXNG, "SearXNG", "Свой метапоиск на домашнем ПК: без ключей и лимитов. Из другого города — через Tailscale."))
            options.forEach { (id, title, note) ->
                Row(Modifier.fillMaxWidth().selectable(search.engine == id, role = Role.RadioButton) { change { it.copy(engine = id) } }
                    .padding(vertical = 6.dp).testTag("engine-$id"), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RadioButton(search.engine == id, null)
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(note, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
            when (search.engine) {
                WebSearchSettings.TAVILY -> SecretField("Ключ Tavily", search.tavilyKey, "tvly-…") { key -> change { it.copy(tavilyKey = key) } }
                WebSearchSettings.BRAVE -> SecretField("Ключ Brave Search", search.braveKey, "BSA…") { key -> change { it.copy(braveKey = key) } }
                WebSearchSettings.SEARXNG -> {
                    var address by remember(search.searxng) { mutableStateOf(search.searxng) }
                    var error by remember { mutableStateOf<String?>(null) }
                    OutlinedTextField(address, { address = it.trim(); error = null }, Modifier.fillMaxWidth().testTag("searxng-url"), label = { Text("Адрес SearXNG") },
                        placeholder = { Text("http://my-pc.tail1234.ts.net:8080") }, singleLine = true, isError = error != null,
                        supportingText = { Text(error ?: "В settings.yml SearXNG добавьте json в search → formats.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    if (address != search.searxng) Button(onClick = {
                        runCatching { validateEndpoint(address) }.onSuccess { url -> change { it.copy(searxng = url) } }.onFailure { error = it.message }
                    }) { Text("Сохранить адрес") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { graph.checkSearch() }, enabled = !check.busy, modifier = Modifier.testTag("search-check")) { Text("Проверить поиск") }
                if (check.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            check.reply?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            check.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        SoftCard {
            Text("Что видит интернет", style = MaterialTheme.typography.titleMedium)
            Text("Запросы уходят выбранному поисковику, страницы открываются прямо с телефона — сайты видят ваш IP. Luma не открывает страницы из домашней сети и с самого телефона, " +
                "а текст страниц передаёт модели как недоверенные данные. За ответ — до ${dev.luma.agent.core.SearchingChat.MAX_SEARCHES} поисков и ${dev.luma.agent.core.SearchingChat.MAX_PAGES} страниц.",
                style = MaterialTheme.typography.bodySmall, color = muted)
            if (!WebSearch.supported(settings.provider) && search.mode == "auto")
                Text("У ${settings.provider.label} нет своего поиска в API, поэтому он всегда ищет через Luma.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable private fun SecretField(label: String, saved: String, hint: String, save: (String) -> Unit) {
    var value by remember(saved) { mutableStateOf(saved) }
    OutlinedTextField(value, { value = it.trim() }, Modifier.fillMaxWidth().testTag("search-key"), label = { Text(label) }, placeholder = { Text(hint) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        supportingText = { Text(if (saved.isBlank()) "Ключ хранится зашифрованным на телефоне." else "Ключ сохранён.") })
    if (value != saved) Button(onClick = { save(value.removePrefix("Bearer ").trim().take(500)) }) { Text("Сохранить ключ") }
}
