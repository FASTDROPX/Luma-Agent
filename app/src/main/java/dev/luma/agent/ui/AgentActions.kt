package dev.luma.agent.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.luma.agent.AppGraph
import dev.luma.agent.core.*
import kotlinx.coroutines.launch

internal class AgentActions(val grant: (PermissionNeed)->Unit,val launch: (SystemToolAction)->Unit,val busy: Boolean)

/** Exactly one visible surface owns Android result launchers, whether chat or conversation. */
@Composable internal fun rememberAgentActions(graph: AppGraph,enabled: Boolean=true): AgentActions {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val run by graph.agent.state.collectAsStateWithLifecycle()
    val settings by graph.vault.state.collectAsStateWithLifecycle()
    // «Выдайте доступ» card: a runtime permission goes through Android's dialog; a special access opens its settings screen.
    var requestedPermissions by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        graph.agent.resumeAccess()
        if (requestedPermissions.any { result[it] != true } && requestedPermissions.any { (context as? Activity)?.shouldShowRequestPermissionRationale(it) == false }) {
            graph.uiMessage.value = tr("Доступ не выдан. Если Android больше не спрашивает, включите его в настройках Luma.")
            dev.luma.agent.core.BackgroundAccess.open(context, dev.luma.agent.core.OptionalAccess.appDetails(context))
        }
    }
    var systemActionId by rememberSaveable { mutableStateOf<String?>(null) }
    val toolActivity = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        systemActionId?.let { graph.agent.completeSystemAction(it, dev.luma.agent.core.SystemToolResult(result.resultCode, result.data)) }; systemActionId = null
    }
    val toolSender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        systemActionId?.let { graph.agent.completeSystemAction(it, dev.luma.agent.core.SystemToolResult(result.resultCode, result.data)) }; systemActionId = null
    }
    fun launchToolAction(action: dev.luma.agent.core.SystemToolAction) {
        if (systemActionId != null) return
        systemActionId = action.id
        try {
            if (action.sender != null) toolSender.launch(androidx.activity.result.IntentSenderRequest.Builder(action.sender).build())
            else action.intent?.let { toolActivity.launch(it) }
        } catch (e: Exception) {
            graph.uiMessage.value = tr("Android не смог открыть приложение для этого действия.")
            graph.agent.completeSystemAction(action.id, dev.luma.agent.core.SystemToolResult(Activity.RESULT_CANCELED)); systemActionId = null
        }
    }
    val workspacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            try {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                graph.vault.update { it.copy(folder = uri.toString()) }; graph.agent.resumeAccess()
            } catch (e: Exception) { graph.uiMessage.value = tr("Не удалось сохранить доступ к выбранной папке.") }
        }
    }
    fun grantAccess(need: dev.luma.agent.core.PermissionNeed) {
        val runtime = need.runtime
        if (need.id == "workspace") { workspacePicker.launch(null); return }
        if (need.id == "screenshots") { scope.launch { graph.vault.update { it.copy(screenshots = true) }; graph.agent.resumeAccess() }; return }
        if (need.id == "background_location" && android.os.Build.VERSION.SDK_INT >= 30)
            dev.luma.agent.core.BackgroundAccess.open(context, dev.luma.agent.core.OptionalAccess.appDetails(context))
        else if(runtime.isNotEmpty()) { requestedPermissions = runtime; permissionLauncher.launch(runtime.toTypedArray()) }
        else dev.luma.agent.core.BackgroundAccess.open(context,dev.luma.agent.core.OptionalAccess.item(need.id)?.screens(context) ?: dev.luma.agent.core.OptionalAccess.appDetails(context))
    }
    LaunchedEffect(run.systemAction?.id,settings.confirmActions,enabled) {
        if(enabled && !settings.confirmActions) run.systemAction?.let {launchToolAction(it)}
    }
    // Coming back from a settings screen (special access): re-check whether it is now on.
    val lifecycleOwner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner,enabled) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event->if(enabled && event==androidx.lifecycle.Lifecycle.Event.ON_RESUME && graph.agent.state.value.permission!=null) graph.agent.resumeAccess()}
        lifecycleOwner.lifecycle.addObserver(observer);onDispose {lifecycleOwner.lifecycle.removeObserver(observer)}
    }
    return AgentActions(::grantAccess,::launchToolAction,systemActionId!=null)
}
