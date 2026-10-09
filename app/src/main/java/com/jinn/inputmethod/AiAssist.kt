package com.jinn.inputmethod

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 云端 AI 联想。
 *
 * 复用「翻译设置 → OpenAI 兼容」里已经配好的端点与凭据（[Prefs.openAiBaseUrl] 等），
 * 因此不需要单独的设置页：把翻译的 OpenAI 兼容配置填好，本功能即可用。
 *
 * 用法：提交一个词之后调用 [request]，拿到结果后把候选并进预测位。
 * 请求在单线程池里串行执行，后发请求会作废旧请求的回调（[seq]），
 * 不阻塞输入主线程。
 */
object AiAssist {

    private const val PREFS = "ai_assist"
    private const val KEY_ENABLED = "enabled"

    /** 单次请求最多取几条候选 */
    private const val MAX_CANDIDATES = 3

    private const val SYSTEM_PROMPT =
        "你是输入法的联想引擎。根据用户刚输入的内容，给出最多 3 个自然、简短的中文后续候选。" +
            "每行一个候选，不要编号、不要标点前缀、不要解释、不要引号。与用户输入的语言保持一致。"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jinn-ai-assist").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger(0)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 请求 [text] 的后续候选。[callback] 在主线程回调，只会在结果非空且未被更新请求取代时触发。
     */
    fun request(context: Context, text: String, callback: (List<String>) -> Unit) {
        val input = text.trim()
        if (input.isEmpty() || !isEnabled(context)) return
        val prefs = Prefs(context.applicationContext)
        val baseUrl = prefs.openAiBaseUrl.trim()
        val apiKey = prefs.openAiApiKey.trim()
        val model = prefs.openAiModel.trim()
        if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) return
        val url = joinUrl(baseUrl, prefs.openAiChatPath)
        val mySeq = seq.incrementAndGet()
        executor.execute {
            val result = call(url, apiKey, model, input)
            if (result.isNotEmpty() && seq.get() == mySeq) {
                mainHandler.post { callback(result) }
            }
        }
    }

    /** 作废进行中的请求结果（例如输入状态已被清空） */
    fun cancel() {
        seq.incrementAndGet()
    }

    private fun call(url: String, apiKey: String, model: String, input: String): List<String> {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.4)
            put("max_tokens", 64)
            put(
                "messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", SYSTEM_PROMPT) })
                    put(JSONObject().apply { put("role", "user"); put("content", input) })
                },
            )
        }.toString()
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val text = response.body?.string() ?: return emptyList()
                parse(text)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parse(responseBody: String): List<String> {
        val content = try {
            JSONObject(responseBody)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
        } catch (_: Throwable) {
            null
        } ?: return emptyList()
        return content.lineSequence()
            .map { it.trim().trim('"', '\'', ' ', '-', '*', '·') }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_CANDIDATES)
            .toList()
    }

    private fun joinUrl(baseUrl: String, chatPath: String): String {
        val base = baseUrl.trimEnd('/')
        val path = chatPath.trim()
        if (path.isEmpty()) return "$base/chat/completions"
        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            base + "/" + path.trimStart('/')
        }
    }
}
