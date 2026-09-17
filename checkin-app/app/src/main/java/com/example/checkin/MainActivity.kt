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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.example.checkin.data.ImportOutcome
import com.example.checkin.data.TokenStore
import com.example.checkin.network.ApiClient
import com.example.checkin.worker.DailySchedule
import com.example.checkin.worker.TraeApi
import com.example.checkin.worker.WorkBuddyApi
import com.example.checkin.worker.TraeStatus
import com.example.checkin.worker.TraeWorker
import com.example.checkin.worker.WorkBuddyWorker
import com.example.checkin.worker.boolOrNull
import com.example.checkin.worker.intOrNull
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

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

        registerDaily()
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
                val results = mutableListOf<String>()
                runBlocking {
                    // 兼容 extract_tokens.py 的扁平格式；两平台字段都有时各处理一个账号
                    if (json.has("trae_access")) {
                        val r = store.addTraeAccount(
                            json.optString("trae_access"),
                            json.optString("trae_refresh"),
                            json.optString("trae_device"),
                        )
                        results.add("${outcomeLabel(r.outcome)} Trae·${r.name}")
                    }
                    if (json.has("wb_token")) {
                        val r = store.addWbAccount(
                            json.optString("wb_token"),
                            json.optString("wb_domain"),
                            json.optString("wb_refresh"),
                        )
                        results.add("${outcomeLabel(r.outcome)} WorkBuddy·${r.name}")
                    }
                }
                runOnUiThread {
                    if (results.isEmpty()) {
                        setStatus("JSON 中没有 trae_access / wb_token 字段", Tone.ERROR)
                    } else {
                        tokenInput.setText("")
                        toggleImport()  // 导入成功后收起
                        setStatus(
                            results.joinToString("、") + "（同一账号自动覆盖更新，编号不变）",
                            Tone.OK,
                            autoHideMs = 5000,
                        )
                        refreshAccountLists()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("解析失败：${e.message}", Tone.ERROR) }
            }
        }.start()
    }

    private fun outcomeLabel(outcome: ImportOutcome): String = when (outcome) {
        ImportOutcome.ADDED -> "已添加"
        ImportOutcome.UPDATED -> "已更新"
    }

    // ---------- 手动操作 ----------

    private fun runOnce(name: String, worker: Class<out androidx.work.ListenableWorker>) {
        // 手动签到不加网络/电量约束（用户点了就是要现在试），并打上 manual 标记：
        // Worker 会把每个账号的结果（含失败）当场通知，不再进退避重试——
        // 否则限流等可重试失败要等 5→10 分钟的退避轮次才有通知，手动时体验很差
        val request = OneTimeWorkRequest.Builder(worker)
            .setInputData(
                androidx.work.Data.Builder()
                    .putBoolean(DailySchedule.KEY_MANUAL, true)
                    .build()
            )
            .build()
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
                            if (status is TraeStatus.AuthFailed) {
                                val renewed = TraeApi.exchangeToken(acc.refresh)
                                if (renewed != null) {
                                    store.updateTraeAccount(
                                        acc.copy(access = renewed.first, refresh = renewed.second)
                                    )
                                    api = TraeApi(renewed.first, acc.device)
                                    status = api.status()
                                }
                            }
                            when (status) {
                                is TraeStatus.AuthFailed -> lines.add(
                                    "Trae·${acc.name}：Token 失效，请在电脑上重跑 run.cmd 重新提取"
                                )

                                is TraeStatus.Failed -> lines.add(
                                    "Trae·${acc.name}：查询失败 " +
                                        status.message.ifBlank { "code=${status.code}" }
                                )

                                is TraeStatus.Ok -> {
                                    val json = status.json
                                    val usage = api.entUsage()
                                    val sb = StringBuilder(
                                        "Trae·${acc.name}：今日已签 ${json.optBoolean("checked_in")}"
                                    )
                                    json.optInt("credits", -1).takeIf { it >= 0 }
                                        ?.let { sb.append("，今日 +$it") }
                                    usage?.let { sb.append("\n    额度已用 ${it.first} / ${it.second}") }
                                    lines.add(sb.toString())
                                }
                            }
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
                            // 鉴权失败先自动续期再重试一次（判定口径与定时签到共用
                            // WorkBuddyApi.isAuthFailure 的同一份实现），别急着报「Token 失效」
                            var current = acc
                            var resp = ApiClient.postRaw(
                                "${current.domain}/v2/billing/meter/checkin-activity-status",
                                "{}", "Bearer ${current.token}",
                            )
                            var json = WorkBuddyApi.parseJson(resp.body)
                            var auth = WorkBuddyApi.isAuthFailure(resp, json)
                            if (auth) {
                                val renewed = WorkBuddyApi.refreshToken(current.refresh)
                                if (renewed != null) {
                                    current = current.copy(token = renewed.first, refresh = renewed.second)
                                    store.updateWbAccount(current)
                                    resp = ApiClient.postRaw(
                                        "${current.domain}/v2/billing/meter/checkin-activity-status",
                                        "{}", "Bearer ${current.token}",
                                    )
                                    json = WorkBuddyApi.parseJson(resp.body)
                                    // 续期后仍判鉴权失败，才算真的「Token 失效」；
                                    // 续期本身失败（返回 null）时保持 auth，同样属实
                                    auth = WorkBuddyApi.isAuthFailure(resp, json)
                                }
                            }
                            val wbJson = json
                            val data = wbJson?.optJSONObject("data")
                            // 判定顺序刻意是「先鉴权、再解析」：HTTP 401 且响应体为空时
                            // json 是 null，若先看 json 就会报成「响应不是合法 JSON」，
                            // 把真正的 Token 失效说错。
                            if (auth) {
                                lines.add(
                                    "WorkBuddy·${current.name}：" +
                                        (if (current.refresh.isBlank())
                                            "Token 失效，且未导入 refreshToken（重跑 run.cmd 后重新粘贴即可启用自动续期）"
                                        else "Token 失效，自动刷新被拒" +
                                            "（code=${wbJson?.optInt("code") ?: resp.code}），" +
                                            "请在电脑上重新运行 run.cmd 提取")
                                )
                            } else if (wbJson == null) {
                                lines.add(
                                    "WorkBuddy·${current.name}：查询失败 服务端响应不是合法 JSON" +
                                        "（HTTP ${resp.code}）"
                                )
                            } else if (wbJson.optInt("code") == 0 && data != null) {
                                val checkedToday = data.optBoolean("today_checked_in")
                                // 这里**不再查成长中心**：它的 `streak.days` 是「连续登录 PC 端」
                                // 的天数（2026-09-17 用户更正），当「连续签到」用是错的，已整体删除；
                                // 签到侧只剩本期赛季累计 `streak_days`，见 [wbStatusLine]。
                                lines.add(wbStatusLine(current.name, checkedToday, data))
                            } else {
                                // 非鉴权失败（活动已结束/未开始、限流、5xx…）：如实报服务端给的原因。
                                // 以前这里一律说「Token 失效，自动刷新被拒」，可这些情况
                                // 压根没触发续期——那样报是拿错误结论把用户往「重新提取」上推，
                                // 与当初把 Trae 9074 当成 Token 失效是同一类毛病。
                                lines.add(
                                    "WorkBuddy·${current.name}：查询失败 " +
                                        wbJson.optString("msg").ifBlank { wbJson.optString("message") }
                                            .ifBlank { "服务端返回 code=${wbJson.optInt("code")}" } +
                                        "（HTTP ${resp.code}）"
                                )
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

    // ---------- 查询结果文案 ----------

    /**
     * 单账号「查询积分」的结果文案（换行分段由结果弹窗按 `\n` 渲染）。
     *
     * 服务端字段一律用 [intOrNull] / [boolOrNull] 的「有才显示」读法：
     * 这些字段都是社区逆向出来的，缺失时宁可少显示一行，也不要拿默认值冒充真实值——
     * 否则会把「服务端没返回这个字段」显示成「累计 0 积分」「今天不是连签奖励日」。
     *
     * ⚠️ 这里**不显示「连续 N 天」**：成长中心的 `streak.days` 是**连续登录 PC 端**的天数
     * （2026-09-17 用户更正），拿它当连续签到是错的，那次调用已整体删除；
     * 签到接口自己只有 `streak_days`（**本期赛季累计**，换期归零，是否断签清零未实测），
     * 所以在下面如实标成「本期累计」，别包装成「连续」。
     */
    private fun wbStatusLine(
        name: String,
        checkedToday: Boolean,
        data: JSONObject,
    ): String {
        val sb = StringBuilder("WorkBuddy·$name：今日已签 $checkedToday")
        // today_credit 与 daily_credit 是同一件事的两个名字，服务端只会回其中一个
        (data.intOrNull("today_credit") ?: data.intOrNull("daily_credit"))
            ?.let { sb.append("，今日 +$it") }

        val activity = data.optString("activity_name").ifBlank { "-" }
        val serverStreak = data.intOrNull("streak_days")
        sb.append(
            if (serverStreak != null) "\n    服务端本期累计 $serverStreak 天（活动：$activity）"
            else "\n    活动：$activity"
        )

        val extra = mutableListOf<String>()
        // 实测（2026-09-17）total_credits=200 恰为 streak_days(2) × daily_credit(100)，
        // 即它是**本期活动**口径、不是账号历史总积分。所以必须写明「本期」——
        // 笼统写「累计」会像社区脚本那样，让人误以为这是账号的总资产。
        data.intOrNull("total_credits")?.let { extra.add("本期累计 $it 积分") }
        data.boolOrNull("is_streak_day")
            ?.let { extra.add(if (it) "今天是连签奖励日" else "今天非连签奖励日") }
        // 实测本期活动返回 0，语义是「本期没有下一档奖励日」而不是「第 0 天」，故只在 > 0 时提示
        data.intOrNull("next_streak_day")?.takeIf { it > 0 }
            ?.let { extra.add("下次奖励在第 $it 天") }
        if (extra.isNotEmpty()) sb.append("\n    ").append(extra.joinToString(" · "))

        return sb.toString()
    }

    // ---------- 定时任务 ----------

    /**
     * 每日签到排程：东八区 08:00 起、08:50 前随机错峰执行（详见 [DailySchedule]）。
     *
     * 顺带做两件事：
     * 1. 取消 1.2.0 之前注册的两个周期任务，否则新旧两套排程会同时跑；
     * 2. `ensureScheduled` 里带「兜底补签」——只在当天落点（最晚 08:50）已过、且今天
     *    **一次都没跑过**时补跑一次；今天跑过（哪怕失败）就不再补，避免每次开 App
     *    都重发一整条重试链。
     *
     * 整体放到后台线程：enqueue 与 `alreadyHandled` 里的 `getWorkInfos...get()`
     * 都是阻塞的数据库操作，在 onCreate（主线程）直接调会触发 StrictMode / ANR 风险。
     * 开启 App 后排程晚几毫秒落地没有影响，排程本身是幂等的。
     */
    private fun registerDaily() {
        Thread {
            DailySchedule.cancelLegacyPeriodic(this)
            DailySchedule.ensureScheduled(this, DailySchedule.WORK_TRAE, TraeWorker::class.java)
            DailySchedule.ensureScheduled(this, DailySchedule.WORK_WB, WorkBuddyWorker::class.java)
        }.start()
    }
}
