package com.streamcast.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 账号接口客户端。
 * 注意：这些是阻塞调用，必须在后台线程执行（Activity 里用线程池）。
 */
object AuthApi {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Resp(val code: Int, val body: JSONObject, val networkError: String?)

    private fun post(url: String, payload: JSONObject, token: String? = null): Resp {
        return try {
            val builder = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON))
            // 部署网关会覆盖 Authorization 头，统一使用自定义头
            if (token != null) builder.header("X-Auth-Token", token)
            client.newCall(builder.build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    JSONObject()
                }
                Resp(res.code, json, null)
            }
        } catch (e: Exception) {
            Resp(-1, JSONObject(), e.message ?: e.javaClass.simpleName)
        }
    }

    private fun get(url: String, token: String? = null): Resp {
        return try {
            val builder = Request.Builder().url(url).get()
            if (token != null) builder.header("X-Auth-Token", token)
            client.newCall(builder.build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    JSONObject()
                }
                Resp(res.code, json, null)
            }
        } catch (e: Exception) {
            Resp(-1, JSONObject(), e.message ?: e.javaClass.simpleName)
        }
    }

    fun config(base: String): Resp = get("$base/api/auth/config")

    fun sendCode(base: String, email: String): Resp =
        post("$base/api/auth/send-code", JSONObject().put("email", email))

    fun register(base: String, email: String, password: String, code: String): Resp =
        post(
            "$base/api/auth/register",
            JSONObject()
                .put("email", email)
                .put("password", password)
                .put("code", code)
                .put("agreeTerms", true)
        )

    fun login(base: String, email: String, password: String): Resp =
        post("$base/api/auth/login", JSONObject().put("email", email).put("password", password))

    fun me(base: String, token: String): Resp = get("$base/api/auth/me", token)

    fun logout(base: String, token: String): Resp =
        post("$base/api/auth/logout", JSONObject(), token)

    private fun patch(url: String, payload: JSONObject, token: String): Resp {
        return try {
            val builder = Request.Builder().url(url).patch(payload.toString().toRequestBody(JSON))
            builder.header("X-Auth-Token", token)
            client.newCall(builder.build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                Resp(res.code, json, null)
            }
        } catch (e: Exception) {
            Resp(-1, JSONObject(), e.message ?: e.javaClass.simpleName)
        }
    }

    /** 更新资料：nickname / avatar(DataURL) / theme 任选其一 */
    fun updateProfile(base: String, token: String, body: JSONObject): Resp =
        patch("$base/api/auth/profile", body, token)

    fun deleteAccount(base: String, token: String, password: String): Resp =
        post("$base/api/auth/delete-account", JSONObject().put("password", password), token)

    /** 从响应里取错误文案 */
    fun errorOf(resp: Resp): String =
        resp.networkError?.let { "网络异常：$it" }
            ?: resp.body.optString("error").ifEmpty { "请求失败（${resp.code}）" }
}
