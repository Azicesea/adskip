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

    /** 最近点掉过的应用，最多 8 条，格式「HH:mm:ss 包名」。 */
    fun recent(ctx: Context): List<String> =
        sp(ctx).getString(KEY_RECENT, "").orEmpty().split(';').filter { it.isNotBlank() }

    fun recordAction(ctx: Context, pkg: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val list = (listOf("$stamp  $pkg") + recent(ctx)).take(8)
        sp(ctx).edit().putString(KEY_RECENT, list.joinToString(";")).apply()
    }
}
