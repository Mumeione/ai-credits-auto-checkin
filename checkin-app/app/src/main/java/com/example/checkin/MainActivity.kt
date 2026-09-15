package com.example.checkin

import android.Manifest
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.checkin.data.TokenStore
import com.example.checkin.network.ApiClient
import com.example.checkin.worker.TraeApi
import com.example.checkin.worker.TraeWorker
import com.example.checkin.worker.WorkBuddyWorker
import com.example.checkin.worker.consecutiveCheckinDays
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 单页 UI：顶部固定「标题 + 操作按钮 + 平台分段控件 + 状态条」，中部滚动账号列表与导入区。
 *
 * 视觉约定（所有按钮都由 [button] 生成，保证尺寸一致）：
 * - 所有可点击控件统一 44/46dp 高、12dp 圆角、居中单行文字
 * - 主操作 = PRIMARY（蓝底白字），次操作 = SOFT（浅蓝底），弱操作 = OUTLINE（白底描边），危险 = DANGER（浅红底）
 * - 状态提示不再固定在底部，改为顶部状态条；多行的查询结果改用弹窗展示
 */
class MainActivity : ComponentActivity() {

    // ---------- 视图 ----------
    private lateinit var traePanel: LinearLayout
    private lateinit var wbPanel: LinearLayout
    private lateinit var traeList: LinearLayout
    private lateinit var wbList: LinearLayout
    private lateinit var traeTab: TextView
    private lateinit var wbTab: TextView
    private lateinit var addToggle: TextView
    private lateinit var importBox: LinearLayout
    private lateinit var tokenInput: EditText
    private lateinit var statusBanner: LinearLayout
    private lateinit var statusText: TextView
    private var showTrae = true

    private val handler = Handler(Looper.getMainLooper())

    // ---------- 配色 ----------
    private val accent = Color.parseColor("#4C7DF0")
    private val accentDeep = Color.parseColor("#3557B7")
    private val accentSoft = Color.parseColor("#EDF2FF")
    private val pageBg = Color.parseColor("#F5F7FB")
    private val surface = Color.WHITE
    private val stroke = Color.parseColor("#E6EAF2")
    private val fieldBg = Color.parseColor("#F7F9FC")
    private val segBg = Color.parseColor("#ECEFF5")
    private val textMain = Color.parseColor("#14161A")
    private val textSub = Color.parseColor("#6B7280")
    private val textFaint = Color.parseColor("#9AA3B2")
    private val danger = Color.parseColor("#DC3A40")
    private val dangerSoft = Color.parseColor("#FDECEC")
    private val ok = Color.parseColor("#1F9D55")
    private val okSoft = Color.parseColor("#E8F7EE")

    /** 按钮样式 */
    private enum class Btn { PRIMARY, SOFT, OUTLINE, DANGER }

    /** 状态条语气 */
    private enum class Tone { INFO, OK, ERROR }

    private companion object {
        /** 所有按钮的统一高度，保证视觉上完全一致 */
        const val BTN_H = 46
        const val BTN_H_SUB = 44
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+ 需要运行时申请通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }

