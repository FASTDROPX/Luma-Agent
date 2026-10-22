package dev.luma.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

internal sealed interface MarkdownSegment {
    data class Text(val markdown: String) : MarkdownSegment
    /** [align]: -1 left, 0 centre, 1 right. */
    data class Table(val header: List<String>, val align: List<Int>, val rows: List<List<String>>) : MarkdownSegment
}

private val tableSeparator = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

private fun tableCells(line: String): List<String> {
    var row = line.trim()
    if (row.startsWith("|")) row = row.drop(1)
    if (row.endsWith("|") && !row.endsWith("\\|")) row = row.dropLast(1)
    return row.split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }
}

/** Splits GFM tables out of the reply, never inside fenced code; everything else stays Markdown text. */
internal fun splitMarkdownTables(source: String): List<MarkdownSegment> {
    val lines = source.lines()
    val result = mutableListOf<MarkdownSegment>()
    val text = StringBuilder()
    var fence: String? = null
    var i = 0
    fun flush() { if (text.isNotBlank()) result += MarkdownSegment.Text(text.toString().trim('\n')); text.clear() }
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        if (fence == null && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) fence = trimmed.take(3)
        else if (fence != null && trimmed.startsWith(fence)) fence = null
        else if (fence == null && line.contains('|') && i + 1 < lines.size && tableSeparator.matches(lines[i + 1])) {
            val header = tableCells(line)
            val spec = tableCells(lines[i + 1])
            if (header.size == spec.size && header.isNotEmpty()) {
                val align = spec.map { val l = it.startsWith(":"); val r = it.endsWith(":"); if (l && r) 0 else if (r) 1 else -1 }
                val rows = mutableListOf<List<String>>()
                var j = i + 2
                while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) {
                    val cells = tableCells(lines[j]); rows += List(header.size) { cells.getOrElse(it) { "" } }; j++
                }
                flush(); result += MarkdownSegment.Table(header, align, rows); i = j; continue
            }
        }
        text.append(line).append('\n'); i++
    }
    flush()
    return result.ifEmpty { listOf(MarkdownSegment.Text(source)) }
}

/** **bold**, *italic*, `code` and [links](url) inside a table cell. */
internal fun cellText(raw: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    val token = Regex("""\*\*(.+?)\*\*|__(.+?)__|\*(.+?)\*|`(.+?)`|\[(.+?)]\((.+?)\)""")
    var last = 0
    for (m in token.findAll(raw)) {
        append(raw.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[1] + g[2]) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(g[4]) }
            else -> append(g[5])
        }
        last = m.range.last + 1
    }
    append(raw.substring(last))
}

/** Columns as wide as their content (up to 260 dp, then wrapped); a wide table scrolls sideways. */
@Composable internal fun MarkdownTable(table: MarkdownSegment.Table) {
    val colors = MaterialTheme.colorScheme
    val line = colors.outlineVariant
    val headerFill = colors.surfaceContainerHigh
    val columns = table.header.size
    val maxCell = with(LocalDensity.current) { 260.dp.roundToPx() }
    val cells = listOf(table.header) + table.rows
    val shape = RoundedCornerShape(12.dp)
    SelectionContainer {
        Box(Modifier.horizontalScroll(rememberScrollState()).testTag("markdown-table").clip(shape).border(1.dp, line, shape)) {
            Layout(content = {
                cells.forEachIndexed { r, row ->
                    row.forEachIndexed { c, value ->
                        Box(Modifier.background(if (r == 0) headerFill else Color.Transparent).drawBehind {
                            val w = 1.dp.toPx()
                            if (c < columns - 1) drawLine(line, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                            if (r < cells.size - 1) drawLine(line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                        }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(cellText(value, colors.surfaceContainerHighest), Modifier.fillMaxWidth(),
                                style = if (r == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface,
                                textAlign = when (table.align.getOrElse(c) { -1 }) { 0 -> TextAlign.Center; 1 -> TextAlign.End; else -> TextAlign.Start })
                        }
                    }
                }
            }) { measurables, _ ->
                val widths = IntArray(columns) { c -> cells.indices.maxOf { r -> measurables[r * columns + c].maxIntrinsicWidth(Constraints.Infinity) }.coerceAtMost(maxCell) }
                val heights = IntArray(cells.size) { r -> (0 until columns).maxOf { c -> measurables[r * columns + c].minIntrinsicHeight(widths[c]) } }
                // Every cell of a row takes the row height, so grid lines stay continuous.
                val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i % columns], heights[i / columns])) }
                layout(widths.sum(), heights.sum()) {
                    var y = 0
                    for (r in cells.indices) {
                        var x = 0
                        for (c in 0 until columns) { placeables[r * columns + c].place(x, y); x += widths[c] }
                        y += heights[r]
                    }
                }
            }
        }
    }
}
