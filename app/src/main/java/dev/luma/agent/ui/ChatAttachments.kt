package dev.luma.agent.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.luma.agent.core.Attachment
import dev.luma.agent.core.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun AttachmentStrip(files: List<Attachment>,store: AttachmentStore,remove: ((Attachment)->Unit)?=null) {
    Row(Modifier.fillMaxWidth().motionHorizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        for(file in files) key(file.id) {
            val preview by produceState<ImageBitmap?>(null,file.id) {
                if(file.mime.startsWith("image/")) value=withContext(Dispatchers.IO) {runCatching {
                    BitmapFactory.decodeFile(store.file(file).path,BitmapFactory.Options().apply {inSampleSize=4})?.asImageBitmap()
                }.getOrNull()}
            }
            Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerHighest) {
                Row(Modifier.widthIn(max=190.dp).padding(6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    val bitmap=preview
                    if(bitmap!=null) Image(bitmap,file.name,Modifier.size(38.dp),contentScale=ContentScale.Crop)
                    else Icon(if(file.mime.startsWith("audio/")) Icons.Outlined.AudioFile else Icons.Outlined.InsertDriveFile,null,Modifier.size(26.dp))
                    Column(Modifier.weight(1f,fill=false).widthIn(max=110.dp)) {
                        Text(file.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium)
                        Text(if(file.size<1000) "${file.size} Б" else if(file.size<1_000_000) "${file.size/1000} КБ" else "%.1f МБ".format(file.size/1_000_000f),style=MaterialTheme.typography.labelSmall)
                    }
                    if(remove!=null) IconButton(onClick={remove(file)},modifier=Modifier.size(32.dp)) {Icon(Icons.Outlined.Close,"Убрать ${file.name}",Modifier.size(16.dp))}
                }
            }
        }
    }
}
