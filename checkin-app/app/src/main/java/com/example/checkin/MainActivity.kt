package com.example.checkin

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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

class MainActivity : ComponentActivity() {
    private lateinit var tokenInput: EditText
    private lateinit var statusText: TextView

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
    }

    private fun buildUi(): ScrollView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        tokenInput = EditText(this).apply {
            hint = "粘贴 extract_tokens.py 导出的 checkin_auth.json 内容"
            minLines = 6
            gravity = Gravity.TOP
        }
        statusText = TextView(this).apply { setPadding(0, pad, 0, 0) }

        box.addView(TextView(this).apply { text = "Token 导入 (JSON)" })
        box.addView(tokenInput)
        box.addView(Button(this).apply {
            text = "保存 Token"
            setOnClickListener { saveTokens() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad })
        box.addView(Button(this).apply {
            text = "手动签到 Trae"
            setOnClickListener { runOnce("trae_once", TraeWorker::class.java) }
        })
        box.addView(Button(this).apply {
            text = "手动签到 WorkBuddy"
            setOnClickListener { runOnce("wb_once", WorkBuddyWorker::class.java) }
        })
        box.addView(Button(this).apply {
            text = "查询积分"
            setOnClickListener { queryCredits() }
        })
        box.addView(statusText)

        // targetSdk 35 强制 edge-to-edge，用 systemBars insets 给内容留出状态栏/导航栏空间
        return ScrollView(this).apply {
            addView(box)
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }
    }

    private fun saveTokens() {
        val text = tokenInput.text.toString().trim()
        if (text.isEmpty()) {
            statusText.text = "输入为空"
            return
        }
        Thread {
            try {
                val json = JSONObject(text)
                val store = TokenStore(applicationContext)
                runBlocking {
                    // 兼容 extract_tokens.py 的扁平格式
                    if (json.has("trae_access")) {
                        store.saveTraeToken(
                            json.optString("trae_access"),
                            json.optString("trae_refresh"),
                            json.optString("trae_device")
                        )
                    }
                    if (json.has("wb_token")) {
                        store.saveWorkBuddyToken(
                            json.optString("wb_token"),
                            json.optString("wb_domain")
                        )
                    }
                }
                runOnUiThread {
                    statusText.text = "Token 已保存"
                    Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "解析失败: ${e.message}" }
            }
        }.start()
    }

    private fun runOnce(name: String, worker: Class<out androidx.work.ListenableWorker>) {
        val request = OneTimeWorkRequest.Builder(worker).build()
        WorkManager.getInstance(this).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
        Toast.makeText(this, "已提交，请留意通知", Toast.LENGTH_SHORT).show()
    }

    private fun queryCredits() {
        statusText.text = "查询中..."
        Thread {
            val lines = mutableListOf<String>()
            try {
                val store = TokenStore(applicationContext)
                runBlocking {
                    // Trae 积分
                    if (store.getTraeAccess().isNotBlank()) {
                        val api = TraeApi(store)
                        try {
                            val status = api.status()
                            if (status != null) {
                                val usage = api.entUsage()
                                val sb = StringBuilder("Trae: 今日已签 ${status.optBoolean("checked_in")}")
                                status.optInt("credits", -1).takeIf { it >= 0 }?.let { sb.append("，今日 +$it") }
                                usage?.let { sb.append("\n  额度已用 ${it.first} / ${it.second}") }
                                lines.add(sb.toString())
                            } else if (api.refreshAccess() && api.status() != null) {
                                lines.add("Trae: Token 已自动刷新，请重新查询")
                            } else {
                                lines.add("Trae: Token 失效，请重新提取")
                            }
                        } catch (e: Exception) {
                            lines.add("Trae: 查询失败 ${e.message}")
                        }
                    } else {
                        lines.add("Trae: 未导入 Token")
                    }
                    // WorkBuddy 积分
                    val (token, domain) = store.getWorkBuddyToken()
                    if (token.isNotBlank() && domain.isNotBlank()) {
                        try {
                            val body = ApiClient.post(
                                "$domain/v2/billing/meter/checkin-activity-status", "{}", "Bearer $token"
                            )
                            val json = JSONObject(body)
                            val data = json.optJSONObject("data")
                            if (json.optInt("code") == 0 && data != null) {
                                lines.add(
                                    "WorkBuddy: 今日已签 ${data.optBoolean("today_checked_in")}" +
                                        "，连续 ${consecutiveCheckinDays(data)} 天" +
                                        "，今日 +${data.optInt("today_credit", 0)}"
                                )
                            } else {
                                lines.add("WorkBuddy: Token 失效或接口异常 (${json.optInt("code")})")
                            }
                        } catch (e: Exception) {
                            lines.add("WorkBuddy: 查询失败 ${e.message}")
                        }
                    } else {
                        lines.add("WorkBuddy: 未导入 Token")
                    }
                }
                runOnUiThread { statusText.text = lines.joinToString("\n") }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "查询失败: ${e.message}" }
            }
        }.start()
    }

    private fun registerPeriodic() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        // Trae 签到：每天执行一次
        val traeRequest = PeriodicWorkRequestBuilder<TraeWorker>(
            1, TimeUnit.DAYS, 30, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "trae_checkin",
            ExistingPeriodicWorkPolicy.KEEP,
            traeRequest
        )

        // WorkBuddy 签到：每天执行一次
        val wbRequest = PeriodicWorkRequestBuilder<WorkBuddyWorker>(
            1, TimeUnit.DAYS, 30, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "workbuddy_checkin",
            ExistingPeriodicWorkPolicy.KEEP,
            wbRequest
        )
    }
}
