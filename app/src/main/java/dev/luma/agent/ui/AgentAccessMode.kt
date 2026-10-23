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
    var confirmFull by remember {mutableStateOf(false)}
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
                TextButton(onClick={expanded=false;if(confirmActions) confirmFull=true},modifier=Modifier.fillMaxWidth().testTag("access-mode-full")) {
                    RadioButton(!confirmActions,onClick=null);Spacer(Modifier.width(8.dp));Text("Полный доступ",Modifier.weight(1f))
                }
                Text("Полный доступ: все действия порученной задачи выполняются без подтверждений Luma. Разрешения и системные окна Android остаются под вашим контролем.",style=MaterialTheme.typography.bodySmall)
            }
        },confirmButton={TextButton(onClick={expanded=false}) {Text("Закрыть")}})
        if(confirmFull) AlertDialog(onDismissRequest={confirmFull=false},
            title={Text("Вы действительно хотите включить полный доступ?")},
            text={Text("Агент сможет выполнять действия без дополнительных вопросов Luma, в том числе звонить, отправлять SMS, изменять и удалять файлы. Режим действует в пределах разрешений Android. Остановить задачу можно кнопкой «Стоп».")},
            confirmButton={TextButton(onClick={confirmFull=false;onChange(false)},modifier=Modifier.testTag("confirm-full-access")) {Text("Включить полный доступ")}},
            dismissButton={TextButton(onClick={confirmFull=false}) {Text("Отмена")}})
    }
}
