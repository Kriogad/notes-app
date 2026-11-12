package ru.notes.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.media.RingtoneManager
import androidx.activity.result.PickVisualMediaRequest
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import javax.crypto.Cipher
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

const val NEW_NOTE = 0L

class MainActivity : ComponentActivity(), Host {
    /** Что открыть по запросу виджета/уведомления: id заметки или NEW_NOTE. */
    private var openRequest by mutableStateOf<Long?>(null)
    private var onVoiceText: ((String) -> Unit)? = null

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?.let { onVoiceText?.invoke(it) }
    }
    private var onPicked: ((List<Uri>) -> Unit)? = null
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { onPicked?.invoke(it) }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onPicked?.invoke(it) }

    override fun pickPhotos(onPicked: (List<Uri>) -> Unit) {
        this.onPicked = onPicked
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override fun pickFiles(onPicked: (List<Uri>) -> Unit) {
        this.onPicked = onPicked
        filePicker.launch(arrayOf("*/*"))
    }

    private var onSound: ((Uri?) -> Unit)? = null
    private val soundPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            onSound?.invoke(r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI))
        }
    }

    private var onAudio: ((Uri) -> Unit)? = null
    private val audioPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> u?.let { onAudio?.invoke(it) } }

    override fun pickAudio(onPicked: (Uri) -> Unit) {
        onAudio = onPicked
        audioPicker.launch("audio/*")
    }

    override fun pickSound(current: Uri?, onPicked: (Uri?) -> Unit) {
        onSound = onPicked
        soundPicker.launch(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Мелодия напоминания")
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        )
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(this, "Без разрешения напоминания не будут показаны", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                NotesApp(this, openRequest, onOpenHandled = { openRequest = null })
                CrashReport()
                UpdateCheck()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private var stoppedAt = 0L

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        // Секретный раздел закрывается, если приложение было свёрнуто дольше минуты
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 60_000) Vault.lock()
    }

    private fun handle(intent: Intent) {
        intent.data?.let { if (Backup.handleRedirect(this, it)) Backup.schedule(this) }
        when {
            intent.getBooleanExtra(EXTRA_NEW, false) -> openRequest = NEW_NOTE
            intent.hasExtra(EXTRA_OPEN) -> {
                openRequest = intent.getLongExtra(EXTRA_OPEN, -1)
                // Открыли из напоминания — значит увидели, больше не повторять
                if (intent.getBooleanExtra(EXTRA_ACK, false)) Reminders.ack(this, openRequest!!)
            }
        }
    }

    override fun listen(onText: (String) -> Unit) {
        onVoiceText = onText
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите…")
        try {
            voice.launch(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Голосовой ввод недоступен на этом устройстве", Toast.LENGTH_LONG).show()
        }
    }

    override fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun bioAvailable(): Boolean = Build.VERSION.SDK_INT >= 30 && runCatching {
        getSystemService(BiometricManager::class.java)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    override fun biometric(cipher: Cipher, title: String, onOk: (Cipher) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) return
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Ввести пароль", mainExecutor) { _, _ -> }
            .build()
        runCatching { prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher), CancellationSignal(), mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let(onOk)
                }
            },
        ) }.onFailure { Toast.makeText(this, "Отпечаток недоступен: ${it.message}", Toast.LENGTH_LONG).show() }
    }
}
