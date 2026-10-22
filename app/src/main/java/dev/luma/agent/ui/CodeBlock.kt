package dev.luma.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A fenced code block: language, copy and «save as file» on top, coloured code that scrolls sideways. */
@Composable internal fun CodeBlock(segment: MarkdownSegment.Code, onLongPress: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < .45f
    val language = remember(segment.language) { SyntaxHighlighter.language(segment.language) }
    val palette = CodePalette.of(dark, colors.onSurface)
    val code by produceState(AnnotatedString(segment.code), segment.code, language, palette) {
        value = withContext(Dispatchers.Default) { SyntaxHighlighter.highlight(segment.code, language, palette) }
    }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    LaunchedEffect(note) { if (note != null) { delay(2200); note = null } }
    // octet-stream keeps the name as typed, so the file stays .kt, .py and so on.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            note = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(segment.code.toByteArray()) } ?: error("no stream"); "Файл сохранён" }
                    .getOrDefault("Не удалось сохранить файл")
            }
        }
    }
    val scale = LocalChatTextScale.current
    val shape = RoundedCornerShape(14.dp)
    Surface(shape = shape, color = colors.surfaceContainer, border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .55f)),
        modifier = Modifier.fillMaxWidth().testTag("code-block").then(
            if (onLongPress != null) Modifier.pointerInput(onLongPress) { detectTapGestures(onLongPress = { onLongPress() }) } else Modifier)) {
        Column {
            Row(Modifier.fillMaxWidth().background(colors.surfaceContainerHigh).heightIn(min = 38.dp).padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(note ?: language.label, Modifier.weight(1f).testTag("code-language"), style = MaterialTheme.typography.labelLarge,
                    color = if (note != null) colors.onSurfaceVariant else colors.primary)
                IconButton(onClick = { clipboard.setText(AnnotatedString(segment.code)); copied = true }, Modifier.size(36.dp).testTag("code-copy")) {
                    Crossfade(copied, animationSpec = tween(180), label = "code copied") { done ->
                        Icon(if (done) Icons.Outlined.Check else Icons.Outlined.ContentCopy, if (done) "Скопировано" else "Копировать код", Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { save.launch("code.${language.extension}") }, Modifier.size(36.dp).testTag("code-save")) {
                    Icon(Icons.Outlined.Download, "Скачать как .${language.extension}", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
            }
            Text(code, Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp).testTag("code-text"),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp * scale, lineHeight = 19.sp * scale, color = colors.onSurface), softWrap = false)
        }
    }
}
