package dev.luma.agent

import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Intent
import dev.luma.agent.core.RunState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import dev.luma.agent.core.formatElapsed
import kotlin.math.abs

internal class FloatingPanel(private val service: AgentAccessibilityService, private val graph: AppGraph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var expanded = false
    private var hidden = false
    private var current = RunState()
    private var enabled = true
    private var header: TextView? = null
    private val params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, android.graphics.PixelFormat.TRANSLUCENT).apply {
        // Same row and height as the Stop button on the right (AgentVisualOverlay).
        gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(52)
    }
    fun bind() {
        scope.launch { combine(graph.agent.state, graph.vault.state) { run, settings -> run to settings.panel }.collect { (run, panel) -> current = run; enabled = panel; if (run.approval != null || run.retryPending) expanded = true; render() } }
        // The expanded header shows the run time; refresh it without rebuilding the window.
        scope.launch { while (true) { delay(1000); if (expanded) header?.text = headerText() } }
    }
    private fun headerText(): String {
        val time = if (current.startedAt > 0) " · " + formatElapsed(android.os.SystemClock.elapsedRealtime() - current.startedAt) else ""
        return if (current.paused) "Luma · пауза$time" else "Luma · шаг ${current.step}$time"
    }
    fun temporarilyHide(value: Boolean) { hidden = value; view?.visibility = if (value) View.INVISIBLE else View.VISIBLE }
    private fun render() {
        if (!current.active || !enabled) { remove(); expanded = false; return }
        // Same look as the app: dark surface, thin light border, gradient primary button. Collapsed it is a small
        // «✦ N» chip in the row of the Stop pill; expanded it is a compact card.
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; elevation = dp(6).toFloat()
            background = OverlayStyle.surface(service, if (expanded) OverlayStyle.CARD else OverlayStyle.SURFACE, if (expanded) 20f else 21f)
            if (expanded) setPadding(dp(14), dp(6), dp(14), dp(10))
            contentDescription = "Luma: ${current.stage}"
        }
        val header = TextView(service).apply {
            // Collapsed, the panel still shows progress: "✦ 3" is the current step.
            text = if (!expanded) (if (current.step > 0) "✦ ${current.step}" else "✦") else headerText()
            gravity = if (expanded) Gravity.CENTER_VERTICAL else Gravity.CENTER
            if (expanded) { textSize = 12.5f; setTextColor(OverlayStyle.MUTED) } else { textSize = 15f; setTextColor(OverlayStyle.ACCENT); setPadding(dp(14), 0, dp(14), 0) }
            setTypeface(Typeface.DEFAULT, Typeface.BOLD); minHeight = dp(if (expanded) 34 else 42); minWidth = dp(if (expanded) 0 else 52)
            contentDescription = if (expanded) "Свернуть панель Luma" else "Развернуть панель Luma, шаг ${current.step}"
        }
        this.header = header
        root.addView(header, LinearLayout.LayoutParams(if (expanded) -1 else -2, dp(if (expanded) 34 else 42)))
        header.setOnClickListener { expanded = !expanded; render() }
        var startX = 0f; var startY = 0f; var originalX = 0; var originalY = 0; var dragged = false
        header.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; originalX = params.x; originalY = params.y; dragged = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - startX) + abs(event.rawY - startY) > dp(8)) dragged = true
                    if (dragged) {
                        params.x = (originalX + event.rawX - startX).toInt().coerceIn(0, (service.resources.displayMetrics.widthPixels - (view?.width ?: 0)).coerceAtLeast(0))
                        params.y = (originalY + event.rawY - startY).toInt().coerceIn(0, (service.resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(0))
                        view?.let { runCatching { manager.updateViewLayout(it, params) } }
                    }; true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) target.performClick(); true }
                else -> true
            }
        }
        if (expanded) {
            root.addView(TextView(service).apply { text = current.approval?.title ?: current.stage; textSize = 14f; setTextColor(OverlayStyle.TEXT); setLineSpacing(0f, 1.1f)
                maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            current.approval?.let { approval -> root.addView(TextView(service).apply { text = approval.details; textSize = 12f; setTextColor(OverlayStyle.MUTED); maxLines = 6
                ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(10), dp(8), dp(10), dp(8)); background = OverlayStyle.surface(service, 0x14FFFFFF, 12f, stroke = false) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
            val actions = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
            val primary = when {
                current.approval != null -> "Разрешить" to { graph.agent.decide(true) }
                current.retryPending -> "Повторить" to { graph.agent.retryRequest() }
                else -> (if (current.paused) "Продолжить" else "Пауза") to { graph.agent.togglePause() }
            }
            actions.addView(button(primary.first, true, action = primary.second), LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(8) })
            actions.addView(button("Стоп", false) { graph.agent.stop() }, LinearLayout.LayoutParams(-2, dp(36)))
            root.addView(actions, LinearLayout.LayoutParams(-1, -2))
            root.addView(button("Открыть чат", false, small = true) { service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) },
                LinearLayout.LayoutParams(-1, dp(30)).apply { topMargin = dp(4) })
        }
        root.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        remove(); view = root
        params.width = if (expanded) dp(248) else -2; params.height = -2
        params.x = params.x.coerceIn(0, (service.resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
        runCatching { manager.addView(root, params) }.onFailure { view = null }
    }
    private fun button(label: String, primary: Boolean, small: Boolean = false, action: () -> Unit) = TextView(service).apply {
        text = label; gravity = Gravity.CENTER; isClickable = true; isFocusable = true; maxLines = 1
        textSize = if (small) 12f else 13f; setTypeface(Typeface.DEFAULT, if (small) Typeface.NORMAL else Typeface.BOLD)
        setTextColor(if (primary) OverlayStyle.ON_ACCENT else OverlayStyle.ACCENT); setPadding(dp(14), 0, dp(14), 0)
        if (primary) background = OverlayStyle.gradient(service)
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (service.resources.displayMetrics.density * value + .5f).toInt()
    private fun remove() { view?.let { runCatching { manager.removeView(it) } }; view = null }
    fun destroy() { scope.cancel(); remove() }
}
