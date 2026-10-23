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

        // While travelling the caret stretches ahead, toward the new letter, not behind.
        view.typeAt(TypingCaret(200f, 300f, 22f, floatArrayOf(120f, 300f, 160f, 300f)), "a")
        repeat(3) { frame() }
        val moving = Bitmap.createBitmap(411, 891, Bitmap.Config.ARGB_8888); view.draw(Canvas(moving))
        val at = view.cursor.x.toInt()
        assertTrue(android.graphics.Color.alpha(moving.getPixel(at + 4, 300)) > 200)
        assertTrue(android.graphics.Color.alpha(moving.getPixel(at - 5, 300)) < 120)
        frames(1000)
        // A space makes the caret hop into a drop and come back.
        view.typeAt(TypingCaret(160f, 300f, 22f, floatArrayOf(110f, 300f, 120f, 300f)), " ")
        frames(128); assertTrue(view.hop > .5f)
        frames(300); assertEquals(0f, view.hop)
        frames(600)

        val bitmap = Bitmap.createBitmap(411, 891, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        assertTrue(android.graphics.Color.alpha(bitmap.getPixel(160, 300)) > 150)
        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(160, 340)))

        view.stopTyping(); frames(600)
        assertEquals(0f, view.typing); assertFalse(view.typingActive)
        assertEquals(160f, view.cursor.x, 1f) // the sphere comes back where the caret was
        view.dispose(); app.graph.scope.cancel()
    }

    @Test fun particlesSitOnTheLettersAndFollowThemWhenTheLineScrolls() {
        val seeds = dev.luma.agent.ui.OrbPhysics(56).particles.map { CursorSeed(it.x, it.y, it.z, it.size, it.shade) }
        val field = CursorParticleField(seeds, 300f, 400f, 14f)
        fun letters(shift: Float) = FloatArray(40) { if (it % 2 == 0) 100f + it / 2 * 10f - shift else 400f }
        field.typing = 1f; field.lineHeight = 20f; field.letters = letters(0f)
        repeat(90) { field.advance(1 / 60f, 300f, 400f, 14f) }
        val xs = field.particles.map { it.x }
        // Spread along the text, not crowded at the caret (x = 300).
        assertTrue(xs.min() < 150f); assertTrue(xs.count { abs(it - 300f) < 20f } < field.particles.size / 3)
        assertTrue(field.particles.all { abs(it.y - 400f) < 20f })

        // A one-line field scrolls: the letters slide 40 px left while the caret stays. Particles follow smoothly.
        val before = field.particles.map { it.x }.average()
        field.letters = letters(40f)
        field.advance(1 / 60f, 300f, 400f, 14f)
        val firstStep = before - field.particles.map { it.x }.average()
        assertTrue(firstStep in 0.0..10.0)
        repeat(90) { field.advance(1 / 60f, 300f, 400f, 14f) }
        val moved = before - field.particles.map { it.x }.average()
        assertTrue("moved $moved", moved in 25.0..45.0)
    }
}
