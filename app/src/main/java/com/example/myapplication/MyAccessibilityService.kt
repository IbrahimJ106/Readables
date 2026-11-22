package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class MyAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var isTtsInitialized = false
    private var lastSpokenText = ""

    var pendingAppLaunchRead = false

    companion object {
        private const val TAG = "AccessibilityDebug"
        var instance: MyAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tts = TextToSpeech(this, this)
        Log.d(TAG, "Accessibility Service Connected")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS Language not supported")
            } else {
                isTtsInitialized = true
                Log.d(TAG, "TTS Initialized Successfully")
            }
        } else {
            Log.e(TAG, "TTS Initialization Failed")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: ""

        // Wait for the new app window to appear after voice command launch
        if (pendingAppLaunchRead && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (packageName.isNotBlank() && packageName != this.packageName) {
                pendingAppLaunchRead = false
                Log.d(TAG, "Target app window detected ($packageName), reading screen now.")
                speakCurrentScreen()
            }
        }

        val rootNode = rootInActiveWindow ?: return
        val textList = mutableListOf<String>()
        extractText(rootNode, textList)

        val cleanedText = textList
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(". ")

        if (cleanedText.isNotBlank() && cleanedText != lastSpokenText) {
            lastSpokenText = cleanedText
        }
    }

    private fun extractText(node: AccessibilityNodeInfo, textList: MutableList<String>) {
        node.text?.let { text ->
            if (text.isNotBlank()) {
                textList.add(text.toString())
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                extractText(child, textList)
            }
        }
    }

    fun speakCurrentScreen() {
        val allWindows = windows
        var targetRoot: AccessibilityNodeInfo? = null
        val myPackage = packageName

        if (allWindows != null) {
            for (window in allWindows) {
                val root = window.root ?: continue
                val windowPackage = root.packageName?.toString()
                if (windowPackage != null && windowPackage != myPackage) {
                    targetRoot = root
                    Log.d(TAG, "Found target app window: $windowPackage")
                    break
                }
            }
        }

        val rootNode = targetRoot ?: rootInActiveWindow
        if (rootNode == null) {
            speakText("No active window found")
            return
        }

        val textList = mutableListOf<String>()
        extractText(rootNode, textList)

        val cleanedText = textList
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(". ")

        if (cleanedText.isBlank()) {
            speakText("No readable text found on this screen.")
            return
        }

        val limitedText = if (cleanedText.length > 400) {
            cleanedText.substring(0, 400) + ". Text truncated."
        } else {
            cleanedText
        }

        speakText(limitedText)
    }

    fun speakText(text: String) {
        if (isTtsInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            Log.d(TAG, "Speaking: $text")
        } else {
            Log.w(TAG, "TTS not ready yet")
        }
    }

    fun stopSpeech() {
        if (isTtsInitialized) {
            tts.stop()
            Log.d(TAG, "Speech stopped")
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted")
    }
}
