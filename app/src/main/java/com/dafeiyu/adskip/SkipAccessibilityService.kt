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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private var blindTapFor = 0L
    private var blindStep = 0
    private var diagFor = 0L
    private var diagCount = 0
    private var actionCount = 0
    private var lastPollAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val inLaunch = now - foregroundAt < launchWindowMs
            scanOnce(inLaunch)
            // 开屏窗口过了就停轮询，省电；有新事件（切应用、弹窗）会自动重新拉起来
            if (inLaunch) handler.postDelayed(this, pollIntervalMs)
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

        // 去掉所有空白（含不换行空格），再剥掉倒计时数字
        var t = k.replace(Regex("[\\s\\u00a0\\u3000]+"), "")
        if (t.length > 8) return false
        t = t.replace("剩余时间", "").replace("剩余", "")
        t = t.trim { it.isDigit() || it == '秒' || it == 's' || it == '|' || it == '·' }

        if (t.isEmpty() || t.length > 6) return false
        return t.startsWith("跳过") || t.startsWith("关闭广告") ||
            t == "skip" || t.startsWith("skip")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (!Prefs.enabled(this) && !Prefs.diag(this)) return

        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg in blockedPkgs) return
        if (!Prefs.isAllowed(this, pkg)) return

        val now = SystemClock.uptimeMillis()
        if (pkg != foregroundPkg) {
            foregroundPkg = pkg
            foregroundAt = now
            lastKey = ""
            actionCount = 0
            restartPoll()
        } else {
            // 任何页面变化都把扫描拉起来一次，弹窗兜底靠这个触发
            restartPoll()
        }
    }

    private fun restartPoll() {
        // 页面事件很密，节流一下，别每个事件都扫一遍
        val now = SystemClock.uptimeMillis()
        if (now - lastPollAt < 400) return
        lastPollAt = now
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    private fun scanOnce(inLaunch: Boolean) {
        val active = Prefs.enabled(this)
        // 关掉自动跳过时仍允许记录页面结构，方便排查
        if (!active && !Prefs.diag(this)) return
        val pkg = foregroundPkg
        if (pkg.isEmpty() || pkg in blockedPkgs) return
        if (!Prefs.isAllowed(this, pkg)) return

        val now = SystemClock.uptimeMillis()
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        // 弹窗兜底：先找 × 之类的关闭键，实在没有才按返回；聊天/支付应用一律不动
        if (active && Prefs.popupGuard(this) && pkg !in popupBlockedPkgs && now - lastBackAt > 5000) {
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
        // 一轮最多动三次手，多了一下就乱点了
        if (actionCount >= 3) return

        // 诊断：每次切前台抓 3 张开屏期间的节点快照（刚进应用时往往还在闪屏）
        if (Prefs.diag(this)) {
            if (diagFor != foregroundAt) {
                diagFor = foregroundAt
                diagCount = 0
            }
            val due = when (diagCount) {
                0 -> true
                1 -> now - foregroundAt > 1200
                2 -> now - foregroundAt > 2500
                3 -> now - foregroundAt > 4000
                4 -> now - foregroundAt > 6000
                else -> false
            }
            if (due) {
                diagCount++
                runCatching { dumpTree(root, pkg, diagCount) }
            }
        }

        // 自动跳过没开时，上面只记录、不动手
        if (!active) return
        if (now - lastActionAt < 1200) return

        val target = findByText(root) ?: if (Prefs.aggressive(this)) findByCorner(root) else null
        if (target != null) {
            if (tryClick(target, pkg) && countNodes(root, 120) > 80) {
                // 点完之后页面节点数暴涨，说明已经进首页了，这一轮别再动手
                actionCount = 3
            }
            return
        }
        // 盲点兜底：按钮是图片、无障碍树里压根没有时，只能按坐标点。
        // 只在「页面几乎没节点」时动手——开屏广告基本就是一张图，正常首页节点很多，免得瞎点。
        if (Prefs.blindTap(this) && now - foregroundAt > 1500 && looksLikeFullScreenImage(root)) {
            if (blindTapFor != foregroundAt) {
                blindTapFor = foregroundAt
                blindStep = 0
            }
            when {
                blindStep == 0 -> { blindStep = 1; blindTap(pkg, 0.885f, 0.105f) }
                blindStep == 1 && now - foregroundAt > 2600 -> {
                    blindStep = 2
                    blindTap(pkg, 0.885f, 0.945f)
                    actionCount++
                }
            }
        }
    }

    /** 数一下窗口里的节点数，超过 cap 就提前返回 cap（省时间）。 */
    private fun countNodes(root: AccessibilityNodeInfo, cap: Int): Int {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var n = 0
        while (queue.isNotEmpty() && n < cap) {
            val node = queue.removeFirst()
            n++
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return n
    }

    /** 开屏广告基本是一整张图，无障碍树里节点很少；正常首页、聊天页节点很多。 */
    private fun looksLikeFullScreenImage(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var n = 0
        while (queue.isNotEmpty() && n < 60) {
            val node = queue.removeFirst()
            n++
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return n < 40
    }

    /** 把当前窗口的节点结构写成文本，排查用。 */
    private fun dumpTree(root: AccessibilityNodeInfo, pkg: String, seq: Int) {
        val dm = resources.displayMetrics
        val sb = StringBuilder()
        sb.append("pkg=").append(pkg).append(" screen=")
            .append(dm.widthPixels).append("x").append(dm.heightPixels).append('\n')

        val rect = Rect()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var n = 0
        while (queue.isNotEmpty() && n < 400) {
            val (node, depth) = queue.removeFirst()
            n++
            node.getBoundsInScreen(rect)
            repeat(minOf(depth, 14)) { sb.append("  ") }
            sb.append('[').append(node.className?.toString()?.substringAfterLast('.') ?: "?").append(']')
            val id = node.viewIdResourceName
            if (!id.isNullOrEmpty()) sb.append(" id=").append(id.substringAfterLast('/'))
            val t = node.text?.toString().orEmpty()
            if (t.isNotEmpty()) sb.append(" text=").append(t)
            val d = node.contentDescription?.toString().orEmpty()
            if (d.isNotEmpty()) sb.append(" desc=").append(d)
            if (node.isClickable) sb.append(" click")
            sb.append(" b=").append(rect.left).append(',').append(rect.top)
                .append(',').append(rect.right).append(',').append(rect.bottom)
            sb.append('\n')
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }

        val dir = getExternalFilesDir(null) ?: filesDir
        dir.mkdirs()
        val ts = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
        File(dir, "diag-$pkg-$ts-$seq.txt").writeText(sb.toString())
        dir.listFiles { f -> f.name.startsWith("diag-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(9)
            ?.forEach { it.delete() }
    }

    /** 右上/右下「跳过」的常见位置，按屏幕比例算，不依赖任何节点。 */
    private fun blindTap(pkg: String, fx: Float, fy: Float) {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * fx
        val y = dm.heightPixels * fy
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, null, null)
        lastActionAt = SystemClock.uptimeMillis()
        Prefs.bumpCount(this)
        Prefs.recordAction(this, pkg)
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
            // 直播、正常页面节点成百上千；这种「全屏弹窗」基本就几个节点，多的一律不算
            if (scanned > 120) return false
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
                val clickable = clickableSelfOrAncestor(node)
                if (clickable != null && isSmallTarget(clickable)) return clickable
                // 文字节点自己很小、但外层是整张 WebView 的情况：直接点文字所在位置
                if (isSmallTarget(node)) return node
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

            if (node.isVisibleToUser) {
                // 有些广告的关闭键本身不可点，可点的是外面一层同样大小的壳
                val target = if (node.isClickable) node else clickableSelfOrAncestor(node)
                if (target != null) {
                    target.getBoundsInScreen(rect)
                    val inCorner = rect.left >= rightStart &&
                        (rect.bottom <= topEnd || rect.top >= bottomStart)
                    val area = rect.width().toLong() * rect.height().toLong()
                    val smallEnough = area in 1..(w.toLong() * h.toLong() / 4)
                    val noRealText = target.text.isNullOrBlank() && target.contentDescription.isNullOrBlank()
                    if (inCorner && smallEnough && noRealText && area < bestArea) {
                        best = target
                        bestArea = area.toInt()
                    }
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
        // 同一个按钮隔 2.5 秒可以再点一次（倒计时的「跳过 5」点一次没反应）
        if (key == lastKey && now - lastActionAt < 2500) return false

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
            actionCount++
            Prefs.bumpCount(this)
            Prefs.recordAction(this, pkg)
        }
        return clicked
    }
}
