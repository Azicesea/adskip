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
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
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
    private lateinit var switchBlind: MaterialSwitch
    private lateinit var switchDiag: MaterialSwitch

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
        switchBlind = findViewById(R.id.switchBlind)
        switchDiag = findViewById(R.id.switchDiag)

        switchEnabled.isChecked = Prefs.enabled(this)
        switchEnabled.setOnCheckedChangeListener { _, checked -> Prefs.setEnabled(this, checked) }

        switchAggressive.isChecked = Prefs.aggressive(this)
        switchAggressive.setOnCheckedChangeListener { _, checked -> Prefs.setAggressive(this, checked) }

        switchPopupGuard.isChecked = Prefs.popupGuard(this)
        switchPopupGuard.setOnCheckedChangeListener { _, checked -> Prefs.setPopupGuard(this, checked) }

        switchBlind.isChecked = Prefs.blindTap(this)
        switchBlind.setOnCheckedChangeListener { _, checked -> Prefs.setBlindTap(this, checked) }

        switchDiag.isChecked = Prefs.diag(this)
        switchDiag.setOnCheckedChangeListener { _, checked -> Prefs.setDiag(this, checked) }

        findViewById<MaterialButton>(R.id.btnDiag).setOnClickListener { shareDiag() }

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
            val pm = packageManager
            val names = allowed.take(6).mapNotNull { pkg ->
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            }
            val more = if (allowed.size > names.size) " 等 ${allowed.size} 个" else ""
            "已选 ${allowed.size} 个：${names.joinToString("、")}$more"
        }

        val recent = Prefs.recent(this)
        recentText.text = if (recent.isEmpty()) {
            "最近动作：无"
        } else {
            "最近动作：\n" + recent.take(4).joinToString("\n")
        }
    }

    /** 把最近三次诊断文件一起分享出去（微信/QQ/邮箱都行）。 */
    private fun shareDiag() {
        val dir = getExternalFilesDir(null)
        val files = dir?.listFiles { f -> f.name.startsWith("diag-") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(3)
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(this, "还没有诊断记录，先打开「记录页面结构」再用几次应用", Toast.LENGTH_LONG).show()
            return
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        Toast.makeText(this, "共 ${files.size} 份，最新：${files.last().name}", Toast.LENGTH_LONG).show()
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.putExtra(Intent.EXTRA_SUBJECT, files.last().name)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "发送诊断文件"))
    }

    private fun showAppPicker() {
        val pm = packageManager
        val all = pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != packageName }
            .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()

        if (all.isEmpty()) {
            Toast.makeText(this, "读不到应用列表", Toast.LENGTH_SHORT).show()
            return
        }

        val chosen = Prefs.allowList(this).toMutableSet()
        var shown: List<AppEntry> = all

        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
                val app = shown[position]
                view.findViewById<TextView>(R.id.appName).text = app.label
                view.findViewById<TextView>(R.id.appPkg).text = app.pkg
                view.findViewById<CheckBox>(R.id.appCheck).isChecked = app.pkg in chosen
                view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(
                    runCatching { pm.getApplicationIcon(app.pkg) }.getOrNull()
                )
                return view
            }
        }

        val search = EditText(this).apply {
            hint = "搜索应用名或包名"
            setSingleLine()
            setPadding(40, 20, 40, 20)
        }

        val listView = ListView(this)
        listView.adapter = adapter
        listView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.52).toInt()
        )
        listView.setOnItemClickListener { _, _, position, _ ->
            val pkg = shown[position].pkg
            if (!chosen.add(pkg)) chosen.remove(pkg)
            adapter.notifyDataSetChanged()
        }

        search.doAfterTextChanged { text ->
            val q = text?.toString()?.trim()?.lowercase().orEmpty()
            shown = if (q.isEmpty()) all else all.filter {
                it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
            }
            adapter.notifyDataSetChanged()
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search)
            addView(listView)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("选择应用（不选 = 全部生效）")
            .setView(container)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("全清", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                Prefs.setAllowList(this, chosen)
                refresh()
                Toast.makeText(
                    this,
                    if (chosen.isEmpty()) "全部应用生效" else "已选 ${chosen.size} 个应用",
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                chosen.clear()
                adapter.notifyDataSetChanged()
                Toast.makeText(this, "已全部取消，点保存生效", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
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
