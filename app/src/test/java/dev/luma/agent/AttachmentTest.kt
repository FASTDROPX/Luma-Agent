package dev.luma.agent

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import dev.luma.agent.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentTest {
    @Test fun changingToTextOnlyModelKeepsNewTextRequestsUsableButRejectsANewImage()=runTest {
        var calls=0
        val api=ProviderClient {r ->calls++;assertFalse(r.body.toString().contains("YQ=="));assertTrue(r.body.toString().contains("недоступен текущей модели"));JSONObject("""{"choices":[{"message":{"content":"ok"}}]}""")}
        val c=Connection("fake","deepseek-chat")
        val image=Turn("user","photo",attachments=listOf(AttachmentData("photo.jpg","image/jpeg","YQ==")))
        assertEquals("ok",api.generate(Provider.DEEPSEEK,c,listOf(image,Turn("assistant","done"),Turn("user","hello")),"system"))
        try {api.generate(Provider.DEEPSEEK,c,listOf(image),"system");fail()} catch(_: UserError) {}
        assertEquals(1,calls)
    }
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    @Test fun fileSurvivesHistoryReloadAndIsRemovedWithItsLastConversation()=runBlocking {
        val chats=ChatStore(context)
        try {
            val file=chats.attachments.store("notes.txt","text/plain","Привет из файла".toByteArray())
            val id=chats.ensure("files");chats.append(id,"user","Прочитай",listOf(file))
            assertEquals(file,chats.messages.value.single().attachments.single())
            assertEquals("Привет из файла",chats.history(id).single().attachments.single().text)
            chats.select(null);chats.select(id);assertEquals(file,chats.messages.value.single().attachments.single())
            chats.delete(id);assertFalse(chats.attachments.file(file).exists())
        } finally {chats.close()}
    }
    @Test fun reasoningAndSpeedAreKeptForTheChatButNeverSentBack()=runBlocking {
        val chats=ChatStore(context)
        try {
            val id=chats.ensure("think");chats.append(id,"user","q")
            chats.append(id,"assistant","ответ",reasoning="мысли",stats=TokenStats(10,20,100,1100,400))
            val shown=chats.messages.value.last()
            assertEquals("мысли",shown.reasoning);assertEquals(20,shown.stats!!.outputTokens);assertEquals(300,shown.stats!!.thinkingMs)
            assertEquals(listOf("q","ответ"),chats.history(id).map {it.text})
            assertFalse(chats.history(id).joinToString {it.text}.contains("мысли"))
        } finally {chats.close()}
    }
    @Test fun historyLimitReadsOnlyTheNewestMessages()=runBlocking {
        val chats=ChatStore(context)
        try {
            val id=chats.ensure("limit");repeat(10) {chats.append(id,if(it%2==0) "user" else "assistant","m$it")}
            assertEquals(listOf("m7","m8","m9"),chats.history(id,3).map {it.text})
            assertEquals(10,chats.history(id).size)
        } finally {chats.close()}
    }
    @Test fun deletingOneChatDoesNotDestroyAnAttachmentStillReferencedByAnother()=runBlocking {
        val chats=ChatStore(context)
        try {
            val file=chats.attachments.store("a.txt","text/plain","a".toByteArray())
            val a=chats.ensure("a");chats.append(a,"user","a",listOf(file));chats.select(null)
            val b=chats.ensure("b");chats.append(b,"user","b",listOf(file))
            chats.delete(a);assertTrue(chats.attachments.file(file).exists());assertEquals("a",chats.history(b).single().attachments.single().text)
            chats.delete(b);assertFalse(chats.attachments.file(file).exists())
        } finally {chats.close()}
    }
    @Test fun imagesAreNormalizedAndTransparentPixelsGetAReadableWhiteBackground() {
        val store=AttachmentStore(context);val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val bytes=ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray()}
        val item=store.store("../../picture.png","image/png",bytes)
        assertEquals("image/jpeg",item.mime);assertFalse(item.name.contains('/'));assertEquals(item.id,store.file(item).name)
        val decoded=BitmapFactory.decodeFile(store.file(item).path)
        assertTrue(Color.red(decoded.getPixel(5,5))>245);assertTrue(store.payload(item).data.isNotBlank())
        decoded.recycle();bitmap.recycle()
    }
    @Test fun invalidPathsBinaryFilesAndOversizedTextAreRejected() {
        val store=AttachmentStore(context)
        for(action in listOf<()->Unit>(
            {store.file(Attachment("../../secret","x","text/plain",0))},
            {store.store("a.zip","application/zip",byteArrayOf(1,2,3))},
            {store.store("a.txt","text/plain",ByteArray(270_000){65})},
            {store.store("a.txt","text/plain",byteArrayOf(0xc3.toByte(),0x28))}
        )) try {action();fail()} catch(_: UserError) {}
    }
    @Test fun oldVersionOneHistoryMigratesWithoutLosingMessages()=runBlocking {
        val file=File(context.noBackupFilesDir,"conversations.db")
        SQLiteDatabase.openOrCreateDatabase(file,null).use {db ->
            db.execSQL("CREATE TABLE conversations(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,updated INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chat INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,role TEXT NOT NULL,body TEXT NOT NULL,created INTEGER NOT NULL)")
            db.execSQL("INSERT INTO conversations VALUES(1,'old',1)");db.execSQL("INSERT INTO messages VALUES(1,1,'user','Старая история',1)");db.version=1
        }
        val chats=ChatStore(context)
        try {chats.select(1);assertEquals("Старая история",chats.messages.value.single().text);assertTrue(chats.messages.value.single().attachments.isEmpty());assertEquals("",chats.messages.value.single().reasoning);assertEquals(4,chats.readableDatabase.version)}
        finally {chats.close()}
    }
    @Test fun systemPickerUriIsCopiedWithoutRetainingExternalAccess()=runBlocking {
        val original=File(context.cacheDir,"picked.txt").apply {writeText("picked content")}
        val provider=object: ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri: Uri)="text/plain"
            override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?)=
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {addRow(arrayOf<Any>("picked.txt",original.length()))}
            override fun openFile(uri: Uri,mode: String)=ParcelFileDescriptor.open(original,ParcelFileDescriptor.MODE_READ_ONLY)
            override fun insert(uri: Uri,values: ContentValues?): Uri?=null
            override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?)=0
            override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?)=0
        }
        provider.attachInfo(context,android.content.pm.ProviderInfo().apply {authority="picked.test";exported=true})
        ShadowContentResolver.registerProviderInternal("picked.test",provider)
        val store=AttachmentStore(context);val item=store.importFile(Uri.parse("content://picked.test/a"))
        original.delete();assertEquals("picked content",store.payload(item).text)
    }
    @Test fun providerAttachmentsUseNativeImageAndPdfPartsAndTextWorksEverywhere()=runTest {
        for(provider in Provider.entries) {
            val c=Connection("fake","model","http://127.0.0.1:1234/v1/",models=listOf(ModelInfo("model",vision=true)))
            val files=mutableListOf(AttachmentData("notes.txt","text/plain",text="file text"))
            if(provider!=Provider.DEEPSEEK) files+=AttachmentData("picture.jpg","image/jpeg","YQ==")
            if(provider !in setOf(Provider.DEEPSEEK,Provider.LOCAL)) files+=AttachmentData("paper.pdf","application/pdf","Yg==")
            val api=ProviderClient {r ->
                val raw=r.body.toString();assertTrue(raw.contains("file text"))
                if(files.size>1) assertTrue(raw.contains("YQ=="))
                if(files.size>2) assertTrue(raw.contains("Yg=="))
                JSONObject(when(provider) {Provider.GEMINI->"""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""";Provider.CLAUDE->"""{"content":[{"type":"text","text":"ok"}]}""";else->"""{"choices":[{"message":{"content":"ok"}}]}"""})
            }
            assertEquals("ok",api.generate(provider,c,listOf(Turn("user","read",attachments=files)),"system"))
        }
    }
}
