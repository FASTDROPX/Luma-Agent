package dev.luma.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.luma.agent.core.BlockedWords
import dev.luma.agent.core.Gestures
import dev.luma.agent.core.ScreenDriver
import dev.luma.agent.core.ScreenResult
import dev.luma.agent.core.ToolFailure
import dev.luma.agent.core.ToolStatus
import dev.luma.agent.core.UserError
import dev.luma.agent.core.requiredInt
import dev.luma.agent.core.requiredString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

class AgentAccessibilityService : AccessibilityService(), ScreenDriver {
    companion object {
        @Volatile var instance: AgentAccessibilityService? = null; private set
        val connected = MutableStateFlow(false)
        internal const val SNAPSHOT_MAX_AGE_MS = 10 * 60_000L
    }
    private data class Node(val info: AccessibilityNodeInfo, val signature: String)
    private val nodes = mutableMapOf<Int, Node>()
    private var snapshot = ""; private var snapshotPackage = ""
    private var physicalWidth = 1; private var physicalHeight = 1
    private var imageWidth = 1; private var imageHeight = 1
    private var hasImage = false
    private var snapshotTime = 0L
    internal var panel: FloatingPanel? = null
    internal var visuals: AgentVisualOverlay? = null
    private val graph get() = (application as LumaApp).graph
    override fun onServiceConnected() {
        super.onServiceConnected(); instance = this; connected.value = true
        panel?.destroy();visuals?.destroy()
        panel = FloatingPanel(this, graph); panel?.bind()
        visuals = AgentVisualOverlay(this,graph);visuals?.bind()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { graph.agent.stop("Android приостановил службу управления.") }
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null; connected.value = false; panel?.destroy(); panel = null; visuals?.destroy();visuals=null; clearNodes()
        graph.agent.stop("Доступ к управлению отключён."); return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (instance === this) { instance = null; connected.value = false }; panel?.destroy();visuals?.destroy();visuals=null; clearNodes(); graph.agent.stop("Служба управления завершена.");super.onDestroy()
    }
    private fun clearNodes() { nodes.values.forEach { it.info.recycle() }; nodes.clear(); snapshot = "" }
    private fun unlocked() {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) throw UserError("Разблокируйте телефон вручную, затем запустите задачу снова.")
    }
    @Suppress("DEPRECATION")
    override suspend fun observe(withImage: Boolean): ScreenResult = withContext(Dispatchers.Main.immediate) {
        unlocked(); clearNodes()
        val metrics = android.util.DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        physicalWidth = metrics.widthPixels.coerceAtLeast(1); physicalHeight = metrics.heightPixels.coerceAtLeast(1)
        imageWidth = physicalWidth; imageHeight = physicalHeight; hasImage = false
        val initialRoot = rootInActiveWindow
        val pkg = initialRoot?.packageName?.toString().orEmpty(); initialRoot?.recycle(); snapshotPackage = pkg
        snapshot = UUID.randomUUID().toString()
        snapshotTime = android.os.SystemClock.elapsedRealtime()
        val result = JSONObject().put("snapshot", snapshot).put("package", pkg)
        if (pkg == packageName) {
            return@withContext ScreenResult(result.put("width", physicalWidth).put("height", physicalHeight)
                .put("nodes", JSONArray()).put("note", "Открыта Luma. Её внутренний интерфейс скрыт. Для задачи откройте приложение через list_apps и open_app."))
        }
        var jpeg: String? = null
        if (withImage && Build.VERSION.SDK_INT >= 30) {
            panel?.temporarilyHide(true);visuals?.temporarilyHide(true)
            try {
                delay(180)
                val bitmap = screenshot()
                try {
                    physicalWidth = bitmap.width; physicalHeight = bitmap.height
                    val scale = minOf(1f, 1200f / maxOf(bitmap.width, bitmap.height))
                    imageWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1); imageHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                    val resized = Bitmap.createScaledBitmap(bitmap, imageWidth, imageHeight, true)
                    try { val out = ByteArrayOutputStream(); resized.compress(Bitmap.CompressFormat.JPEG, 65, out); jpeg = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP); hasImage = true }
                    finally { if (resized !== bitmap) resized.recycle() }
                } finally { bitmap.recycle() }
            } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); result.put("imageNote", "Не удалось дождаться снимка. Доступно дерево элементов.") }
            catch (e: UserError) { result.put("imageNote", e.message) }
            finally { panel?.temporarilyHide(false);visuals?.temporarilyHide(false) }
        } else if (withImage) result.put("imageNote", "На Android ниже 11 доступны элементы интерфейса без снимка.")
        val items = JSONArray(); var characters = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 24 || items.length() >= 120 || characters > 18_000 || node.isPassword || !node.isVisibleToUser) return
            val rect = Rect(); node.getBoundsInScreen(rect)
            val text = node.text?.toString().orEmpty().take(300); val description = node.contentDescription?.toString().orEmpty().take(200)
            if (text.isNotBlank() || description.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable) {
                val id = nodes.size + 1; nodes[id] = Node(AccessibilityNodeInfo.obtain(node), signature(node))
                items.put(JSONObject().put("id", id).put("text", text).put("description", description)
                    .put("bounds", JSONArray(listOf(scaledX(rect.left), scaledY(rect.top), scaledX(rect.right), scaledY(rect.bottom))))
                    .put("clickable", node.isClickable).put("editable", node.isEditable).put("scrollable", node.isScrollable).put("enabled", node.isEnabled))
                characters += text.length + description.length
            }
            for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; try { visit(child, depth + 1) } finally { child.recycle() } }
        }
        val root = rootInActiveWindow
        if (root?.packageName?.toString().orEmpty() != snapshotPackage) { root?.recycle(); throw UserError("Окно сменилось при чтении экрана. Повторите screen.") }
        root?.let { try { visit(it, 0) } finally { it.recycle() } }
        ScreenResult(result.put("width", imageWidth).put("height", imageHeight).put("nodes", items).put("image", hasImage), jpeg)
    }
    private fun signature(node: AccessibilityNodeInfo): String {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        return "${node.windowId}|${node.viewIdResourceName}|${node.text}|${node.contentDescription}|$bounds"
    }
    private fun scaledX(x: Int) = (x * imageWidth.toDouble() / physicalWidth).roundToInt()
    private fun scaledY(y: Int) = (y * imageHeight.toDouble() / physicalHeight).roundToInt()
    @Suppress("DEPRECATION")
    private fun validateSnapshot(args: JSONObject) {
        unlocked()
        // The model only ever sees the latest screen, so a missing id means that one. The age limit is
        // generous because local models can think for minutes; window, size and node checks still apply.
        val requested = (args.opt("snapshot") as? String)?.takeIf { it.isNotBlank() } ?: snapshot
        if (requested != snapshot || snapshot.isEmpty() || android.os.SystemClock.elapsedRealtime() - snapshotTime > SNAPSHOT_MAX_AGE_MS) throw UserError("Снимок устарел. Сначала снова прочитайте screen.")
        val metrics=android.util.DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        if(metrics.widthPixels!=physicalWidth || metrics.heightPixels!=physicalHeight) throw UserError("Размер экрана изменился. Снова прочитайте screen.")
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString(); root?.recycle()
        if (pkg != snapshotPackage || pkg == packageName) throw UserError("Окно изменилось. Сначала снова прочитайте screen.")
    }
    private fun node(args: JSONObject): AccessibilityNodeInfo {
        validateSnapshot(args)
        val cached = nodes[args.requiredInt("node")] ?: throw UserError("Элемент не найден. Обновите screen.")
        if (!cached.info.refresh() || !cached.info.isVisibleToUser || !cached.info.isEnabled || cached.info.isPassword || signature(cached.info) != cached.signature) throw UserError("Элемент изменился или недоступен. Обновите screen.")
        return cached.info
    }
    override fun actionLabel(name: String, args: JSONObject): String {
        val target = args.optInt("node")
        val label = nodes[target]?.info?.let { it.text?.toString()?.takeIf(String::isNotBlank) ?: it.contentDescription?.toString() }.orEmpty().take(100)
        return when (name) {
            "tap" -> "Нажать: ${label.ifBlank { "элемент $target" }}"
            "set_text" -> "Ввести в «${label.ifBlank { "поле $target" }}»: ${args.optString("text").take(160)}"
            "tap_point" -> textAtPoint(args)?.let { "Нажать: ${it.take(100)} (точка ${args.optInt("x")}, ${args.optInt("y")})" }
                ?: "Нажать точку ${args.optInt("x")}, ${args.optInt("y")}"
            "scroll", "swipe" -> "Прокрутить экран"
            "swipe_screen", "open_notifications", "open_quick_settings", "global_action" -> dev.luma.agent.core.ToolRegistry.label(name, args)
            "open_app" -> "Открыть ${args.optString("package")}"
            "back" -> "Вернуться назад"
            "home" -> "Открыть главный экран"
            else -> name
        }
    }
    /** Text of the smallest labelled element under a screenshot point; labels only, never used to act. */
    private fun textAtPoint(args: JSONObject): String? {
        val point = runCatching { physicalPoint(args.optInt("x", -1), args.optInt("y", -1), imageWidth, imageHeight, physicalWidth, physicalHeight) }.getOrNull() ?: return null
        val rect = Rect()
        return nodes.values.mapNotNull { node ->
            val label = node.info.text?.toString()?.takeIf(String::isNotBlank) ?: node.info.contentDescription?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            node.info.getBoundsInScreen(rect)
            if (rect.contains(point.first.toInt(), point.second.toInt())) label to rect.width().toLong() * rect.height() else null
        }.minByOrNull { it.second }?.first
    }
    /** Returns why a step needs the user's confirmation, or null. Words come only from the user's own list. */
    override fun sensitive(name: String, args: JSONObject, blockedWords: String): String? {
        if (name !in setOf("tap", "tap_point", "set_text")) return null
        if (permissionScreen(snapshotPackage)) return "Системный экран разрешений Android."
        return BlockedWords.match(blockedWords, actionLabel(name, args))?.let { "Совпало запрещённое слово «$it»." }
    }
    private fun permissionScreen(pkg: String) = pkg.contains("permissioncontroller") || pkg.contains("packageinstaller")
    private fun currentPackage(): String { val root = rootInActiveWindow; return root?.packageName?.toString().orEmpty().also { root?.recycle() } }
    @Suppress("DEPRECATION")
    private fun realSize(): Pair<Int, Int> { val m = android.util.DisplayMetrics(); (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(m); return m.widthPixels to m.heightPixels }
    /** The expanded shade or quick settings: a large System UI window, not just the status bar. */
    private fun systemPanelShown(): Boolean = runCatching {
        val (_, height) = realSize(); val rect = Rect()
        windows.any { window ->
            window.getBoundsInScreen(rect)
            val root = window.root; val pkg = root?.packageName?.toString().orEmpty(); root?.recycle()
            window.type == AccessibilityWindowInfo.TYPE_SYSTEM && pkg.contains("systemui") && rect.height() > height * 0.4
        }
    }.getOrDefault(false)
    private suspend fun global(action: Int, fraction: Float, what: String, verify: (suspend () -> Boolean?)? = null): JSONObject {
        visualGlobal(fraction)
        if (!performGlobalAction(action)) throw ToolFailure(ToolStatus.ERROR, "Android не выполнил действие «$what».")
        visuals?.pulse()
        val verified = verify?.let { check -> var value: Boolean? = null; repeat(6) { if (value != true) { delay(200); value = check() } }; value }
        return JSONObject().put("status", ToolStatus.DONE).put("verified", verified == true).apply { if (verify != null && verified != true) put("message", "Android принял действие, но проверить результат не удалось.") }
    }
    override suspend fun execute(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.Main.immediate) {
        if (!(name == "global_action" && args.optString("action") == "lock_screen")) unlocked()
        graph.agent.awaitAction()
        if (name in setOf("tap", "tap_point", "set_text", "swipe") && permissionScreen(snapshotPackage)) throw ToolFailure(ToolStatus.DENIED, "Системное разрешение нужно подтвердить вручную в Android.")
        if (name == "swipe_screen" && permissionScreen(currentPackage())) throw ToolFailure(ToolStatus.DENIED, "На экране системного разрешения жесты агента запрещены. Подтвердите или отклоните его вручную.")
        when (name) {
            "open_notifications" -> return@withContext global(GLOBAL_ACTION_NOTIFICATIONS, .5f, "шторка уведомлений") { systemPanelShown() }
            "open_quick_settings" -> return@withContext global(GLOBAL_ACTION_QUICK_SETTINGS, .5f, "быстрые настройки") { systemPanelShown() }
            "global_action" -> return@withContext when (val action = args.getString("action")) {
                "recents" -> global(GLOBAL_ACTION_RECENTS, .88f, "недавние приложения")
                "power_dialog" -> global(GLOBAL_ACTION_POWER_DIALOG, .5f, "меню питания")
                "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_LOCK_SCREEN, .5f, "блокировка") {
                        getSystemService(KeyguardManager::class.java).isKeyguardLocked || !getSystemService(android.os.PowerManager::class.java).isInteractive }
                    else throw ToolFailure(ToolStatus.UNSUPPORTED, "Блокировка экрана службой доступна на Android 9+.")
                "screenshot" -> if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_TAKE_SCREENSHOT, .5f, "снимок экрана").put("message", "Снимок сохраняет система в галерею; Luma его не получает.")
                    else throw ToolFailure(ToolStatus.UNSUPPORTED, "Снимок экрана службой доступен на Android 9+.")
                "close_shade" -> if (Build.VERSION.SDK_INT >= 31) global(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE, .5f, "закрыть шторку") { !systemPanelShown() }
                    else global(GLOBAL_ACTION_BACK, .12f, "назад") { !systemPanelShown() }
                "all_apps" -> if (Build.VERSION.SDK_INT >= 31) global(GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS, .5f, "все приложения")
                    else throw ToolFailure(ToolStatus.UNSUPPORTED, "Список всех приложений через службу доступен на Android 12+.")
                else -> throw ToolFailure(ToolStatus.ERROR, "Неизвестное системное действие $action.")
            }
            "swipe_screen" -> {
                val (width, height) = realSize()
                val stroke = Gestures.standard(args.getString("direction"), args.optString("edge"), args.optString("length"), width, height)
                gesture(stroke.x1, stroke.y1, stroke.x2, stroke.y2, stroke.durationMs) { graph.agent.awaitAction(); unlocked() }
                delay(350); return@withContext JSONObject().put("status", ToolStatus.DONE).put("verified", false)
                    .put("gesture", JSONObject().put("from", JSONArray(listOf(stroke.x1.roundToInt(), stroke.y1.roundToInt()))).put("to", JSONArray(listOf(stroke.x2.roundToInt(), stroke.y2.roundToInt()))))
                    .put("next", "Результат жеста виден в приложенном screen.")
            }
            "tap" -> {
                pointAtNode(args)
                if (!click(node(args))) throw UserError("Элемент не поддерживает нажатие. Обновите экран.")
                visuals?.pulse()
            }
            "set_text" -> {
                pointAtNode(args)
                val node = node(args); if (!node.isEditable) throw UserError("Элемент не является полем ввода.")
                val text = args.requiredString("text", 4000)
                if (visuals?.typingEnabled() == true) typeVisibly(node, text, args)
                else if (!setText(node, text)) throw UserError("Android не разрешил ввод в это поле.")
                visuals?.pulse()
            }
            "scroll" -> {
                pointAtNode(args)
                val node = node(args); val direction = args.requiredString("direction")
                val action = when (direction) { "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; else -> throw UserError("Неверное направление прокрутки.") }
                if (!node.isScrollable || !node.performAction(action)) throw UserError("Элемент не прокручивается в эту сторону.")
            }
            "tap_point", "swipe" -> {
                validateSnapshot(args)
                fun point(x: String, y: String): Pair<Float, Float> {
                    val px = args.requiredInt(x); val py = args.requiredInt(y)
                    return physicalPoint(px,py,imageWidth,imageHeight,physicalWidth,physicalHeight)
                }
                val start = if (name == "tap_point") point("x", "y") else point("x1", "y1")
                val end=if(name=="swipe") point("x2","y2") else start
                // Without a picture the model knows only the element tree: a tap must land on a listed element, never a guessed spot.
                if (name == "tap_point" && !hasImage && !insideKnownNode(start)) throw ToolFailure(ToolStatus.ERROR, "Точка не попадает ни в один элемент последнего screen. Используйте tap по node; координаты без снимка допустимы только внутри элементов.")
                gesture(start.first, start.second, end.first, end.second, if (name == "swipe") 420 else 80) { graph.agent.awaitAction(); validateSnapshot(args) }
            }
            "back" -> { visualGlobal(.12f);if (!performGlobalAction(GLOBAL_ACTION_BACK)) throw UserError("Не удалось вернуться назад.");visuals?.pulse() }
            "home" -> { visualGlobal(.5f);if (!performGlobalAction(GLOBAL_ACTION_HOME)) throw UserError("Не удалось открыть главный экран.");visuals?.pulse() }
            else -> throw UserError("Неизвестное действие экрана.")
        }
        delay(450); JSONObject().put("status", ToolStatus.DONE).put("ok", true).put("next", "Результат виден в приложенном screen")
    }
    private fun setText(node: AccessibilityNodeInfo, text: String) =
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
    /**
     * Types like a person, for the eye only: the cursor becomes a caret, the text grows in short steps and the
     * particles settle on the letters just typed. The last step sets exactly [text]. Stop and pause are honoured
     * between steps; a stopped task leaves the part typed so far, as a person's typing would.
     */
    private suspend fun typeVisibly(node: AccessibilityNodeInfo, text: String, args: JSONObject) {
        val steps = dev.luma.agent.core.TypingPlan.prefixes(text)
        val pause = dev.luma.agent.core.TypingPlan.stepMillis(text.length, steps.size)
        visuals?.beginTyping(caretOf(node, 0))
        try {
            var shown = ""
            for (prefix in steps) {
                graph.agent.awaitAction(); unlocked()
                if (!node.refresh() || !node.isEditable) throw ToolFailure(ToolStatus.UNKNOWN, "Поле ввода исчезло во время ввода. Проверьте screen, не повторяя ввод.")
                if (!setText(node, prefix)) throw if (prefix === steps.first()) UserError("Android не разрешил ввод в это поле.")
                    else ToolFailure(ToolStatus.UNKNOWN, "Android перестал принимать ввод на середине текста. Проверьте поле через screen.")
                // Give the field one frame to lay out the new letters, then move the caret, then finish the pause.
                val settle = minOf(pause, 24L)
                delay(settle)
                visuals?.updateTyping(caretOf(node, prefix.length), prefix.removePrefix(shown)); shown = prefix
                delay(pause - settle)
            }
        } finally { visuals?.endTyping() }
    }
    /**
     * Caret and recent letters in screen pixels. Android reports the bounds of each character
     * (EXTRA_DATA_TEXT_CHARACTER_LOCATION); letters scrolled out of a one-line field come back empty and are skipped.
     * Without that data the caret is estimated from the field size.
     */
    @Suppress("DEPRECATION")
    private fun caretOf(node: AccessibilityNodeInfo, length: Int): TypingCaret {
        val field = Rect(); node.getBoundsInScreen(field)
        val density = resources.displayMetrics.density
        val count = minOf(length, 24)
        if (count > 0 && node.availableExtraData?.contains(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY) == true) {
            val request = Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, length - count)
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, count)
            }
            val boxes = runCatching {
                if (node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, request))
                    node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)?.mapNotNull { it as? android.graphics.RectF }
                else null
            }.getOrNull().orEmpty().filter { it.width() >= 0f && it.height() > 0f && it.centerX() >= field.left - 1 && it.centerX() <= field.right + 1 }
            if (boxes.isNotEmpty()) {
                val last = boxes.last()
                return TypingCaret(last.right + 1.5f * density, last.centerY(), last.height() * 1.15f, FloatArray(boxes.size * 2) { if (it % 2 == 0) boxes[it / 2].centerX() else boxes[it / 2].centerY() })
            }
        }
        // Estimate: about 0.55 em per character at 16 sp, kept inside the field like a scrolled one-line input.
        val step = 16 * resources.displayMetrics.scaledDensity * .55f
        val inset = 12 * density
        val x = (field.left + inset + length * step).coerceAtMost(field.right - inset).coerceAtLeast(field.left + inset)
        val y = field.exactCenterY(); val height = minOf(field.height() * .55f, 24 * density)
        val shown = (0 until count).map { x - (it + .5f) * step }.filter { it >= field.left + inset }.reversed()
        return TypingCaret(x, y, height, FloatArray(shown.size * 2) { if (it % 2 == 0) shown[it / 2] else y })
    }
    private fun insideKnownNode(point: Pair<Float, Float>): Boolean {
        val rect = Rect()
        return nodes.values.any { node -> node.info.getBoundsInScreen(rect); rect.contains(point.first.toInt(), point.second.toInt()) }
    }
    /** One stroke; [recheck] runs after the cursor animation, right before the gesture is injected. */
    private suspend fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long, recheck: suspend () -> Unit) {
        visuals?.travelTo(x1, y1); visuals?.prepareGesture(x1, y1, x2, y2)
        recheck()
        val moves = x1 != x2 || y1 != y2
        val path = Path().apply { moveTo(x1, y1); if (moves) lineTo(x2, y2) }
        panel?.temporarilyHide(true)
        try {
            withTimeout(5000) { suspendCancellableCoroutine<Unit> { continuation ->
                val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(Unit) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resumeWithException(ToolFailure(ToolStatus.UNKNOWN, "Android прервал жест; он мог выполниться частично. Проверьте экран.")) }
                }, null)
                if (accepted) { visuals?.pulse(); if (moves) visuals?.traceTo(x2, y2) }
                if (!accepted && continuation.isActive) continuation.resumeWithException(ToolFailure(ToolStatus.ERROR, "Android не принял жест."))
            } }
        } catch (e: TimeoutCancellationException) {
            // A stuck gesture is a failed step, not the end of the whole task; it may still have happened.
            currentCoroutineContext().ensureActive()
            throw ToolFailure(ToolStatus.UNKNOWN, "Android не подтвердил жест за 5 секунд. Проверьте screen, не повторяя жест.")
        } finally { panel?.temporarilyHide(false) }
    }
    /** A label is often not clickable itself; like a finger, the tap lands on its nearest clickable container. */
    private fun click(target: AccessibilityNodeInfo): Boolean {
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var current: AccessibilityNodeInfo? = target.parent
        repeat(6) {
            val node = current ?: return false
            if (node.isClickable && node.isEnabled && node.isVisibleToUser && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = node.parent
        }
        return false
    }
    private suspend fun pointAtNode(args: JSONObject) {
        val before=node(args);val rect=Rect();before.getBoundsInScreen(rect)
        if(rect.isEmpty || !rect.intersect(0,0,physicalWidth,physicalHeight)) throw UserError("Элемент за пределами экрана. Обновите screen.")
        visuals?.travelTo(rect.exactCenterX(),rect.exactCenterY())
        graph.agent.awaitAction();unlocked()
        // Re-fetch after the animation: it may have been cancelled, paused or the screen may have changed.
        node(args)
    }
    private suspend fun visualGlobal(fraction: Float) {
        @Suppress("DEPRECATION")
        val metrics=android.util.DisplayMetrics().also {(getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(it)}
        visuals?.travelTo(metrics.widthPixels*fraction,metrics.heightPixels-24*metrics.density)
        graph.agent.awaitAction();unlocked()
    }
    private suspend fun screenshot(): Bitmap = if (Build.VERSION.SDK_INT < 30) throw UserError("Снимки требуют Android 11 или новее.") else withTimeout(5000) {
        suspendCancellableCoroutine { continuation ->
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    try {
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: throw IllegalStateException()
                        val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false); hardware.recycle()
                        if (continuation.isActive) continuation.resume(bitmap) else bitmap.recycle()
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(UserError("Не удалось прочитать снимок.")) }
                    finally { buffer.close() }
                }
                override fun onFailure(errorCode: Int) { if (continuation.isActive) continuation.resumeWithException(UserError("Android не разрешил снимок этого экрана. Доступны только элементы интерфейса.")) }
            })
        }
    }
}

internal fun physicalPoint(x: Int,y: Int,imageWidth: Int,imageHeight: Int,screenWidth: Int,screenHeight: Int): Pair<Float,Float> {
    if(imageWidth<=0 || imageHeight<=0 || screenWidth<=0 || screenHeight<=0 || x !in 0 until imageWidth || y !in 0 until imageHeight)
        throw UserError("Координаты за пределами экрана. Обновите screen.")
    return x*screenWidth.toFloat()/imageWidth to y*screenHeight.toFloat()/imageHeight
}
