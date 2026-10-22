package dev.luma.agent.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsVault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "luma-vault.bin"))
    private val mutex = Mutex()
    var loadError: String? = null; private set
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    suspend fun update(change: (AppSettings) -> AppSettings) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loadError != null) throw UserError(loadError!!)
            val next = change(mutable.value)
            val plain = next.json().toString().toByteArray(Charsets.UTF_8)
            if (plain.size > 1_900_000) throw UserError("Сохранённые каталоги слишком большие. Удалите неиспользуемое подключение.")
            val encrypted = encrypt(plain)
            var stream: java.io.FileOutputStream? = null
            try { stream = file.startWrite(); stream.write(encrypted); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw UserError("Не удалось сохранить настройки. Проверьте свободное место.") }
            mutable.value = next
        }
    }
    suspend fun saveConnection(provider: Provider, key: String, endpoint: String) {
        val cleaned = key.trim().removePrefix("Bearer ").trim()
        if (provider.keyRequired && cleaned.isEmpty()) throw UserError("Вставьте API-ключ.")
        if (cleaned.any { it.isWhitespace() } || cleaned.length > 4096) throw UserError("В ключе есть пробелы или переносы строки.")
        val base = if (provider == Provider.LOCAL) validateEndpoint(endpoint) else ""
        update { settings ->
            val old = settings.connection(provider)
            val same = old.key == cleaned && old.endpoint == base
            settings.copy(provider = provider, connections = settings.connections + (provider to if (same) old else Connection(cleaned, endpoint = base, parameters = old.parameters)))
        }
    }
    suspend fun saveModels(provider: Provider, expected: Connection, models: List<ModelInfo>) = update { settings ->
        val current = settings.connection(provider)
        if (current.key != expected.key || current.endpoint != expected.endpoint) settings
        else settings.copy(connections = settings.connections + (provider to current.copy(models = models, catalogAt = System.currentTimeMillis(), model = current.model.takeIf { id -> models.any { it.id == id } } ?: "")))
    }
    suspend fun selectModel(provider: Provider, model: String) = update { settings ->
        val c = settings.connection(provider)
        if (c.models.none { it.id == model }) throw UserError("Обновите список и выберите доступную модель.")
        settings.copy(connections = settings.connections + (provider to c.copy(model = model)))
    }
    private fun load(): AppSettings {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return AppSettings()
        return try {
            val bytes = file.readFully(); if (bytes.size > 2_000_000) throw IllegalStateException()
            val ivSize = bytes.first().toInt() and 255
            if (ivSize !in 12..16 || bytes.size < ivSize + 17) throw IllegalStateException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret(false), GCMParameterSpec(128, bytes.copyOfRange(1, ivSize + 1)))
            AppSettings.parse(JSONObject(String(cipher.doFinal(bytes.copyOfRange(ivSize + 1, bytes.size)), Charsets.UTF_8)))
        } catch (e: Exception) { loadError = "Не удалось открыть настройки Android Keystore. Сбросьте данные Luma в настройках Android и подключите ключ заново."; AppSettings() }
    }
    private fun encrypt(bytes: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secret(true))
        byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(bytes)
    } catch (e: Exception) { throw UserError("Android Keystore недоступен. Ключ не сохранён.") }
    private fun secret(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias("luma.vault.v1")) return store.getKey("luma.vault.v1", null) as SecretKey
        if (!create) throw IllegalStateException()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("luma.vault.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
fun validateEndpoint(value: String): String {
    val uri = runCatching { java.net.URI(value.trim()) }.getOrNull() ?: throw UserError("Проверьте адрес сервера.")
    if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) throw UserError("Нужен адрес API без ключа, параметров и логина в URL.")
    if (uri.scheme != "https" && !(uri.scheme == "http" && isPrivateHost(uri.host))) throw UserError("HTTP разрешён для локальной сети. Для внешнего сервера используйте HTTPS.")
    return uri.toASCIIString().trimEnd('/') + "/"
}
/** Hosts that may be reached over plain HTTP: this device and the local network only. */
fun isPrivateHost(rawHost: String): Boolean {
    val host = rawHost.lowercase().removeSurrounding("[", "]")
    val octets = host.split('.').map { it.toIntOrNull() }
    val ipv4 = octets.size == 4 && octets.all { it != null && it in 0..255 }
    val localV4 = ipv4 && (octets[0] == 10 || octets[0] == 127 || (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 169 && octets[1] == 254))
    // IPv6 loopback, unique local (fc00::/7) and link-local (fe80::/10) literals.
    val localV6 = ':' in host && (host == "::1" || host.startsWith("fc") || host.startsWith("fd") ||
        host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb"))
    return host == "localhost" || host.endsWith(".local") || localV4 || localV6
}
