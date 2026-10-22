package dev.luma.agent

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
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
        gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(56)
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
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; elevation = dp(8).toFloat()
            if (expanded) setPadding(dp(10), dp(6), dp(10), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xff273653.toInt(), 0xff393053.toInt())).apply {
                cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0x999fbfff.toInt())
            }
            contentDescription = "Luma: ${current.stage}"
        }
        val header = TextView(service).apply {
            text = if (!expanded) "✦" else headerText()
            textSize = if (expanded) 15f else 20f; setTextColor(0xffd9defa.toInt()); gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT, Typeface.BOLD); minHeight = dp(48); minWidth = dp(48)
        }
        this.header = header
        root.addView(header, LinearLayout.LayoutParams(if (expanded) -1 else dp(48), dp(if (expanded) 40 else 48)))
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
            root.addView(TextView(service).apply { text = current.approval?.title ?: current.stage; textSize = 14f; setTextColor(0xffe7eaf3.toInt()); setPadding(dp(8), dp(8), dp(8), dp(12)); maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(dp(250), -2))
            current.approval?.let { approval -> root.addView(TextView(service).apply { text = approval.details; textSize = 12f; setTextColor(0xffc3c8d7.toInt()); maxLines = 7; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(8), dp(8)) }, LinearLayout.LayoutParams(dp(250), -2)) }
            val actions = LinearLayout(service)
            if (current.approval != null) actions.addView(button("Разрешить", true) { graph.agent.decide(true) }, LinearLayout.LayoutParams(0, -2, 1f))
            else if(current.retryPending) actions.addView(button("Повторить",true) {graph.agent.retryRequest()},LinearLayout.LayoutParams(0,-2,1f))
            else actions.addView(button(if (current.paused) "Продолжить" else "Пауза", true) { graph.agent.togglePause() }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(button("Стоп", false) { graph.agent.stop() }, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(actions)
            root.addView(button("Открыть чат", false) { service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) })
        }
        root.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        remove(); view = root
        params.width = if (expanded) dp(280) else dp(48); params.height = -2
        params.x = params.x.coerceIn(0, (service.resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
        runCatching { manager.addView(root, params) }.onFailure { view = null }
    }
    private fun button(label: String, primary: Boolean, action: () -> Unit) = Button(service).apply {
        text = label; textSize = 12f; isAllCaps = false; minHeight = dp(42); minimumHeight = dp(42); setPadding(dp(8), dp(6), dp(8), dp(6))
        setTextColor(if (primary) 0xff152044.toInt() else 0xffc4ceff.toInt()); backgroundTintList = null
        background = shape(if (primary) 0xffafbeff.toInt() else Color.TRANSPARENT, 24); setOnClickListener { action() }
    }
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (service.resources.displayMetrics.density * value + .5f).toInt()
    private fun remove() { view?.let { runCatching { manager.removeView(it) } }; view = null }
    fun destroy() { scope.cancel(); remove() }
}
