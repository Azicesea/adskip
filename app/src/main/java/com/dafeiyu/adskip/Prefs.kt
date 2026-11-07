package com.dafeiyu.adskip

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 极简本地存储：开关、计数、包名白名单。全部存在 SharedPreferences，不联网。 */
object Prefs {
    private const val FILE = "adskip"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_COUNT = "skip_count"
    private const val KEY_ALLOW = "allow_list"
    private const val KEY_AGGRESSIVE = "aggressive"
    private const val KEY_RECENT = "recent_actions"
    private const val KEY_POPUP_GUARD = "popup_guard"
    private const val KEY_BLIND = "blind_tap"
    private const val KEY_DIAG = "diag"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun count(ctx: Context): Int = sp(ctx).getInt(KEY_COUNT, 0)

    fun bumpCount(ctx: Context) {
        val cur = count(ctx)
        sp(ctx).edit().putInt(KEY_COUNT, cur + 1).apply()
    }

    fun resetCount(ctx: Context) {
        sp(ctx).edit().putInt(KEY_COUNT, 0).apply()
    }

    /** 空集合 = 全部应用生效。 */
    fun allowList(ctx: Context): Set<String> =
        sp(ctx).getStringSet(KEY_ALLOW, emptySet()) ?: emptySet()

    fun setAllowList(ctx: Context, list: Set<String>) {
        sp(ctx).edit().putStringSet(KEY_ALLOW, list).apply()
    }

    fun isAllowed(ctx: Context, pkg: String): Boolean {
        val list = allowList(ctx)
        return list.isEmpty() || list.contains(pkg)
    }

    /** 激进模式：找不到「跳过」文字时，去试右上/右下角无文字的关闭控件。默认关。 */
    fun aggressive(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AGGRESSIVE, false)

    fun setAggressive(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AGGRESSIVE, value).apply()
    }

    /** 弹窗兜底：认出「红包已到账 + ¥数字 + 開」这类没关闭键的弹窗时按返回。默认开。 */
    fun popupGuard(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_POPUP_GUARD, true)

    fun setPopupGuard(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_POPUP_GUARD, value).apply()
    }

    /** 盲点：开屏 4 秒还没跳掉，就按固定坐标点右上角那块（有些广告的「跳过」是图，无障碍拿不到）。默认关。 */
    fun blindTap(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_BLIND, false)

    fun setBlindTap(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_BLIND, value).apply()
    }

    /** 诊断：每次应用切前台把当时的节点结构存一份，排查「为什么这个广告跳不掉」。默认关。 */
    fun diag(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_DIAG, false)

    fun setDiag(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_DIAG, value).apply()
    }

    /** 最近点掉过的应用，最多 8 条，格式「HH:mm:ss 包名」。 */
    fun recent(ctx: Context): List<String> =
        sp(ctx).getString(KEY_RECENT, "").orEmpty().split(';').filter { it.isNotBlank() }

    fun recordAction(ctx: Context, pkg: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val list = (listOf("$stamp  $pkg") + recent(ctx)).take(8)
        sp(ctx).edit().putString(KEY_RECENT, list.joinToString(";")).apply()
    }
}
