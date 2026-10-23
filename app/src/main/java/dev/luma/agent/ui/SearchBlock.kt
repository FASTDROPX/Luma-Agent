package dev.luma.agent.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.luma.agent.core.WebSearch

/** The line under the header: "Найдено 12 страниц · прочитано 5", or what is known of it. */
internal fun searchSummary(search: WebSearch): String = when {
    search.found == 0 && search.queries.isNotEmpty() -> "Поиск в интернете · ${search.queries.size} ${if (search.queries.size == 1) "запрос" else "запроса"}, страниц нет"
    search.found == 0 -> "Поиск в интернете"
    search.read == 0 || search.read == search.found -> "Поиск в интернете · найдено ${WebSearch.pages(search.found)}"
    else -> "Поиск в интернете · найдено ${WebSearch.pages(search.found)} · прочитано ${search.read}"
}

/**
 * DeepSeek-style web search above an answer: "Поиск в интернете · найдено 12 страниц · прочитано 5 ›". Opened, it lists
 * the search queries and the pages, read ones marked; a page opens in the browser.
 */
@Composable fun SearchBlock(search: WebSearch, modifier: Modifier = Modifier) {
    var open by rememberSaveable(search.hashCode()) { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val turn by animateFloatAsState(if (open) 90f else 0f, if (reduced) snap() else spring(dampingRatio = .8f, stiffness = 500f), label = "chevron")
    val uri = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.testTag("search-block")) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.TravelExplore, null, Modifier.size(16.dp), tint = muted)
            Text(searchSummary(search), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ChevronRight, if (open) "Скрыть подробности поиска" else "Показать подробности поиска", Modifier.size(18.dp).rotate(turn), tint = muted)
        }
        AnimatedVisibility(open, enter = if (reduced) EnterTransition.None else expandVertically(spring(stiffness = 600f)) + fadeIn(tween(220)),
            exit = if (reduced) ExitTransition.None else shrinkVertically(tween(200)) + fadeOut(tween(150))) {
            Column(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (search.queries.isNotEmpty()) {
                    Text("Запросы", style = MaterialTheme.typography.labelMedium, color = muted)
                    search.queries.forEach { query ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.Search, null, Modifier.size(14.dp), tint = muted)
                            Text(query, style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                    }
                }
                if (search.sources.isNotEmpty()) {
                    Text(if (search.read in 1 until search.found) "Страницы · прочитанные отмечены" else "Страницы", Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = muted)
                    search.sources.forEachIndexed { index, source ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { runCatching { uri.openUri(source.url) } }
                            .padding(vertical = 4.dp, horizontal = 2.dp).testTag("search-source-$index"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}", Modifier.widthIn(min = 18.dp), style = MaterialTheme.typography.labelSmall, color = muted)
                            Column(Modifier.weight(1f)) {
                                Text(source.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // Gemini links go through Google's redirect; its title is already the site's name.
                                val host = WebSearch.host(source.url)
                                if (!host.startsWith("vertexaisearch.")) Text(host, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (source.read && search.read < search.found) Icon(Icons.Outlined.DoneAll, "Прочитано", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