        registerPeriodic()
        setContentView(buildUi())
        updateTabs()
        refreshAccountLists()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------- 布局骨架 ----------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBg)
        }

        // ===== 顶部固定区 =====
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(8))
        }

        header.addView(TextView(this).apply {
            text = "自动签到"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textMain)
            includeFontPadding = false
        })
        header.addView(TextView(this).apply {
            text = "Trae · WorkBuddy 每日自动签到"
            textSize = 12.5f
            setTextColor(textSub)
            setPadding(0, dp(5), 0, 0)
        })

        // 主操作：两个签到按钮同排、等宽、等高
        val signRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        signRow.addView(
            button("Trae 签到", Btn.PRIMARY) { runOnce("trae_once", TraeWorker::class.java) },
            lpWeighted(BTN_H, endMarginDp = 8)
        )
        signRow.addView(
            button("WorkBuddy 签到", Btn.PRIMARY) { runOnce("wb_once", WorkBuddyWorker::class.java) },
            lpWeighted(BTN_H)
        )
        header.addView(signRow, lpWrap(topMarginDp = 16))

        // 次操作：查询积分整行，与上方按钮同高
        header.addView(
            button("查询积分", Btn.OUTLINE) { queryCredits() },
            lpMatch(BTN_H_SUB, topMarginDp = 8)
        )

        // 平台分段控件
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(segBg, 12)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        traeTab = segTab("Trae") { showTrae = true; updateTabs() }
        wbTab = segTab("WorkBuddy") { showTrae = false; updateTabs() }
        seg.addView(traeTab, lpWeighted(BTN_H_SUB - 8))
        seg.addView(wbTab, lpWeighted(BTN_H_SUB - 8))
        header.addView(seg, lpWrap(topMarginDp = 14))

        // 状态条（无内容时整体隐藏，不再占用底部空间）
        statusBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        statusText = TextView(this).apply {
            textSize = 12.5f
            setTextColor(textSub)
        }
        statusBanner.addView(
            statusText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(statusBanner, lpWrap(topMarginDp = 12))

        root.addView(header)

        // ===== 中部滚动内容 =====
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(24))
        }

        traeList = newList()
        wbList = newList()
        traePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(traeList) }
        wbPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(wbList) }
        content.addView(traePanel)
        content.addView(wbPanel)

        addToggle = button("＋ 添加账号", Btn.SOFT) { toggleImport() }
        content.addView(addToggle, lpMatch(BTN_H_SUB, topMarginDp = 14))

        tokenInput = EditText(this).apply {
            hint = "粘贴 checkin_auth.json 的全部内容"
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(textMain)
            setHintTextColor(textFaint)
            background = shape(fieldBg, 10, 1, stroke)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        importBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = shape(surface, 14, 1, stroke)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = "导入凭据"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textMain)
                includeFontPadding = false
            })
            addView(tokenInput, lpMatch(104, topMarginDp = 10))
            addView(
                button("确认导入", Btn.PRIMARY) { addAccounts() },
                lpMatch(BTN_H_SUB, topMarginDp = 12)
            )
        }
        content.addView(importBox, lpWrap(topMarginDp = 10))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // targetSdk 35 强制 edge-to-edge，用 systemBars insets 给内容留出状态栏/导航栏空间
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return root
    }

    private fun newList() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    // ---------- 样式工具 ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private fun lpMatch(heightDp: Int, topMarginDp: Int = 0, bottomMarginDp: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)).apply {
            topMargin = dp(topMarginDp)
            bottomMargin = dp(bottomMarginDp)
        }

    private fun lpWeighted(heightDp: Int, weight: Float = 1f, endMarginDp: Int = 0) =
        LinearLayout.LayoutParams(0, dp(heightDp), weight).apply { marginEnd = dp(endMarginDp) }

    private fun lpWrap(topMarginDp: Int = 0, bottomMarginDp: Int = 0) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(topMarginDp)
            bottomMargin = dp(bottomMarginDp)
        }

    private fun shape(
        fillColor: Int,
        radiusDp: Int,
        strokeDp: Int = 0,
        strokeColor: Int = Color.TRANSPARENT,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fillColor)
        cornerRadius = dpf(radiusDp.toFloat())
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

    private fun ripple(
        fillColor: Int,
        radiusDp: Int,
        strokeDp: Int = 0,
        strokeColor: Int = Color.TRANSPARENT,
        rippleColor: Int = 0x1A000000,
    ): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(rippleColor),
        shape(fillColor, radiusDp, strokeDp, strokeColor),
        null,
    )

    /**
     * 统一按钮工厂：TextView + Ripple 自绘，规避系统 Button 默认 minWidth/minHeight
     * 与 inset 带来的尺寸漂移，保证所有按钮严格等高、文字同一字号。
     */
    private fun button(
        label: String,
        kind: Btn,
        sizeSp: Float = 14f,
        onClick: () -> Unit,
    ): TextView {
        val tv = TextView(this).apply {
            text = label
            textSize = sizeSp
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setTypeface(null, Typeface.BOLD)
            setOnClickListener { onClick() }
        }
        when (kind) {
            Btn.PRIMARY -> {
                tv.background = ripple(accent, 12, rippleColor = 0x40FFFFFF)
                tv.setTextColor(Color.WHITE)
            }

            Btn.SOFT -> {
                tv.background = ripple(accentSoft, 12, rippleColor = 0x1F4C7DF0)
                tv.setTextColor(accentDeep)
            }

            Btn.OUTLINE -> {
                tv.background = ripple(
                    surface, 12, strokeDp = 1, strokeColor = stroke, rippleColor = 0x14000000
                )
                tv.setTextColor(accentDeep)
            }

            Btn.DANGER -> {
                tv.background = ripple(dangerSoft, 9, rippleColor = 0x1FDC3A40)
                tv.setTextColor(danger)
            }
        }
        return tv
    }

    private fun segTab(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setOnClickListener { onClick() }
        }

    private fun updateTabs() {
        styleTab(traeTab, showTrae)
        styleTab(wbTab, !showTrae)
        traePanel.visibility = if (showTrae) View.VISIBLE else View.GONE
        wbPanel.visibility = if (!showTrae) View.VISIBLE else View.GONE
    }

    private fun styleTab(tab: TextView, active: Boolean) {
        if (active) {
            tab.background = shape(surface, 10)
            tab.setTextColor(accent)
            tab.setTypeface(null, Typeface.BOLD)
            tab.elevation = dpf(1.5f)
        } else {
            tab.background = null
            tab.setTextColor(textSub)
            tab.setTypeface(null, Typeface.NORMAL)
            tab.elevation = 0f
        }
    }

    private fun emptyHint(msg: String): TextView = TextView(this).apply {
        text = msg
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(textFaint)
        setLineSpacing(dpf(3f), 1f)
        setPadding(0, dp(30), 0, dp(30))
    }

    private fun accountCard(name: String, result: String, onDelete: () -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(surface, 14, 1, stroke)
            setPadding(dp(14), dp(12), dp(10), dp(12))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = name
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textMain)
            includeFontPadding = false
        })
        col.addView(TextView(this).apply {
            text = result.ifBlank { "尚未签到" }
            textSize = 12.5f
            setTextColor(if (result.isBlank()) textFaint else textSub)
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(
            col,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(10) }
        )
        card.addView(
            button("删除", Btn.DANGER, 12f) { onDelete() },
            LinearLayout.LayoutParams(dp(58), dp(32))
        )
        return card
    }

    // ---------- 状态条 ----------

    /**
     * 顶部状态条。[autoHideMs] > 0 时自动收起，仅用于「已提交」「已添加」这类瞬时提示；
     * 错误提示保持常驻，直到下一次操作覆盖。
     */
    private fun setStatus(msg: String, tone: Tone = Tone.INFO, autoHideMs: Long = 0L) {
        handler.removeCallbacksAndMessages(null)
        if (msg.isEmpty()) {
            statusBanner.visibility = View.GONE
            return
        }
        val (bg, fg) = when (tone) {
            Tone.INFO -> accentSoft to accentDeep
            Tone.OK -> okSoft to ok
            Tone.ERROR -> dangerSoft to danger
        }
        statusBanner.background = shape(bg, 10)
        statusText.setTextColor(fg)
        statusText.text = msg
        statusBanner.visibility = View.VISIBLE
        if (autoHideMs > 0) {
            handler.postDelayed({ statusBanner.visibility = View.GONE }, autoHideMs)
        }
    }

    /** 多行查询结果用弹窗展示（内容可长按选中复制），替代原先挤在底部的状态栏。 */
    private fun showResultDialog(title: String, lines: List<String>) {
        val metrics = resources.displayMetrics
        val cardWidth = (metrics.widthPixels * 0.88f).toInt()

        val dlg = Dialog(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(surface, 18)
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textMain)
            includeFontPadding = false
        })

        val body = TextView(this).apply {
            text = lines.joinToString("\n\n").ifBlank { "（无结果）" }
            textSize = 13f
            setTextColor(textMain)
            setLineSpacing(dpf(4f), 1f)
            setTextIsSelectable(true)
        }
        // 先按弹窗可用宽度量一次高度：内容短就贴内容高，内容长则封顶后内部滚动
        body.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth - dp(40), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxBodyHeight = (metrics.heightPixels * 0.45f).toInt()
        val scroll = ScrollView(this).apply {
            addView(body)
            isVerticalScrollBarEnabled = true
        }
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(body.measuredHeight, maxBodyHeight),
        ).apply { topMargin = dp(12) }
        card.addView(scroll)
        card.addView(
            button("关闭", Btn.SOFT) { dlg.dismiss() },
            lpMatch(BTN_H_SUB, topMarginDp = 16)
        )

        dlg.setContentView(card)
        dlg.setCanceledOnTouchOutside(true)
        dlg.show()
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.45f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    // ---------- 账号列表 ----------

    private fun refreshAccountLists() {
        Thread {
            val store = TokenStore(applicationContext)
            val traeAccounts = runBlocking { store.getTraeAccounts() }
            val wbAccounts = runBlocking { store.getWbAccounts() }
            runOnUiThread {
                traeList.removeAllViews()
                if (traeAccounts.isEmpty()) {
                    traeList.addView(emptyHint("还没有 Trae 账号\n点下方「添加账号」导入凭据"))
                }
                traeAccounts.forEach { acc ->
                    traeList.addView(
                        accountCard(acc.name, acc.lastResult) { removeTrae(acc.id) },
                        lpWrap(topMarginDp = 8),
                    )
                }

                wbList.removeAllViews()
                if (wbAccounts.isEmpty()) {
                    wbList.addView(emptyHint("还没有 WorkBuddy 账号\n点下方「添加账号」导入凭据"))
                }
                wbAccounts.forEach { acc ->
                    wbList.addView(
                        accountCard(acc.name, acc.lastResult) { removeWbuddy(acc.id) },
                        lpWrap(topMarginDp = 8),
                    )
                }

                traeTab.text = if (traeAccounts.isEmpty()) "Trae" else "Trae · ${traeAccounts.size}"
                wbTab.text =
                    if (wbAccounts.isEmpty()) "WorkBuddy" else "WorkBuddy · ${wbAccounts.size}"
                updateTabs()
            }
        }.start()
    }

    private fun removeTrae(id: String) {
        Thread {
            runBlocking { TokenStore(applicationContext).removeTraeAccount(id) }
            runOnUiThread { refreshAccountLists() }
        }.start()
    }

    private fun removeWbuddy(id: String) {
        Thread {
            runBlocking { TokenStore(applicationContext).removeWbAccount(id) }
            runOnUiThread { refreshAccountLists() }
        }.start()
    }

    // ---------- 导入 ----------

    private fun toggleImport() {
        val show = importBox.visibility == View.GONE
        importBox.visibility = if (show) View.VISIBLE else View.GONE
        addToggle.text = if (show) "－ 收起导入" else "＋ 添加账号"
        if (show) tokenInput.requestFocus()
    }

    private fun addAccounts() {
        val text = tokenInput.text.toString().trim()
        if (text.isEmpty()) {
            setStatus("输入为空，请先粘贴 checkin_auth.json 的内容", Tone.ERROR)
            return
        }
        Thread {
            try {
                val json = JSONObject(text)
                val store = TokenStore(applicationContext)
                val added = mutableListOf<String>()
                runBlocking {
                    // 兼容 extract_tokens.py 的扁平格式；两平台字段都有时各追加一个账号
                    if (json.has("trae_access")) {
                        val name = store.addTraeAccount(
                            json.optString("trae_access"),
                            json.optString("trae_refresh"),
                            json.optString("trae_device"),
                        )
                        added.add("Trae·$name")
                    }
                    if (json.has("wb_token")) {
                        val name = store.addWbAccount(
                            json.optString("wb_token"),
                            json.optString("wb_domain"),
                        )
                        added.add("WorkBuddy·$name")
                    }
                }
                runOnUiThread {
                    if (added.isEmpty()) {
                        setStatus("JSON 中没有 trae_access / wb_token 字段", Tone.ERROR)
                    } else {
                        tokenInput.setText("")
                        toggleImport()  // 导入成功后收起
                        setStatus(
                            "已添加：${added.joinToString("、")}（相同 Token 会覆盖更新）",
                            Tone.OK,
                            autoHideMs = 4000,
                        )
                        refreshAccountLists()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("解析失败：${e.message}", Tone.ERROR) }
            }
        }.start()
    }

    // ---------- 手动操作 ----------

    private fun runOnce(name: String, worker: Class<out androidx.work.ListenableWorker>) {
        val request = OneTimeWorkRequest.Builder(worker).build()
        WorkManager.getInstance(this).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
        setStatus("已提交签到任务，结果稍后通过通知播报", Tone.INFO, autoHideMs = 4000)
    }

    private fun queryCredits() {
        setStatus("正在查询全部账号，请稍候…", Tone.INFO)
        Thread {
            val lines = mutableListOf<String>()
            try {
                val store = TokenStore(applicationContext)
                runBlocking {
                    // Trae：遍历全部账号
                    val traeAccounts = store.getTraeAccounts()
                    if (traeAccounts.isEmpty()) lines.add("Trae：未导入 Token")
                    traeAccounts.forEach { acc ->
                        if (acc.access.isBlank()) return@forEach
                        try {
                            var api = TraeApi(acc.access, acc.device)
                            var status = api.status()
                            if (status == null) {
                                val renewed = TraeApi.exchangeToken(acc.refresh)
                                if (renewed != null) {
                                    store.updateTraeAccount(
                                        acc.copy(access = renewed.first, refresh = renewed.second)
                                    )
                                    api = TraeApi(renewed.first, acc.device)
                                    status = api.status()
                                }
                            }
                            if (status == null) {
                                lines.add("Trae·${acc.name}：Token 失效，请重新提取")
                                return@forEach
                            }
                            val usage = api.entUsage()
                            val sb = StringBuilder("Trae·${acc.name}：今日已签 ${status.optBoolean("checked_in")}")
                            status.optInt("credits", -1).takeIf { it >= 0 }?.let { sb.append("，今日 +$it") }
                            usage?.let { sb.append("\n    额度已用 ${it.first} / ${it.second}") }
                            lines.add(sb.toString())
                        } catch (e: Exception) {
                            lines.add("Trae·${acc.name}：查询失败 ${e.message}")
                        }
                    }
                    // WorkBuddy：遍历全部账号
                    val wbAccounts = store.getWbAccounts()
                    if (wbAccounts.isEmpty()) lines.add("WorkBuddy：未导入 Token")
                    wbAccounts.forEach { acc ->
                        if (acc.token.isBlank() || acc.domain.isBlank()) return@forEach
                        try {
                            val body = ApiClient.post(
                                "${acc.domain}/v2/billing/meter/checkin-activity-status",
                                "{}", "Bearer ${acc.token}",
                            )
                            val json = JSONObject(body)
                            val data = json.optJSONObject("data")
                            if (json.optInt("code") == 0 && data != null) {
                                lines.add(
                                    "WorkBuddy·${acc.name}：今日已签 ${data.optBoolean("today_checked_in")}" +
                                        "，连续 ${consecutiveCheckinDays(data)} 天" +
                                        "，今日 +${data.optInt("today_credit", 0)}"
                                )
                            } else {
                                lines.add("WorkBuddy·${acc.name}：Token 失效或接口异常 (${json.optInt("code")})")
                            }
                        } catch (e: Exception) {
                            lines.add("WorkBuddy·${acc.name}：查询失败 ${e.message}")
                        }
                    }
                }
                runOnUiThread {
                    setStatus("")
                    showResultDialog("查询结果", lines)
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("查询失败：${e.message}", Tone.ERROR) }
            }
        }.start()
    }

    // ---------- 定时任务 ----------

    private fun registerPeriodic() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        // Trae 签到：每天执行一次（Worker 内部遍历全部账号）
        val traeRequest = PeriodicWorkRequestBuilder<TraeWorker>(
            1, TimeUnit.DAYS, 30, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "trae_checkin",
            ExistingPeriodicWorkPolicy.KEEP,
            traeRequest,
        )

        // WorkBuddy 签到：每天执行一次（Worker 内部遍历全部账号）
        val wbRequest = PeriodicWorkRequestBuilder<WorkBuddyWorker>(
            1, TimeUnit.DAYS, 30, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "workbuddy_checkin",
            ExistingPeriodicWorkPolicy.KEEP,
            wbRequest,
        )
    }
}
