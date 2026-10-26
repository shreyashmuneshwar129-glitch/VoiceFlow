package com.voiceflow.keyboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class VoiceFlowService : InputMethodService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false
    private var isBusy = false

    private var languageMode = GeminiClient.LanguageMode.AUTO
    private var previousTextSnapshot: String? = null

    private lateinit var statusTextView: TextView
    private lateinit var micButton: TextView
    private lateinit var cancelButton: TextView
    private lateinit var langButton: TextView

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D11"))
            setPadding(dp(10), dp(8), dp(10), dp(12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Top AI Toolbar (Scrollable chips)
        val scrollToolbar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val toolbarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }

        for (action in GeminiClient.ToolbarAction.values()) {
            val chip = createPillButton(
                text = action.title,
                bgColor = "#1C1C26",
                strokeColor = "#2E2E3E",
                textColor = "#ECECF4",
                textSizeSp = 13f,
                padH = 14,
                padV = 8
            ).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(8)
                layoutParams = lp
                setOnClickListener {
                    vibrateLight()
                    runToolbarAction(action)
                }
            }
            toolbarRow.addView(chip)
        }

        // Undo Chip at the end of toolbar
        val undoChip = createPillButton(
            text = "↩️ Undo",
            bgColor = "#2A1E2E",
            strokeColor = "#4A3252",
            textColor = "#FFD54F",
            textSizeSp = 13f,
            padH = 14,
            padV = 8
        ).apply {
            setOnClickListener {
                vibrateLight()
                performUndo()
            }
        }
        toolbarRow.addView(undoChip)

        scrollToolbar.addView(toolbarRow)
        root.addView(scrollToolbar)

        // 2. Language Mode + Status Bar Row
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }

        langButton = createPillButton(
            text = languageMode.label,
            bgColor = "#182232",
            strokeColor = "#293B56",
            textColor = "#90CAF9",
            textSizeSp = 12f,
            padH = 12,
            padV = 6
        ).apply {
            setOnClickListener {
                vibrateLight()
                cycleLanguageMode()
            }
        }

        statusTextView = TextView(this).apply {
            text = "Tap mic & speak naturally"
            setTextColor(Color.parseColor("#B0B0C0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusRow.addView(langButton)
        statusRow.addView(statusTextView)
        root.addView(statusRow)

        // 3. Center Big Mic Row
        val micContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        }

        micButton = createPillButton(
            text = "🎙️  Tap to Speak",
            bgColor = "#D32F2F",
            strokeColor = "#FF5252",
            textColor = "#FFFFFF",
            textSizeSp = 18f,
            padH = 36,
            padV = 20,
            isBold = true
        ).apply {
            setOnClickListener {
                vibrateLight()
                onMicButtonTapped()
            }
        }

        cancelButton = createPillButton(
            text = "✖ Cancel",
            bgColor = "#2C2C38",
            strokeColor = "#444454",
            textColor = "#FF8A80",
            textSizeSp = 14f,
            padH = 16,
            padV = 18
        ).apply {
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(12)
            layoutParams = lp
            setOnClickListener {
                vibrateLight()
                cancelRecording()
            }
        }

        micContainer.addView(micButton)
        micContainer.addView(cancelButton)
        root.addView(micContainer)

        // 4. Bottom Utility Row (Switch Keyboard, Comma, Space, Period, Backspace, Enter)
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val switchKbBtn = createUtilityKey("🌐", weight = 1f) {
            vibrateLight()
            switchToNextKeyboard()
        }
        val commaBtn = createUtilityKey(",", weight = 0.8f) {
            vibrateLight()
            currentInputConnection?.commitText(", ", 1)
        }
        val spaceBtn = createUtilityKey("Space", weight = 2.8f) {
            vibrateLight()
            currentInputConnection?.commitText(" ", 1)
        }
        val dotBtn = createUtilityKey(".", weight = 0.8f) {
            vibrateLight()
            currentInputConnection?.commitText(". ", 1)
        }
        val backspaceBtn = createUtilityKey("⌫", weight = 1.1f) {
            vibrateLight()
            handleBackspace()
        }
        val enterBtn = createUtilityKey("↵", weight = 1.1f, bgColor = "#1E3A5F") {
            vibrateLight()
            handleEnter()
        }

        bottomRow.addView(switchKbBtn)
        bottomRow.addView(commaBtn)
        bottomRow.addView(spaceBtn)
        bottomRow.addView(dotBtn)
        bottomRow.addView(backspaceBtn)
        bottomRow.addView(enterBtn)

        root.addView(bottomRow)

        return root
    }

    private fun onMicButtonTapped() {
        if (isBusy) return

        if (isRecording) {
            stopRecordingAndTranscribe()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            updateStatus("⚠️ Mic permission needed! Open VoiceFlow app.", isError = true)
            return
        }

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            updateStatus("⚠️ API Key missing! Open VoiceFlow app to paste it.", isError = true)
            return
        }

        try {
            val outFile = File(cacheDir, "voiceflow_clip.m4a")
            if (outFile.exists()) outFile.delete()
            audioFile = outFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(64000)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true

            styleButton(micButton, "⏹️  Done Speaking", "#FF1744", "#FF8A80", "#FFFFFF")
            cancelButton.visibility = View.VISIBLE
            updateStatus("🔴 Listening... Speak now, tap Done when finished.")
        } catch (e: Exception) {
            releaseRecorder()
            updateStatus("❌ Could not start mic: ${e.message}", isError = true)
        }
    }

    private fun stopRecordingAndTranscribe() {
        val file = audioFile
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            releaseRecorder()
            resetMicUi()
            updateStatus("⚠️ Recording too short. Speak a little longer.", isError = true)
            return
        }

        releaseRecorder()
        cancelButton.visibility = View.GONE

        if (file == null || !file.exists()) {
            resetMicUi()
            updateStatus("⚠️ Audio clip not found. Try again.", isError = true)
            return
        }

        setBusyState("✨ Transcribing & cleaning...")

        serviceScope.launch {
            val result = GeminiClient.transcribeAndCleanAudio(
                apiKey = getApiKey(),
                audioFile = file,
                languageMode = languageMode
            )
            file.delete()
            resetMicUi()

            result.fold(
                onSuccess = { cleanedText ->
                    if (cleanedText.isBlank()) {
                        updateStatus("No speech detected. Tap mic to try again.")
                    } else {
                        insertTranscribedText(cleanedText)
                        updateStatus("✅ Inserted! Tap any toolbar button above to refine.")
                    }
                },
                onFailure = { err ->
                    updateStatus("⚠️ ${err.message}", isError = true)
                }
            )
        }
    }

    private fun cancelRecording() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }
        releaseRecorder()
        audioFile?.delete()
        resetMicUi()
        updateStatus("Recording cancelled.")
    }

    private fun runToolbarAction(action: GeminiClient.ToolbarAction) {
        if (isBusy || isRecording) return

        val ic = currentInputConnection ?: return
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            updateStatus("⚠️ API Key missing! Open VoiceFlow app first.", isError = true)
            return
        }

        // Check if text is highlighted/selected first; otherwise grab text in the input field
        val selected = ic.getSelectedText(0)?.toString()
        val hasSelection = !selected.isNullOrBlank()

        val beforeCursor = if (!hasSelection) {
            ic.getTextBeforeCursor(2000, 0)?.toString() ?: ""
        } else ""
        val afterCursor = if (!hasSelection) {
            ic.getTextAfterCursor(2000, 0)?.toString() ?: ""
        } else ""

        val targetText = if (hasSelection) selected!! else (beforeCursor + afterCursor)

        if (targetText.isBlank()) {
            updateStatus("⚠️ Speak or select some text first!", isError = true)
            return
        }

        setBusyState("✨ Running '${action.title}'...")

        serviceScope.launch {
            val result = GeminiClient.transformText(
                apiKey = apiKey,
                inputText = targetText,
                action = action
            )
            resetMicUi()

            result.fold(
                onSuccess = { transformed ->
                    if (transformed.isNotBlank()) {
                        previousTextSnapshot = targetText
                        val conn = currentInputConnection
                        if (conn != null) {
                            conn.beginBatchEdit()
                            if (hasSelection) {
                                conn.commitText(transformed, 1)
                            } else {
                                conn.deleteSurroundingText(beforeCursor.length, afterCursor.length)
                                conn.commitText(transformed, 1)
                            }
                            conn.endBatchEdit()
                        }
                        updateStatus("✅ Applied ${action.title}! (Tap Undo to revert)")
                    } else {
                        updateStatus("⚠️ Empty response from AI.", isError = true)
                    }
                },
                onFailure = { err ->
                    updateStatus("⚠️ ${err.message}", isError = true)
                }
            )
        }
    }

    private fun performUndo() {
        val snapshot = previousTextSnapshot
        if (snapshot == null) {
            updateStatus("Nothing to undo yet.")
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(2000, 0)?.length ?: 0
        val after = ic.getTextAfterCursor(2000, 0)?.length ?: 0
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before, after)
        ic.commitText(snapshot, 1)
        ic.endBatchEdit()
        previousTextSnapshot = null
        updateStatus("↩️ Reverted to previous text.")
    }

    private fun insertTranscribedText(text: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        val needsLeadingSpace = before.isNotEmpty() && !before.endsWith(" ") && !before.endsWith("\n")
        val finalToCommit = if (needsLeadingSpace) " $text" else text

        val existingBefore = ic.getTextBeforeCursor(2000, 0)?.toString() ?: ""
        val existingAfter = ic.getTextAfterCursor(2000, 0)?.toString() ?: ""
        previousTextSnapshot = existingBefore + existingAfter

        ic.commitText(finalToCommit, 1)
    }

    private fun cycleLanguageMode() {
        val modes = GeminiClient.LanguageMode.values()
        val nextIndex = (languageMode.ordinal + 1) % modes.size
        languageMode = modes[nextIndex]
        langButton.text = languageMode.label
        updateStatus("Language mode: ${languageMode.label}")
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnterAction = (info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0

        if (!noEnterAction && action in listOf(
                EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_DONE
            )
        ) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun switchToNextKeyboard() {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            false
        }
        if (!switched) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    private fun setBusyState(message: String) {
        isBusy = true
        isRecording = false
        styleButton(micButton, "⏳  Working...", "#37474F", "#546E7A", "#CFD8DC")
        updateStatus(message)
    }

    private fun resetMicUi() {
        isBusy = false
        isRecording = false
        cancelButton.visibility = View.GONE
        styleButton(micButton, "🎙️  Tap to Speak", "#D32F2F", "#FF5252", "#FFFFFF")
    }

    private fun updateStatus(message: String, isError: Boolean = false) {
        statusTextView.text = message
        statusTextView.setTextColor(
            if (isError) Color.parseColor("#FF8A65") else Color.parseColor("#B0B0C0")
        )
    }

    private fun getApiKey(): String {
        val prefs = getSharedPreferences("voiceflow_prefs", Context.MODE_PRIVATE)
        return prefs.getString("gemini_api_key", "")?.trim() ?: ""
    }

    private fun releaseRecorder() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        isRecording = false
    }

    private fun createPillButton(
        text: String,
        bgColor: String,
        strokeColor: String,
        textColor: String,
        textSizeSp: Float,
        padH: Int,
        padV: Int,
        isBold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            if (isBold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
            styleButton(this, text, bgColor, strokeColor, textColor)
        }
    }

    private fun styleButton(
        view: TextView,
        text: String,
        bgColor: String,
        strokeColor: String,
        textColor: String
    ) {
        view.text = text
        view.setTextColor(Color.parseColor(textColor))
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28).toFloat()
            setColor(Color.parseColor(bgColor))
            setStroke(dp(1), Color.parseColor(strokeColor))
        }
    }

    private fun createUtilityKey(
        label: String,
        weight: Float,
        bgColor: String = "#1B1B24",
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(bgColor))
                setStroke(dp(1), Color.parseColor("#2C2C3A"))
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            lp.marginEnd = dp(4)
            lp.marginStart = dp(4)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun vibrateLight() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(18)
            }
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (isRecording) {
            cancelRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseRecorder()
        serviceScope.cancel()
    }
}
