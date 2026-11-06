package com.dafeiyu.adskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 极简跳广告。
 *  - 应用切到前台后 20 秒内每 400ms 主动扫一次（不等事件，广告按钮晚渲染也能抓到）；
 *  - 系统界面（设置、桌面、状态栏等）一律不碰；
 *  - 单次最多看 500 个节点；
 *  - 角落点击（无文字的关闭键）默认关闭，需要手动打开「激进模式」。
 */
class SkipAccessibilityService : AccessibilityService() {

    private var lastActionAt = 0L
    private var lastKey = ""
    private var foregroundPkg = ""
    private var foregroundAt = 0L

    private val launchWindowMs = 20000L
    private val pollIntervalMs = 400L
    private val idleIntervalMs = 1500L
    private var lastBackAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val inLaunch = now - foregroundAt < launchWindowMs
            scanOnce(inLaunch)
            handler.postDelayed(this, if (inLaunch) pollIntervalMs else idleIntervalMs)
        }
    }

    /** 聊天、支付类应用永远不按返回，防止把正常消息顶掉。 */
    private val popupBlockedPkgs = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.eg.android.AlipayGphone",
        "com.alibaba.android.rimet",
        "com.tencent.wework"
    )

    private val blockedPkgs = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.sec.android.app.launcher",
        "com.android.inputmethod.latin",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.securitycenter",
        "com.android.providers.downloads.ui"
    )

    private val skipWords = listOf("跳过广告", "跳过", "关闭广告", "skipads", "skipad", "skip")

    private fun looksLikeSkip(raw: String): Boolean {
        val k = raw.lowercase()
        // 文件名、聊天里的文字容易带「跳过」，一律不当按钮
        if (k.contains(".apk") || k.contains(".mp4") || k.contains(".zip") ||
            k.contains(".jpg") || k.contains(".png") || k.contains(".pdf")
        ) return false

        var t = k.replace(" ", "").replace("\u3000", "")
        if (t.length > 8) return false
        t = t.removePrefix("剩余").removePrefix("剩余时间")
        t = t.trim { it.isDigit() || it == '秒' || it == 's' }

        if (t.isEmpty() || t.length > 6) return false
        return skipWords.any { t == it } || (t.length <= 6 && skipWords.any { it.startsWith(t) || t.startsWith(it) })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (!Prefs.enabled(this)) return

        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg in blockedPkgs) return
        if (!Prefs.isAllowed(this, pkg)) return

        val now = SystemClock.uptimeMillis()
        if (pkg != foregroundPkg) {
            foregroundPkg = pkg
            foregroundAt = now
            lastKey = ""
            restartPoll()
        } else if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            restartPoll()
        }
    }

    private fun restartPoll() {
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    private fun scanOnce(inLaunch: Boolean) {
        if (!Prefs.enabled(this)) return
        val pkg = foregroundPkg
        if (pkg.isEmpty() || pkg in blockedPkgs) return
        if (!Prefs.isAllowed(this, pkg)) return

        val now = SystemClock.uptimeMillis()
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        // 弹窗兜底：先找 × 之类的关闭键，实在没有才按返回；聊天/支付应用一律不动
        if (Prefs.popupGuard(this) && pkg !in popupBlockedPkgs && now - lastBackAt > 5000) {
            if (looksLikeNoClosePopup(root)) {
                val close = findPopupClose(root)
                if (close != null && tryClick(close, pkg)) return
                performGlobalAction(GLOBAL_ACTION_BACK)
                lastBackAt = now
                Prefs.bumpCount(this)
                Prefs.recordAction(this, pkg)
                return
            }
        }

        if (!inLaunch) return
        if (now - lastActionAt < 1200) return

        val target = findByText(root) ?: if (Prefs.aggressive(this)) findByCorner(root) else null
        if (target != null) tryClick(target, pkg)
    }

    /**
     * 在弹窗里找「关闭」类控件：文字是 ×/✕/关闭/close 的优先，其次小面积无文字可点控件。
     * 中间那个「開」是大圆按钮，靠尺寸自然被排除。
     */
    private fun findPopupClose(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val maxW = (w * 0.12f).toInt()
        val maxH = (h * 0.08f).toInt()
        val rect = Rect()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        var strong: AccessibilityNodeInfo? = null
        var weak: AccessibilityNodeInfo? = null
        var weakScore = Int.MAX_VALUE

        while (queue.isNotEmpty() && scanned < 500) {
            val node = queue.removeFirst()
            scanned++

            val label = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
                .replace(" ", "").lowercase()
            val isCloseLabel = label == "×" || label == "✕" || label == "x" ||
                label == "关闭" || label == "close" || label == "跳过" || label == "关闭广告"

            if (node.isClickable && node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                val area = rect.width().toLong() * rect.height().toLong()
                val small = area in 1..(w.toLong() * h.toLong() / 40)   // < 屏 2.5%
                val fitsButton = rect.width() <= maxW && rect.height() <= maxH
                if (isCloseLabel) {
                    strong = node
                } else if (small && fitsButton && label.isEmpty() && weak == null) {
                    // 距右上角越近越像关闭键
                    val score = (w - rect.right) + rect.top
                    if (score < weakScore) { weakScore = score; weak = node }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return strong ?: weak
    }

    /**
     * 认出「红包已到账 / ¥10 起 / 開 按钮」这种全屏弹窗。
     * 三个特征同时出现才算，避免把正常页面顶掉。
     */
    private fun looksLikeNoClosePopup(root: AccessibilityNodeInfo): Boolean {
        var hasOpen = false
        var hasMoney = false
        var hasRed = false

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < 400) {
            val node = queue.removeFirst()
            scanned++
            val raw = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty()).trim()
            if (raw.isNotEmpty()) {
                val t = raw.replace(" ", "").replace("\u3000", "")
                if (t == "開" || t == "开") hasOpen = true
                if (t.contains("¥") || t.contains("￥")) hasMoney = true
                if (t.contains("红包") || t.contains("到账")) hasRed = true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return hasOpen && hasMoney && hasRed
    }

    private fun findByText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < 500) {
            val node = queue.removeFirst()
            scanned++

            val text = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
                .trim().lowercase()
            if (text.isNotEmpty() && looksLikeSkip(text)) {
                clickableSelfOrAncestor(node)?.let { if (isSmallTarget(it)) return it }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** 跳过按钮一般很小；整块卡片、列表项这类大控件不当按钮，避免误点。 */
    private fun isSmallTarget(node: AccessibilityNodeInfo): Boolean {
        val dm = resources.displayMetrics
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        if (rect.width() > dm.widthPixels * 0.55f) return false
        if (rect.height() > dm.heightPixels * 0.12f) return false
        return true
    }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 5) {
            if (cur.isClickable && cur.isVisibleToUser) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun findByCorner(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val rightStart = (w * 0.72f).toInt()
        val topEnd = (h * 0.32f).toInt()
        val bottomStart = (h * 0.66f).toInt()
        val rect = Rect()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        var best: AccessibilityNodeInfo? = null
        var bestArea = Int.MAX_VALUE

        while (queue.isNotEmpty() && scanned < 500) {
            val node = queue.removeFirst()
            scanned++

            if (node.isClickable && node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                val inCorner = rect.left >= rightStart &&
                    (rect.bottom <= topEnd || rect.top >= bottomStart)
                val area = rect.width().toLong() * rect.height().toLong()
                val smallEnough = area in 1..(w.toLong() * h.toLong() / 4)
                val noRealText = node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank()
                if (inCorner && smallEnough && noRealText && area < bestArea) {
                    best = node
                    bestArea = area.toInt()
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    private fun tryClick(node: AccessibilityNodeInfo, pkg: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastActionAt < 1200) return false

        val key = pkg + "|" + node.viewIdResourceName + "|" + node.text + "|" + node.contentDescription
        // 同一个按钮在一次启动窗口里只点一次，点不掉也不再反复戳
        if (key == lastKey) return false

        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                    .build()
                dispatchGesture(gesture, null, null)
            }
        }

        lastActionAt = now
        lastKey = key
        if (clicked) {
            Prefs.bumpCount(this)
            Prefs.recordAction(this, pkg)
        }
        return clicked
    }
}
