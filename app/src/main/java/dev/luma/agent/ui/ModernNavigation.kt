package dev.luma.agent.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable fun ModernNavigation(items: List<Pair<String,ImageVector>>,selected: Int,onSelect: (Int)->Unit) {
    val colors=MaterialTheme.colorScheme
    Surface(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal=12.dp,vertical=4.dp).fillMaxWidth(),
        shape=RoundedCornerShape(26.dp),color=colors.surfaceContainer,tonalElevation=2.dp,
        border=BorderStroke(.6.dp,colors.outlineVariant.copy(alpha=.5f))) {
        Row(Modifier.selectableGroup().padding(horizontal=6.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
            items.forEachIndexed {index,(title,icon) ->
                val active=index==selected
                val tint by animateColorAsState(if(active) colors.primary else colors.onSurfaceVariant,label="nav color")
                val width by animateDpAsState(if(active) 56.dp else 38.dp,if(LocalReducedMotion.current) snap() else spring(dampingRatio=.85f,stiffness=450f),label="nav selection")
                Column(Modifier.weight(1f).selectable(active,role=Role.Tab,onClick={onSelect(index)}).padding(vertical=3.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Surface(Modifier.width(width).height(28.dp),shape=RoundedCornerShape(14.dp),color=if(active) colors.secondaryContainer else Color.Transparent) {
                        Box(contentAlignment=Alignment.Center) {Icon(icon,null,Modifier.size(21.dp),tint=if(active) colors.onSecondaryContainer else tint)}
                    }
                    Text(title,style=MaterialTheme.typography.labelSmall,color=tint,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    }
}
