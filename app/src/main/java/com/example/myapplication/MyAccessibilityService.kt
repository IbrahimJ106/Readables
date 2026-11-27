package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

data class ClickableItem(val index: Int, val label: String, val node: AccessibilityNodeInfo)

private sealed class WhatsAppAction {
    data class OpenChat(val name: String) : WhatsAppAction()
    object ReadRecentChats : WhatsAppAction()
}

private data class ChatRowData(val name: String, val preview: String)

class MyAccessibilityService : AccessibilityService(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var isTtsInitialized = false
    private val handler = Handler(Looper.getMainLooper())

    private var lastVolumeUpTime: Long = 0L
    private var isDoublePressConsumed: Boolean = false

    private var lastClickableItems: List<ClickableItem> = emptyList()

    @Volatile
    private var pendingConfirmationNode: AccessibilityNodeInfo? = null
    @Volatile
    private var pendingConfirmationLabel: String? = null

    @Volatile
    private var pendingCallNumber: String? = null
    @Volatile
    private var pendingCallLabel: String? = null

    @Volatile
    private var isWaitingForDialerWindow: Boolean = false

    @Volatile
    private var pendingTargetPackage: String? = null

    @Volatile
    private var pendingWhatsAppAction: WhatsAppAction? = null
    @Volatile
    private var isWaitingForWhatsAppWindow: Boolean = false

    private val timeoutRunnable = Runnable {
        val target = pendingTargetPackage
        if (target != null) {
            Log.w(TAG, "Timeout waiting for TYPE_WINDOW_STATE_CHANGED from $target")
            pendingTargetPackage = null
            verifyAndReadTargetApp(target, System.currentTimeMillis())
        }
    }

    private val dialerTimeoutRunnable = Runnable {
        if (isWaitingForDialerWindow) {
            Log.w(TAG, "Timeout waiting for dialer window state change event")
            isWaitingForDialerWindow = false
            findAndClickCallButton(System.currentTimeMillis())
        }
    }

    private val whatsAppTimeoutRunnable = Runnable {
        if (isWaitingForWhatsAppWindow || pendingWhatsAppAction != null) {
            Log.w(TAG, "Timeout waiting for WhatsApp window state change event")
            val action = pendingWhatsAppAction
            pendingWhatsAppAction = null
            isWaitingForWhatsAppWindow = false
            if (action != null) {
                executeWhatsAppAction(action, System.currentTimeMillis())
            }
        }
    }

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

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val action = event.action
            val currentTime = System.currentTimeMillis()

            if (action == KeyEvent.ACTION_DOWN) {
                if (currentTime - lastVolumeUpTime < 600) {
                    // Volume Up double press detected within 600 ms
                    lastVolumeUpTime = 0L
                    isDoublePressConsumed = true
                    Log.d(TAG, "Volume Up double-press detected. Triggering assistant.")

                    // 1. Speak "Listening" via TTS
                    speakText("Listening")

                    // 2. Launch ListenActivity transparent overlay
                    val listenIntent = Intent(this, ListenActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(listenIntent)

                    return true // Consume second volume up press
                } else {
                    lastVolumeUpTime = currentTime
                    isDoublePressConsumed = false
                    return false // Let single volume up press through normally
                }
            } else if (action == KeyEvent.ACTION_UP) {
                if (isDoublePressConsumed) {
                    isDoublePressConsumed = false
                    return true // Consume UP event for second press
                }
            }
        }
        return super.onKeyEvent(event)
    }

    fun setPendingTargetPackage(packageName: String) {
        pendingTargetPackage = packageName
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 5000)
        Log.d(TAG, "Pending target package set: $packageName")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString() ?: ""

        // 1. Handle WhatsApp window state change event
        if (isWaitingForWhatsAppWindow && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (eventPackage == "com.whatsapp") {
                Log.d(TAG, "WhatsApp window state changed detected")
                isWaitingForWhatsAppWindow = false
                handler.removeCallbacks(whatsAppTimeoutRunnable)

                val action = pendingWhatsAppAction
                pendingWhatsAppAction = null

                // Wait ~500 ms for WhatsApp UI views to inflate
                handler.postDelayed({
                    if (action != null) {
                        executeWhatsAppAction(action, System.currentTimeMillis())
                    }
                }, 500)
                return
            }
        }

        // 2. Handle dialer window state change event
        if (isWaitingForDialerWindow && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (eventPackage != packageName) {
                Log.d(TAG, "Dialer window state changed detected for package: $eventPackage")
                isWaitingForDialerWindow = false
                handler.removeCallbacks(dialerTimeoutRunnable)

                // Wait ~300 ms for dialer UI views to inflate, then search and click Call button
                handler.postDelayed({
                    findAndClickCallButton(System.currentTimeMillis())
                }, 300)
                return
            }
        }

        // 3. Handle target package window state change event
        val targetPackage = pendingTargetPackage ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && eventPackage == targetPackage) {
            Log.d(TAG, "Target app window state changed detected for $targetPackage")

            pendingTargetPackage = null
            handler.removeCallbacks(timeoutRunnable)

            // Wait ~700 ms for content to load, then verify and read
            handler.postDelayed({
                verifyAndReadTargetApp(targetPackage, System.currentTimeMillis())
            }, 700)
        }
    }

    private fun getValidRootNode(): AccessibilityNodeInfo? {
        val myPackage = packageName
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && activeRoot.isVisibleToUser && activeRoot.packageName?.toString() != myPackage) {
            return activeRoot
        }
        try {
            val windowList = windows
            val candidateWindows = windowList.filter { window ->
                val root = window.root
                root != null && root.isVisibleToUser && root.packageName?.toString() != myPackage
            }

            val bestWindow = candidateWindows.firstOrNull { it.isFocused }
                ?: candidateWindows.firstOrNull { it.isActive }
                ?: candidateWindows.firstOrNull()

            if (bestWindow?.root != null) {
                return bestWindow.root
            }
        } catch (_: Exception) {
            // Fallback to active root
        }
        return activeRoot
    }

    private fun verifyAndReadTargetApp(targetPackage: String, startTimeMs: Long) {
        val rootNode = getValidRootNode()
        val currentPackage = rootNode?.packageName?.toString()

        if (rootNode != null && currentPackage == targetPackage) {
            Log.d(TAG, "Confirmed active window matches target package $targetPackage. Reading screen.")
            readAndSpeakNode(rootNode)
        } else {
            val elapsedTime = System.currentTimeMillis() - startTimeMs
            if (elapsedTime < 3000) {
                Log.d(TAG, "Active window ($currentPackage) != target ($targetPackage). Retrying in 300ms (elapsed: ${elapsedTime}ms)...")
                handler.postDelayed({
                    verifyAndReadTargetApp(targetPackage, startTimeMs)
                }, 300)
            } else {
                Log.w(TAG, "Failed to switch active window to $targetPackage within timeout.")
                speakText("I couldn't read that app")
            }
        }
    }

    private fun readAndSpeakNode(rootNode: AccessibilityNodeInfo) {
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

    private fun extractText(node: AccessibilityNodeInfo, textList: MutableList<String>) {
        if (!node.isVisibleToUser) return

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

    // --- Global Actions & Device Navigation ---

    fun performBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        speakText("Going back")
    }

    fun performHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        speakText("Going home")
    }

    // --- WhatsApp Integration ---

    fun openWhatsAppChat(targetName: String) {
        launchWhatsAppWithAction(WhatsAppAction.OpenChat(targetName))
    }

    fun readRecentWhatsAppChats() {
        launchWhatsAppWithAction(WhatsAppAction.ReadRecentChats)
    }

    private fun launchWhatsAppWithAction(action: WhatsAppAction) {
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.whatsapp")
        if (launchIntent == null) {
            speakText("WhatsApp is not installed on this device.")
            return
        }

        pendingWhatsAppAction = action

        val root = getValidRootNode()
        if (root != null && root.packageName?.toString() == "com.whatsapp") {
            Log.d(TAG, "WhatsApp is already active window. Executing action.")
            handler.postDelayed({
                executeWhatsAppAction(action, System.currentTimeMillis())
            }, 400)
            return
        }

        isWaitingForWhatsAppWindow = true
        handler.removeCallbacks(whatsAppTimeoutRunnable)
        handler.postDelayed(whatsAppTimeoutRunnable, 5000)

        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launchIntent)
            Log.d(TAG, "Launched WhatsApp app")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch WhatsApp", e)
            isWaitingForWhatsAppWindow = false
            pendingWhatsAppAction = null
            handler.removeCallbacks(whatsAppTimeoutRunnable)
            speakText("I couldn't open WhatsApp")
        }
    }

    private fun executeWhatsAppAction(action: WhatsAppAction, startTimeMs: Long) {
        val rootNode = getValidRootNode()
        val currentPackage = rootNode?.packageName?.toString()

        if (rootNode != null && currentPackage == "com.whatsapp") {
            when (action) {
                is WhatsAppAction.OpenChat -> performFindAndOpenWhatsAppChat(rootNode, action.name)
                is WhatsAppAction.ReadRecentChats -> performReadRecentWhatsAppChats(rootNode)
            }
        } else {
            val elapsedTime = System.currentTimeMillis() - startTimeMs
            if (elapsedTime < 3500) {
                handler.postDelayed({
                    executeWhatsAppAction(action, startTimeMs)
                }, 300)
            } else {
                Log.w(TAG, "Failed to confirm WhatsApp window within timeout.")
                when (action) {
                    is WhatsAppAction.OpenChat -> speakText("I couldn't find that chat on screen.")
                    is WhatsAppAction.ReadRecentChats -> speakText("No recent chats found on screen.")
                }
            }
        }
    }

    private fun performFindAndOpenWhatsAppChat(rootNode: AccessibilityNodeInfo, targetName: String) {
        val matchedItems = findMatchingWhatsAppChatNodes(rootNode, targetName)

        if (matchedItems.isEmpty()) {
            speakText("I couldn't find that chat on screen.")
        } else if (matchedItems.size == 1) {
            val (_, clickableNode) = matchedItems[0]
            val clicked = performClickNode(clickableNode)
            if (clicked) {
                speakText("Opened chat with $targetName")
            } else {
                speakText("I couldn't open that chat.")
            }
        } else {
            val items = matchedItems.mapIndexed { index, (label, node) ->
                ClickableItem(index + 1, label, node)
            }
            lastClickableItems = items
            val count = items.size
            val listText = items.joinToString(", ") { "${it.index}. ${it.label}" }
            speakText("Found $count chats matching $targetName. $listText. Which number?")
        }
    }

    private fun findMatchingWhatsAppChatNodes(
        rootNode: AccessibilityNodeInfo,
        targetName: String
    ): List<Pair<String, AccessibilityNodeInfo>> {
        val candidates = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        collectAllNodesWithText(rootNode, candidates)

        val results = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        val seenParents = mutableSetOf<AccessibilityNodeInfo>()

        for ((nodeText, node) in candidates) {
            if (isExcludedHeaderOrTab(nodeText)) continue

            if (matchesName(nodeText, targetName)) {
                val clickableParent = findNearestClickableParent(node)
                if (!seenParents.contains(clickableParent)) {
                    seenParents.add(clickableParent)
                    val rowLabel = getChatRowLabel(clickableParent, nodeText)
                    results.add(Pair(rowLabel, clickableParent))
                }
            }
        }

        return results
    }

    private fun matchesName(nodeText: String, targetName: String): Boolean {
        if (nodeText.isBlank() || targetName.isBlank()) return false

        val normText = nodeText.trim().lowercase().replace("\\s+".toRegex(), " ")
        val normTarget = targetName.trim().lowercase().replace("\\s+".toRegex(), " ")

        if (normText.contains(normTarget)) return true

        val noSpaceText = normText.replace(" ", "")
        val noSpaceTarget = normTarget.replace(" ", "")
        return noSpaceText.contains(noSpaceTarget)
    }

    private fun isExcludedHeaderOrTab(text: String): Boolean {
        val lower = text.trim().lowercase()
        val headers = setOf(
            "whatsapp", "chats", "status", "calls", "communities",
            "search", "search...", "more options", "camera", "new chat",
            "settings", "archived", "unread", "meta ai"
        )
        return headers.contains(lower)
    }

    private fun getChatRowLabel(rowNode: AccessibilityNodeInfo, fallbackLabel: String): String {
        val texts = mutableListOf<String>()
        collectAllTextFromNode(rowNode, texts)
        for (text in texts) {
            val trimmed = text.trim()
            if (trimmed.isNotBlank() && !isExcludedHeaderOrTab(trimmed) && !isTimestampOrUnreadBadge(trimmed)) {
                return trimmed
            }
        }
        return fallbackLabel
    }

    private fun isTimestampOrUnreadBadge(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.all { it.isDigit() }) return true
        if (trimmed.endsWith("PM", ignoreCase = true) || trimmed.endsWith("AM", ignoreCase = true)) return true
        val lower = trimmed.lowercase()
        if (lower == "yesterday" || lower == "today" || lower == "monday" || lower == "tuesday" ||
            lower == "wednesday" || lower == "thursday" || lower == "friday" || lower == "saturday" || lower == "sunday") {
            return true
        }
        if (trimmed.matches("^\\d{1,2}[:/.]\\d{2}.*".toRegex())) return true
        return false
    }

    private fun performReadRecentWhatsAppChats(rootNode: AccessibilityNodeInfo) {
        val chatRows = extractRecentChatRows(rootNode)

        if (chatRows.isEmpty()) {
            speakText("No recent chats found on screen.")
            return
        }

        val formattedList = chatRows.take(5).mapIndexed { idx, row ->
            if (row.preview.isNotBlank()) {
                "${idx + 1}. ${row.name}, preview: ${row.preview}"
            } else {
                "${idx + 1}. ${row.name}"
            }
        }.joinToString(". ")

        speakText("Recent chats: $formattedList")
    }

    private fun extractRecentChatRows(rootNode: AccessibilityNodeInfo): List<ChatRowData> {
        val rowNodes = mutableListOf<AccessibilityNodeInfo>()

        val listContainer = findListContainer(rootNode)
        if (listContainer != null && listContainer.childCount > 0) {
            for (i in 0 until listContainer.childCount) {
                val child = listContainer.getChild(i) ?: continue
                if (child.isVisibleToUser) {
                    rowNodes.add(child)
                }
            }
        }

        if (rowNodes.isEmpty()) {
            collectRowContainers(rootNode, rowNodes)
        }

        val results = mutableListOf<ChatRowData>()
        val seenNames = mutableSetOf<String>()

        for (rowNode in rowNodes) {
            val texts = mutableListOf<String>()
            collectAllTextFromNode(rowNode, texts)

            val cleanedTexts = texts
                .map { it.trim() }
                .filter { it.isNotBlank() && !isExcludedHeaderOrTab(it) }

            if (cleanedTexts.isEmpty()) continue

            val name = cleanedTexts.first()
            val lowerName = name.lowercase()
            if (seenNames.contains(lowerName)) continue
            seenNames.add(lowerName)

            val preview = cleanedTexts.drop(1).firstOrNull { text ->
                !isTimestampOrUnreadBadge(text)
            } ?: ""

            results.add(ChatRowData(name, preview))
            if (results.size >= 5) break
        }

        return results
    }

    private fun findListContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null
        val className = node.className?.toString() ?: ""
        if (className.contains("RecyclerView", ignoreCase = true) || className.contains("ListView", ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findListContainer(child)
            if (found != null) return found
        }
        return null
    }

    private fun collectRowContainers(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (!node.isVisibleToUser) return
        val textCount = countTextDescendants(node)
        if (textCount >= 1 && isInteractiveNode(node)) {
            list.add(node)
            return
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectRowContainers(child, list)
        }
    }

    private fun countTextDescendants(node: AccessibilityNodeInfo): Int {
        if (!node.isVisibleToUser) return 0
        var count = 0
        if ((node.text?.isNotBlank() == true) || (node.contentDescription?.isNotBlank() == true)) {
            count++
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            count += countTextDescendants(child)
        }
        return count
    }

    private fun collectAllTextFromNode(node: AccessibilityNodeInfo, list: MutableList<String>) {
        if (!node.isVisibleToUser) return
        node.text?.let { text ->
            if (text.isNotBlank()) {
                list.add(text.toString())
            }
        }
        node.contentDescription?.let { desc ->
            if (desc.isNotBlank() && desc.toString() != node.text?.toString()) {
                list.add(desc.toString())
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTextFromNode(child, list)
        }
    }

    // --- Calling & Dialer Integration ---

    fun setPendingCall(label: String, number: String) {
        pendingCallLabel = label
        pendingCallNumber = number
        speakText("Calling $label. Say yes to continue")
    }

    fun hasPendingCall(): Boolean = pendingCallNumber != null

    fun confirmPendingCall(context: Context) {
        val number = pendingCallNumber
        val label = pendingCallLabel ?: number ?: "contact"
        pendingCallNumber = null
        pendingCallLabel = null

        if (number.isNullOrBlank()) {
            speakText("No call pending")
            return
        }

        isWaitingForDialerWindow = true
        handler.removeCallbacks(dialerTimeoutRunnable)
        handler.postDelayed(dialerTimeoutRunnable, 5000)

        try {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            Log.d(TAG, "Launched dialer for number: $number ($label)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch dialer", e)
            isWaitingForDialerWindow = false
            handler.removeCallbacks(dialerTimeoutRunnable)
            speakText("I couldn't open the dialer")
        }
    }

    private fun findAndClickCallButton(startTimeMs: Long) {
        val rootNode = getValidRootNode()
        if (rootNode != null) {
            val callNode = findCallOrDialNode(rootNode)
            if (callNode != null) {
                val clickableParent = findNearestClickableParent(callNode)
                val success = performClickNode(clickableParent)
                if (success) {
                    Log.d(TAG, "Successfully clicked call button")
                    return
                }
            }
        }

        val elapsedTime = System.currentTimeMillis() - startTimeMs
        if (elapsedTime < 3000) {
            Log.d(TAG, "Call/Dial button not found yet, retrying in 300ms (elapsed: ${elapsedTime}ms)...")
            handler.postDelayed({
                findAndClickCallButton(startTimeMs)
            }, 300)
        } else {
            Log.w(TAG, "Call/Dial button not found or click failed in dialer window")
            speakText("I couldn't press the call button")
        }
    }

    private fun findCallOrDialNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(rootNode, candidates)

        val matchingNodes = candidates.filter { node ->
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            text.contains("call") || text.contains("dial") || desc.contains("call") || desc.contains("dial")
        }

        if (matchingNodes.isEmpty()) return null

        val exactMatch = matchingNodes.firstOrNull { node ->
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            text == "call" || text == "dial" || desc == "call" || desc == "dial"
        }
        if (exactMatch != null) return exactMatch

        val startsWithMatch = matchingNodes.firstOrNull { node ->
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            text.startsWith("call") || text.startsWith("dial") || desc.startsWith("call") || desc.startsWith("dial")
        }
        if (startsWithMatch != null) return startsWithMatch

        return matchingNodes.first()
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (!node.isVisibleToUser) return
        list.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodes(child, list)
        }
    }

    // --- Clickable Items & Safety System ---

    fun hasLastClickableItems(): Boolean = lastClickableItems.isNotEmpty()

    fun listClickableItems() {
        lastClickableItems = emptyList()

        val root = getValidRootNode()
        if (root == null) {
            speakText("No active screen found")
            return
        }

        val candidates = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        collectNodesWithText(root, candidates)

        val items = mutableListOf<ClickableItem>()
        val seenLabels = mutableSetOf<String>()
        val seenNodes = mutableSetOf<AccessibilityNodeInfo>()
        var count = 1

        for ((label, node) in candidates) {
            val cleanLabel = label.trim()
            if (cleanLabel.isBlank()) continue

            val targetNode = findNearestClickableParent(node)
            val lowerLabel = cleanLabel.lowercase()

            if (!seenLabels.contains(lowerLabel) && !seenNodes.contains(targetNode)) {
                seenLabels.add(lowerLabel)
                seenNodes.add(targetNode)
                items.add(ClickableItem(count, cleanLabel, targetNode))
                count++
                if (items.size >= 25) break
            }
        }

        lastClickableItems = items

        if (items.isEmpty()) {
            speakText("No clickable items found on screen.")
            return
        }

        val listText = items.joinToString(", ") { "${it.index}. ${it.label}" }
        speakText("You can click: $listText")
    }

    private fun collectNodesWithText(
        node: AccessibilityNodeInfo,
        results: MutableList<Pair<String, AccessibilityNodeInfo>>
    ) {
        if (!node.isVisibleToUser) return

        val label = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        if (label.isNotBlank()) {
            results.add(Pair(label, node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodesWithText(child, results)
        }
    }

    private fun findNearestClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (isInteractiveNode(current)) {
                return current
            }
            current = current.parent
        }
        return node
    }

    private fun isInteractiveNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        if (node.isClickable || node.isFocusable) return true
        for (action in node.actionList) {
            if (action.id == AccessibilityNodeInfo.ACTION_CLICK) return true
        }
        return false
    }

    fun hasPendingConfirmation(): Boolean = pendingConfirmationNode != null || pendingCallNumber != null

    fun confirmPendingClick(): Boolean {
        val node = pendingConfirmationNode
        val label = pendingConfirmationLabel ?: "item"
        pendingConfirmationNode = null
        pendingConfirmationLabel = null

        if (node != null) {
            val clicked = performClickNode(node)
            if (clicked) {
                speakText("Clicked $label")
            } else {
                speakText("Failed to click $label")
            }
            return true
        }
        return false
    }

    fun cancelPendingConfirmation() {
        pendingConfirmationNode = null
        pendingConfirmationLabel = null
        pendingCallNumber = null
        pendingCallLabel = null
    }

    fun clickItemByNumber(number: Int): Boolean {
        val item = lastClickableItems.find { it.index == number }
        if (item != null) {
            processClickWithSafety(item.node, item.label)
            return true
        }
        return false
    }

    fun clickItemByLabel(targetLabel: String): Boolean {
        val matchedItem = lastClickableItems.find {
            it.label.lowercase().contains(targetLabel.lowercase()) || targetLabel.lowercase().contains(it.label.lowercase())
        }
        if (matchedItem != null) {
            processClickWithSafety(matchedItem.node, matchedItem.label)
            return true
        }

        val root = getValidRootNode()
        val matchedNode = findNodeByLabel(root, targetLabel)
        if (matchedNode != null) {
            val label = matchedNode.text?.toString() ?: matchedNode.contentDescription?.toString() ?: targetLabel
            val clickableNode = findNearestClickableParent(matchedNode)
            processClickWithSafety(clickableNode, label)
            return true
        }

        return false
    }

    private fun processClickWithSafety(node: AccessibilityNodeInfo, label: String) {
        if (isSensitiveAction(label)) {
            pendingConfirmationNode = node
            pendingConfirmationLabel = label
            speakText("You requested to click $label. Say yes to continue.")
        } else {
            val clicked = performClickNode(node)
            if (clicked) {
                speakText("Clicked $label")
            } else {
                speakText("Failed to click $label")
            }
        }
    }

    private fun isSensitiveAction(text: String): Boolean {
        val lower = text.lowercase()
        val sensitiveKeywords = listOf("pay", "send", "transfer", "confirm", "delete", "buy", "purchase")
        return sensitiveKeywords.any { keyword -> lower.contains(keyword) }
    }

    private fun performClickNode(targetNode: AccessibilityNodeInfo): Boolean {
        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var current = targetNode.parent
        while (current != null) {
            if (isInteractiveNode(current)) {
                val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) return true
            }
            current = current.parent
        }
        return false
    }

    private fun findNodeByLabel(rootNode: AccessibilityNodeInfo?, targetLabel: String): AccessibilityNodeInfo? {
        if (rootNode == null || targetLabel.isBlank()) return null

        val candidates = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        collectAllNodesWithText(rootNode, candidates)

        val lowerTarget = targetLabel.lowercase()

        for ((label, node) in candidates) {
            if (label.lowercase() == lowerTarget) {
                return node
            }
        }

        for ((label, node) in candidates) {
            if (label.lowercase().contains(lowerTarget) || lowerTarget.contains(label.lowercase())) {
                return node
            }
        }

        return null
    }

    private fun collectAllNodesWithText(
        node: AccessibilityNodeInfo,
        results: MutableList<Pair<String, AccessibilityNodeInfo>>
    ) {
        if (!node.isVisibleToUser) return

        val label = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        if (label.isNotBlank()) {
            results.add(Pair(label, node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodesWithText(child, results)
        }
    }

    // --- Service Helpers ---

    fun speakCurrentScreen() {
        val rootNode = getValidRootNode()
        if (rootNode == null) {
            speakText("No active screen found")
            return
        }
        readAndSpeakNode(rootNode)
    }

    fun speakText(text: String) {
        if (isTtsInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
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

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(dialerTimeoutRunnable)
        handler.removeCallbacks(whatsAppTimeoutRunnable)
        pendingWhatsAppAction = null
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
