package com.voiceflow.os

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.CalendarContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class MainActivity : Activity(), RecognitionListener {

    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var micButton: Button
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        createUi()
        createLockScreenNotification()
        requestPermissionsIfNeeded()

        if (intent.getBooleanExtra(EXTRA_START_LISTENING, false)) {
            micButton.postDelayed({ startListening() }, 350)
        }
    }

    private fun createUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 72, 48, 72)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Voice Flow OS"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        }
        statusText = TextView(this).apply {
            text = "点击按钮开始说话"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 24)
            setTextColor(Color.DKGRAY)
        }
        resultText = TextView(this).apply {
            text = "例如：待办，明天下午三点提交报告"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
            setTextColor(Color.GRAY)
        }
        micButton = Button(this).apply {
            text = "🎤 开始说话"
            textSize = 20f
            setOnClickListener { startListening() }
        }

        root.addView(title, matchWrap())
        root.addView(statusText, matchWrap())
        root.addView(resultText, matchWrap())
        root.addView(micButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.text = "系统没有可用的语音识别服务"
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
        }

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出你的指令")
        }

        statusText.text = "正在聆听……"
        resultText.text = ""
        micButton.isEnabled = false
        speechRecognizer?.startListening(recognizerIntent)
    }

    override fun onResults(results: Bundle?) {
        micButton.isEnabled = true
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        statusText.text = if (text.isBlank()) "没有听清" else "识别完成"
        resultText.text = text
        if (text.isNotBlank()) routeCommand(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val partial = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
        if (!partial.isNullOrBlank()) resultText.text = partial
    }

    private fun routeCommand(raw: String) {
        val normalized = raw.replace("：", ":").trim()
        when {
            normalized.startsWith("待办") -> {
                saveLocalCommand(normalized)
                statusText.text = "已收到待办指令（MVP 暂存）"
                Toast.makeText(this, "下一阶段接入滴答清单", Toast.LENGTH_LONG).show()
            }
            normalized.startsWith("日历") || normalized.contains("开会") -> {
                openCalendarDraft(normalized.substringAfter(":", normalized))
            }
            normalized.startsWith("记录") || normalized.contains("知识库") -> {
                saveLocalCommand(normalized)
                statusText.text = "已保存知识记录草稿"
            }
            else -> {
                saveLocalCommand(normalized)
                statusText.text = "已保存原始指令，等待 AI 路由"
            }
        }
    }

    private fun saveLocalCommand(command: String) {
        getSharedPreferences("voice_flow_log", MODE_PRIVATE)
            .edit()
            .putString(System.currentTimeMillis().toString(), command)
            .apply()
    }

    private fun openCalendarDraft(title: String) {
        val start = LocalDateTime.now().plusHours(1)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = start + 60 * 60 * 1000
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title.ifBlank { "Voice Flow 日程" })
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        }
        runCatching { startActivity(intent) }
            .onSuccess { statusText.text = "已打开 OPPO 日历草稿" }
            .onFailure { statusText.text = "无法打开系统日历：${it.message}" }
    }

    private fun createLockScreenNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.voiceflow.os.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(com.voiceflow.os.R.string.notification_channel_description)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_START_LISTENING, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.voiceflow.os.R.drawable.ic_mic)
            .setContentTitle("Voice Flow OS")
            .setContentText("点击后直接开始语音输入")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onError(error: Int) {
        micButton.isEnabled = true
        statusText.text = "识别失败（错误码 $error），请重试"
    }
    override fun onReadyForSpeech(params: Bundle?) { statusText.text = "请说话……" }
    override fun onBeginningOfSpeech() { statusText.text = "正在听" }
    override fun onEndOfSpeech() { statusText.text = "正在识别……" }
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onDestroy() {
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "voice_flow_lock_screen"
        private const val NOTIFICATION_ID = 1001
        private const val REQ_PERMISSIONS = 2001
        private const val EXTRA_START_LISTENING = "start_listening"
    }
}
