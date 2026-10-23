package dev.luma.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumaApp::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypingCaretTest {
    @Test fun typingIsSplitIntoWholeCharactersAndEndsWithTheFullText() {
        val emoji = "Привет 👋🏽 мир"
        val steps = TypingPlan.prefixes(emoji)
        assertEquals(emoji, steps.last())
        assertTrue(steps.all { emoji.startsWith(it) })
        assertFalse(steps.any { it.endsWith("\uD83D") || it.endsWith("👋") }) // never splits the emoji or its skin tone
        val long = "а".repeat(500)
        val few = TypingPlan.prefixes(long)
        assertEquals(40, few.size); assertEquals(long, few.last())
        assertEquals(listOf(""), TypingPlan.prefixes(""))
        // Short and long texts both take a sensible total time.
        assertEquals(300L, TypingPlan.stepMillis(2, 1))
        assertEquals(40L, TypingPlan.stepMillis(500, 40))
    }

    @Test fun sphereMorphsIntoALiquidCaretThatStretchesAndThickensWhileMoving() {
        val app = ApplicationProvider.getApplicationContext<LumaApp>()
        val view = AgentCanvas(app); view.configure(RunState(active = true), AppSettings())
        view.layout(0, 0, 411, 891)
        // Frames run on Robolectric's clock, the same one onDraw reads.
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        fun frame() { looper.idleFor(java.time.Duration.ofMillis(16)); view.advanceVisualFrame(android.os.SystemClock.uptimeMillis()) }
        fun frames(ms: Int) = repeat(ms / 16) { frame() }
        val now = android.os.SystemClock.uptimeMillis()
        view.advanceVisualFrame(now); frames(1200)
        view.typeAt(TypingCaret(120f, 300f, 22f, floatArrayOf(100f, 300f, 110f, 300f)))
        frames(200); assertTrue(view.typing in .2f..<1f)
        frames(400); assertEquals(1f, view.typing)
        assertEquals(120f, view.cursor.x, 1f); assertEquals(300f, view.cursor.y, 1f)
        frames(800); assertTrue(view.caretFlow < .05f)

        // The next letter: the caret glides there without a jump and without overshooting, and thickens a little.
        view.typeAt(TypingCaret(160f, 300f, 22f, floatArrayOf(110f, 300f, 120f, 300f)))
        val path = mutableListOf<Float>(); var flow = 0f
        repeat(20) { frame(); path += view.cursor.x; flow = maxOf(flow, view.caretFlow) }
        assertTrue(path.first() < 140f)
        assertTrue(path.zipWithNext().all { (a, b) -> b >= a - .01f && b <= 160.01f })
        assertEquals(160f, path.last(), 1.5f)
        assertTrue(flow > .1f)
        frames(1000); assertTrue(view.caretFlow < .05f)

        val restHeight = run { view.draw(Canvas(Bitmap.createBitmap(411, 891, Bitmap.Config.ARGB_8888))); view.caretBox.height() }
        val restWidth = view.caretBox.width()
        assertEquals(22f, restHeight, .5f)

        // While travelling the caret stretches ahead, toward the new letter, and only in width: its height stays.
        view.typeAt(TypingCaret(200f, 300f, 22f))
        repeat(3) { frame() }
        val moving = Bitmap.createBitmap(411, 891, Bitmap.Config.ARGB_8888); view.draw(Canvas(moving))
        val at = view.cursor.x.toInt()
        assertTrue(android.graphics.Color.alpha(moving.getPixel(at + 4, 300)) > 200)
        assertTrue(android.graphics.Color.alpha(moving.getPixel(at - 5, 300)) < 120)
        assertEquals(restHeight, view.caretBox.height(), .01f)
        assertTrue(view.caretBox.width() > restWidth + 3f)
        view.typeAt(TypingCaret(160f, 300f, 22f)); frames(1000)

        val bitmap = Bitmap.createBitmap(411, 891, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        assertTrue(android.graphics.Color.alpha(bitmap.getPixel(160, 300)) > 150)
        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(160, 340)))

        view.stopTyping(); frames(600)
        assertEquals(0f, view.typing); assertFalse(view.typingActive)
        assertEquals(160f, view.cursor.x, 1f) // the sphere comes back where the caret was
        view.dispose(); app.graph.scope.cancel()
    }

    @Test fun particlesChaseTheCaretAndTrailBehindItWhileTypingAlsoInAScrollingField() {
        val app = ApplicationProvider.getApplicationContext<LumaApp>()
        val view = AgentCanvas(app); view.configure(RunState(active = true), AppSettings())
        view.layout(0, 0, 411, 891)
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        fun frame() { looper.idleFor(java.time.Duration.ofMillis(16)); view.advanceVisualFrame(android.os.SystemClock.uptimeMillis()) }
        fun frames(ms: Int) = repeat(ms / 16) { frame() }
        view.advanceVisualFrame(android.os.SystemClock.uptimeMillis()); frames(1200)
        // Letters 9 px wide from x = 40; a one-line field shows up to x = 370 and then scrolls.
        fun caret(n: Int, scroll: Float = 0f): TypingCaret {
            val count = minOf(n, 24)
            val letters = FloatArray(count * 2) { val i = n - count + it / 2; if (it % 2 == 0) 40f + 9f * i + 4.5f - scroll else 300f }
            return TypingCaret(40f + 9f * n - scroll + 1.5f, 300f, 22f, letters, n)
        }
        fun xs() = view.particleField!!.particles.map { it.x }
        fun spread(caretX: Float) = xs().map { abs(it - caretX) }
        view.typeAt(caret(0)); frames(500)

        // A normal field: the caret moves on; the particles chase it and trail behind, never spread over the text.
        var n = 0
        repeat(30) { n++; view.typeAt(caret(n)); frames(48) }
        val caretX = view.cursor.x
        assertTrue(xs().average() < caretX - 4f)
        assertTrue(xs().min() > caretX - 150f)
        frames(900)
        assertTrue(spread(view.cursor.x).average() < 22.0) // typing stopped: they catch up with the caret

        // A full one-line field: the caret stays at the right edge while the text scrolls left under it. The particles
        // are carried back with the letters and keep chasing, so they trail behind instead of piling up on the caret.
        val edge = caret(n).x
        var scroll = 0f
        val trailing = mutableListOf<Double>()
        repeat(30) { n++; scroll += 9f; view.typeAt(caret(n, scroll)); frames(48); trailing += edge - xs().average() }
        assertEquals(edge, view.cursor.x, 1f)
        assertTrue("trail ${trailing.takeLast(10)}", trailing.takeLast(10).all { it > 6.0 })
        assertTrue(xs().min() > edge - 160f)
        frames(900)
        assertTrue(spread(edge).average() < 22.0)
        view.dispose(); app.graph.scope.cancel()
    }
}
