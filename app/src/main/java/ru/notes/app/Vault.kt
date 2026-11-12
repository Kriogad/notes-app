package ru.notes.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import java.io.InputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Секретные заметки. Файл secret.enc: соль, подсказка и данные, зашифрованные AES-256-GCM.
 * Ключ получается из пароля (PBKDF2, 200 000 итераций) и живёт только в памяти, пока раздел открыт.
 * Для входа по отпечатку тот же ключ дополнительно шифруется ключом из Android Keystore,
 * который выдаётся только после подтверждения отпечатком.
 */
object Vault : NoteStore {
    const val FILE = "secret.enc"
    private const val BIO_ALIAS = "notes_vault_bio"
    private const val ITERATIONS = 200_000

    private lateinit var app: Context
    private val file get() = File(app.filesDir, FILE)
    private val prefs get() = app.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private var key: SecretKey? = null

    private val state = MutableStateFlow<List<Note>>(emptyList())
    override val notes: StateFlow<List<Note>> = state
    val unlocked = MutableStateFlow(false)

    fun init(context: Context) { app = context.applicationContext }

    val exists get() = file.exists()
    val hint: String get() = runCatching { JSONObject(file.readText()).optString("hint") }.getOrDefault("")

    // ---------- пароль ----------

    suspend fun create(password: String, hint: String) = withContext(Dispatchers.Default) {
        val salt = random(16)
        val k = derive(password, salt)
        prefs.edit().clear().apply()
        key = k
        write(emptyList(), salt, hint)
        state.value = emptyList()
        unlocked.value = true
    }

    /** true — пароль верный. */
    suspend fun unlock(password: String): Boolean = withContext(Dispatchers.Default) {
        val salt = b64(JSONObject(file.readText()).getString("salt"))
        open(derive(password, salt))
    }

    fun lock() {
        key = null
        Attachments.clearViews(app)
        state.value = emptyList()
        unlocked.value = false
    }

    /** Файл заменён (восстановление из копии) — закрываем, отпечаток надо включить заново. */
    fun reload() {
        lock()
        disableBio()
    }

    private fun open(k: SecretKey): Boolean = try {
        val o = JSONObject(file.readText())
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, b64(o.getString("iv"))))
        val arr = JSONArray(String(c.doFinal(b64(o.getString("data")))))
        state.value = List(arr.length()) { Notes.fromJson(arr.getJSONObject(it)) }.sortedByDescending { it.updated }
        key = k
        unlocked.value = true
        true
    } catch (e: Exception) {
        false
    }

    // ---------- отпечаток ----------

    val bioEnabled get() = prefs.contains("bio_data")

    fun disableBio() {
        prefs.edit().remove("bio_data").remove("bio_iv").apply()
        runCatching { keystore().deleteEntry(BIO_ALIAS) }
    }

    /** Шифр для включения отпечатка (вызывать, когда раздел открыт). */
    fun bioEncryptCipher(): Cipher {
        runCatching { keystore().deleteEntry(BIO_ALIAS) }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(BIO_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, gen.generateKey()) }
    }

    /** Отпечаток подтверждён — сохраняем зашифрованный ключ раздела. */
    fun bioSave(cipher: Cipher) {
        val k = key ?: return
        prefs.edit()
            .putString("bio_data", b64(cipher.doFinal(k.encoded)))
            .putString("bio_iv", b64(cipher.iv))
            .apply()
    }

    /** Шифр для входа по отпечатку; null — отпечаток недоступен (например, добавили новый палец). */
    fun bioDecryptCipher(): Cipher? = try {
        val k = keystore().getKey(BIO_ALIAS, null) as SecretKey
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, b64(prefs.getString("bio_iv", "")!!)))
        }
    } catch (e: KeyPermanentlyInvalidatedException) {
        disableBio(); null
    } catch (e: Exception) {
        null
    }

    fun bioUnlock(cipher: Cipher): Boolean = try {
        open(SecretKeySpec(cipher.doFinal(b64(prefs.getString("bio_data", "")!!)), "AES"))
    } catch (e: Exception) {
        false
    }

    // ---------- хранение ----------

    override fun get(id: Long) = state.value.find { it.id == id }

    @Synchronized
    override fun upsert(note: Note) = commit(state.value.filter { it.id != note.id } + note)

    @Synchronized
    override fun delete(id: Long) {
        get(id)?.attachments?.forEach { Attachments.delete(app, it) }
        commit(state.value.filter { it.id != id })
    }

    // ---------- файлы вложений: [12 байт IV][шифротекст + тег] ----------

    fun encryptTo(input: InputStream, out: File): Boolean {
        val k = key ?: return false
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
        out.outputStream().use { o ->
            o.write(c.iv)
            CipherOutputStream(o, c).use { input.copyTo(it) }
        }
        return true
    }

    fun decrypt(f: File): ByteArray? {
        val k = key ?: return null
        return runCatching {
            val all = f.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, all, 0, 12))
            c.doFinal(all, 12, all.size - 12)
        }.getOrNull()
    }

    override fun setDone(id: Long, done: Boolean) {
        get(id)?.let { upsert(it.copy(done = done)) }
    }

    private fun commit(list: List<Note>) {
        if (key == null) return
        val o = JSONObject(file.readText())
        write(list, b64(o.getString("salt")), o.optString("hint"))
        state.value = list.sortedByDescending { it.updated }
        Backup.schedule(app)
    }

    private fun write(list: List<Note>, salt: ByteArray, hint: String) {
        val arr = JSONArray().apply { list.forEach { put(Notes.toJson(it)) } }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val data = c.doFinal(arr.toString().toByteArray())
        val o = JSONObject().put("v", 1).put("salt", b64(salt)).put("iv", b64(c.iv))
            .put("data", b64(data)).put("hint", hint)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    private fun derive(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
