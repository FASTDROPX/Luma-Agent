package dev.luma.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable fun AgentAccessMode(confirmActions: Boolean,enabled: Boolean=true,onChange: (Boolean)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Box {
        TextButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("agent-access-mode"),
            contentPadding=PaddingValues(horizontal=8.dp,vertical=2.dp)) {
            Icon(if(confirmActions) Icons.Outlined.VerifiedUser else Icons.Outlined.Bolt,null,Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if(confirmActions) "Запрашивать разрешения" else "Полный доступ",Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium)
            Icon(Icons.Outlined.ExpandMore,null,Modifier.size(18.dp))
        }
        if(expanded && enabled) AlertDialog(onDismissRequest={expanded=false},title={Text("Действия агента")},text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={expanded=false;onChange(true)},modifier=Modifier.fillMaxWidth().testTag("access-mode-ask")) {
                    RadioButton(confirmActions,onClick=null);Spacer(Modifier.width(8.dp));Text("Запрашивать разрешения",Modifier.weight(1f))
                }
                TextButton(onClick={expanded=false;onChange(false)},modifier=Modifier.fillMaxWidth().testTag("access-mode-full")) {
                    RadioButton(!confirmActions,onClick=null);Spacer(Modifier.width(8.dp));Text("Полный доступ",Modifier.weight(1f))
                }
                Text("Полный доступ: обычные шаги выполняются автоматически в пределах выданных разрешений. Удаление, перемещение и перезапись файлов, а также нажатия с вашими запрещёнными словами — с подтверждением.",style=MaterialTheme.typography.bodySmall)
            }
        },confirmButton={TextButton(onClick={expanded=false}) {Text("Закрыть")}})
    }
}
