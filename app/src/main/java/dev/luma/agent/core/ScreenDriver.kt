package dev.luma.agent.core

import org.json.JSONObject

data class ScreenResult(val json: JSONObject, val image: String? = null)

/** Screen reading and Accessibility actions; the Luma Accessibility service is the real one. */
interface ScreenDriver {
    suspend fun observe(withImage: Boolean): ScreenResult
    suspend fun execute(name: String, args: JSONObject): JSONObject
    /** Why a step must wait for the user (a blocked word, a permission screen), or null. */
    fun sensitive(name: String, args: JSONObject, blockedWords: String): String?
    fun actionLabel(name: String, args: JSONObject): String
}

/** Standard gestures from the screen size alone: no screenshot, no vision, no guessed coordinates. */
object Gestures {
    data class Stroke(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long)
    private val edgeFor = mapOf("top" to "down", "bottom" to "up", "left" to "right", "right" to "left")

    fun standard(direction: String, edge: String?, length: String?, width: Int, height: Int): Stroke {
        if (width <= 1 || height <= 1) throw ToolFailure(ToolStatus.ERROR, "Размер экрана неизвестен.")
        if (direction !in edgeFor.values) throw ToolFailure(ToolStatus.ERROR, "direction: up, down, left или right.")
        val from = edge?.takeIf { it.isNotEmpty() }
        if (from != null && edgeFor[from] != direction) throw ToolFailure(ToolStatus.ERROR, "От края $from палец может двигаться только ${edgeFor[from]}.")
        val fraction = when (length) { "short" -> .25f; "long" -> .7f; else -> .45f }
        val w = width.toFloat(); val h = height.toFloat(); val inset = 2f
        val vertical = direction == "up" || direction == "down"
        val sign = if (direction == "down" || direction == "right") 1f else -1f
        val span = (if (vertical) h else w) * fraction
        val start = when (from) {
            "top" -> inset; "left" -> inset; "bottom" -> h - inset; "right" -> w - inset
            else -> (if (vertical) h else w) / 2 - sign * span / 2
        }
        val end = start + sign * span
        fun clampX(v: Float) = v.coerceIn(0f, w - 1)
        fun clampY(v: Float) = v.coerceIn(0f, h - 1)
        return if (vertical) Stroke(clampX(w / 2), clampY(start), clampX(w / 2), clampY(end), if (from != null) 250 else 350)
        else Stroke(clampX(start), clampY(h / 2), clampX(end), clampY(h / 2), if (from != null) 250 else 350)
    }
}

/**
 * How the agent «types» into a field: growing prefixes of the final text, cut only between whole characters
 * (emoji and accented letters stay intact), at most [maxSteps] of them. The last one is always the full text,
 * so the field ends up exactly as one ACTION_SET_TEXT would leave it.
 */
object TypingPlan {
    fun prefixes(text: String, maxSteps: Int = 40): List<String> {
        if (text.isEmpty()) return listOf("")
        val iterator = java.text.BreakIterator.getCharacterInstance().apply { setText(text) }
        val ends = generateSequence(iterator.first()) { iterator.next().takeIf { it != java.text.BreakIterator.DONE } }.drop(1).toList()
        val steps = ends.size.coerceAtMost(maxSteps.coerceAtLeast(1))
        return (1..steps).map { i -> text.substring(0, ends[((ends.size * i + steps - 1) / steps - 1).coerceIn(0, ends.size - 1)]) }.distinct()
    }
    /** About 45 ms per character, but the whole text takes 0.3–1.6 s however long it is. */
    fun stepMillis(characters: Int, steps: Int): Long = ((characters * 45L).coerceIn(300, 1600) / steps.coerceAtLeast(1)).coerceAtLeast(16)
}
