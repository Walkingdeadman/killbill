package com.killbill.ai

import android.util.Base64
import com.killbill.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * DashScope OpenAI 兼容模式调用 Qwen-VL。
 * 失败重试 1 次（共 2 次尝试），仍失败返回 Error 由上层降级手动模式。
 */
class QwenVisionClient(private val prefs: Prefs) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 实测：Token Plan 网关带图请求 P90 约 2 分钟（排队+推理），90s 必超时。
        // 用户已确认接受等待，卡片/toast 有"识别中"反馈兜着。
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    sealed class Result {
        data class Ok(val draft: BillDraft) : Result()
        object NotBill : Result()
        data class Error(val msg: String) : Result()
    }

    /** 分析账单截图。jpegBase64 为不含 data: 前缀的裸 base64。 */
    suspend fun analyze(jpegBase64: String): Result {
        val key = prefs.apiKey
        if (key.isBlank()) return Result.Error("未配置 API Key，请在设置页填写")
        val body = buildBody(
            imageBase64 = jpegBase64,
            userText = BillPrompt.USER,
            temperature = 0.0,
        )
        val (content, err) = call(body, key)
        return when {
            content != null -> when (val draft = BillParser.parse(content)) {
                null -> Result.Error("识别结果解析失败: ${content.take(120)}")
                else -> if (draft.isBill) Result.Ok(draft) else Result.NotBill
            }

            else -> Result.Error(err ?: "网络请求失败")
        }
    }

    /** 用户取消时掐死在飞的请求，别让它占着连接池继续熬 */
    fun cancelAll() {
        runCatching { client.dispatcher.cancelAll() }
    }

    /** 设置页连通性测试：发 32x32 像素图，只要 HTTP 200 就算 Key 可用。注意网关慢，可能要等一两分钟。 */
    suspend fun testKey(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val key = prefs.apiKey
        if (key.isBlank()) return@withContext false to "Key 为空"
        val body = buildBody(
            imageBase64 = TINY_PNG_BASE64,
            userText = BillPrompt.TEST_USER,
            temperature = 0.0,
            maxTokens = 8,
            imageMime = "image/png",
        )
        when (val raw = callRaw(body, key)) {
            is RawResult.Success -> true to "连接成功"
            is RawResult.Failure -> false to raw.msg
        }
    }

    private sealed class RawResult {
        data class Success(val content: String) : RawResult()
        data class Failure(val msg: String, val noRetry: Boolean = false) : RawResult()
    }

    /** @return content 到错误信息。超时/4xx 不重试（网关本就慢，重试让用户白等双倍时间） */
    private suspend fun call(body: String, key: String): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            when (val r1 = callRaw(body, key)) {
                is RawResult.Success -> return@withContext r1.content to null
                is RawResult.Failure -> {
                    if (r1.noRetry) return@withContext null to r1.msg
                    when (val r2 = callRaw(body, key)) {
                        is RawResult.Success -> return@withContext r2.content to null
                        is RawResult.Failure -> return@withContext null to r2.msg
                    }
                }
            }
        }

    private fun callRaw(body: String, key: String): RawResult {
        val url = prefs.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // 4xx：Key 不对/参数不对/模型不对，重试没意义
                    val noRetry = resp.code in 400..499
                    return@use RawResult.Failure("HTTP ${resp.code}: ${text.take(200)}", noRetry)
                }
                val content = extractContent(text)
                    ?: return@use RawResult.Failure("响应结构异常: ${text.take(200)}")
                RawResult.Success(content)
            }
        } catch (e: java.net.SocketTimeoutException) {
            RawResult.Failure("网关超时（它家带图请求经常要一两分钟）", noRetry = true)
        } catch (e: Exception) {
            RawResult.Failure(e.message ?: "网络异常")
        }
    }

    /** content 可能是 string，也可能是 [{type:text,text:...}] 数组，两种都兼容 */
    private fun extractContent(respText: String): String? {
        return try {
            val root = json.parseToJsonElement(respText).jsonObject
            val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?: return null
            when (val c = message["content"]) {
                null -> null
                is JsonArray -> c.joinToString("") {
                    (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                }
                else -> c.jsonPrimitive.contentOrNull
            }
        } catch (e: Exception) {
            null
        }
    }

    /** OCR 文本兜底解析：不发图，只发文字行，token 成本约为全图的零头 */
    suspend fun analyzeText(ocrText: String): Result {
        val key = prefs.apiKey
        if (key.isBlank()) return Result.Error("未配置 API Key，请在设置页填写")
        val body = buildBody(
            imageBase64 = null,
            userText = BillPrompt.TEXT_USER + "\n\nOCR 文字：\n" + ocrText.take(4000),
            temperature = 0.0,
        )
        val (content, err) = call(body, key)
        return when {
            content != null -> when (val draft = BillParser.parse(content)) {
                null -> Result.Error("识别结果解析失败: ${content.take(120)}")
                else -> if (draft.isBill) Result.Ok(draft) else Result.NotBill
            }

            else -> Result.Error(err ?: "网络请求失败")
        }
    }

    private fun buildBody(
        imageBase64: String?,
        userText: String,
        temperature: Double,
        maxTokens: Int? = null,
        imageMime: String = "image/jpeg",
    ): String {
        val content = buildJsonArray {
            if (imageBase64 != null) {
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:$imageMime;base64,$imageBase64")
                    })
                })
            }
            add(buildJsonObject {
                put("type", "text")
                put("text", userText)
            })
        }
        val body = buildJsonObject {
            put("model", prefs.model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", BillPrompt.SYSTEM)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", content)
                })
            })
            put("temperature", temperature)
            if (maxTokens != null) put("max_tokens", maxTokens)
            // qwen3 系默认开思考，带图思考动辄数分钟且推理文本污染输出。
            // 实测 enable_thinking=false 后 reasoning 为空、结果正确。
            put("enable_thinking", false)
        }
        return body.toString()
    }

    companion object {
        /**
         * 32x32 灰色 PNG，连通性测试用，几乎不耗 token。
         * 注意：MaaS 网关校验图片最小边长 > 10px，1x1 会被拒（用户实测），别改小。
         */
        const val TINY_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAJklEQVR4nO3NMQ0AAAwDoEqv9KrYsQQMkB6LQCAQCAQCgUAg+BIMLd4AW+JNPdoAAAAASUVORK5CYII="

        fun encodeJpegBase64(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
