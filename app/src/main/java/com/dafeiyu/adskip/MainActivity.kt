package com.dafeiyu.adskip

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private data class AppEntry(val pkg: String, val label: String)

    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var recentText: TextView
    private lateinit var rangeText: TextView
    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var switchAggressive: MaterialSwitch
    private lateinit var switchPopupGuard: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        countText = findViewById(R.id.countText)
        recentText = findViewById(R.id.recentText)
        rangeText = findViewById(R.id.rangeText)
        switchEnabled = findViewById(R.id.switchEnabled)
        switchAggressive = findViewById(R.id.switchAggressive)
        switchPopupGuard = findViewById(R.id.switchPopupGuard)

        switchEnabled.isChecked = Prefs.enabled(this)
        switchEnabled.setOnCheckedChangeListener { _, checked -> Prefs.setEnabled(this, checked) }

        switchAggressive.isChecked = Prefs.aggressive(this)
        switchAggressive.setOnCheckedChangeListener { _, checked -> Prefs.setAggressive(this, checked) }

        switchPopupGuard.isChecked = Prefs.popupGuard(this)
        switchPopupGuard.setOnCheckedChangeListener { _, checked -> Prefs.setPopupGuard(this, checked) }

        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            Prefs.resetCount(this)
            refresh()
            Toast.makeText(this, "已清零", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnPickApps).setOnClickListener {
            showAppPicker()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = isServiceEnabled()
        val enabled = Prefs.enabled(this)
        statusText.text = when {
            !on -> "无障碍服务：未开启"
            !enabled -> "无障碍服务：已开启（自动跳过已暂停）"
            else -> "无障碍服务：已开启"
        }
        countText.text = "已跳过：${Prefs.count(this)} 次"
        switchEnabled.isChecked = enabled

        val allowed = Prefs.allowList(this)
        rangeText.text = if (allowed.isEmpty()) {
            "生效范围：全部应用"
        } else {
            "生效范围：已选 ${allowed.size} 个应用"
        }

        val recent = Prefs.recent(this)
        recentText.text = if (recent.isEmpty()) {
            "最近动作：无"
        } else {
            "最近动作：\n" + recent.take(4).joinToString("\n")
        }
    }

    private fun showAppPicker() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != packageName }
            .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()

        if (apps.isEmpty()) {
            Toast.makeText(this, "读不到应用列表", Toast.LENGTH_SHORT).show()
            return
        }

        val chosen = Prefs.allowList(this)
        val checked = BooleanArray(apps.size) { apps[it].pkg in chosen }

        val adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int): Any = apps[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
                val app = apps[position]
                view.findViewById<TextView>(R.id.appName).text = app.label
                view.findViewById<TextView>(R.id.appPkg).text = app.pkg
                view.findViewById<CheckBox>(R.id.appCheck).isChecked = checked[position]
                view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(
                    runCatching { pm.getApplicationIcon(app.pkg) }.getOrNull()
                )
                return view
            }
        }

        val listView = ListView(this)
        listView.adapter = adapter
        val height = (resources.displayMetrics.heightPixels * 0.6).toInt()
        listView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        listView.setOnItemClickListener { _, _, position, _ ->
            checked[position] = !checked[position]
            adapter.notifyDataSetChanged()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("选择应用（不选 = 全部生效）")
            .setView(listView)
            .setPositiveButton("保存") { _, _ ->
                val picked = apps.filterIndexed { i, _ -> checked[i] }.map { it.pkg }.toSet()
                Prefs.setAllowList(this, picked)
                refresh()
                Toast.makeText(
                    this,
                    if (picked.isEmpty()) "全部应用生效" else "已选 ${picked.size} 个应用",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("全清") { _, _ ->
                Prefs.setAllowList(this, emptySet())
                refresh()
            }
            .show()
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, SkipAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (item in splitter) {
            if (item.equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
