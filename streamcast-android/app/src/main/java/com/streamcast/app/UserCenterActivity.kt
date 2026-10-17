package com.streamcast.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.streamcast.app.databinding.ActivityUserCenterBinding
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * 用户中心：头像、昵称、主题、协议链接、退出与注销。
 * 头像以 Base64 DataURL 存库（服务端限制 64KB），上传前裁剪压缩为 200×200 JPEG。
 */
class UserCenterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserCenterBinding
    private val io = Executors.newSingleThreadExecutor()
    private var avatarData: String = ""

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) uploadAvatar(uri)
    }

    private val prefs by lazy { getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE) }
    private val server: String get() = prefs.getString(AppConfig.KEY_SERVER, AppConfig.DEFAULT_SERVER) ?: AppConfig.DEFAULT_SERVER
    private val base: String get() = AppConfig.httpBase(server)
    private val token: String get() = prefs.getString(AppConfig.KEY_TOKEN, "").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityUserCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.tvEmail.text = prefs.getString(AppConfig.KEY_USER_EMAIL, "").orEmpty()
        binding.etNickname.setText(prefs.getString(AppConfig.KEY_NICKNAME, "").orEmpty())
        avatarData = prefs.getString(AppConfig.KEY_AVATAR, "").orEmpty()
        renderAvatar()

        when (ThemeUtil.saved(this)) {
            AppConfig.THEME_DARK -> binding.rbDark.isChecked = true
            AppConfig.THEME_LIGHT -> binding.rbLight.isChecked = true
            else -> binding.rbSystem.isChecked = true
        }

        binding.avatarBox.setOnClickListener { pickImage.launch("image/*") }
        binding.btnSaveNick.setOnClickListener { saveNickname() }

        binding.rgTheme.setOnCheckedChangeListener { _, id ->
            val theme = when (id) {
                R.id.rbDark -> AppConfig.THEME_DARK
                R.id.rbLight -> AppConfig.THEME_LIGHT
                else -> AppConfig.THEME_SYSTEM
            }
            if (theme == ThemeUtil.saved(this)) return@setOnCheckedChangeListener
            ThemeUtil.set(this, theme)
            pushProfile(JSONObject().put("theme", theme), silent = true)
        }

        binding.btnTerms.setOnClickListener { openUrl(AppConfig.termsUrl(server)) }
        binding.btnPrivacy.setOnClickListener { openUrl(AppConfig.privacyUrl(server)) }
        binding.btnRepo.setOnClickListener { openUrl(AppConfig.REPO_URL) }

        binding.btnLogout.setOnClickListener { confirmLogout() }
        binding.btnDelete.setOnClickListener { confirmDelete() }

        refreshProfile()
    }

    // ------------------------------------------------------------ 资料
    private fun refreshProfile() {
        if (token.isEmpty()) return
        io.execute {
            val resp = AuthApi.me(base, token)
            if (resp.code != 200) return@execute
            val u = resp.body.optJSONObject("user") ?: return@execute
            prefs.edit()
                .putString(AppConfig.KEY_NICKNAME, u.optString("nickname"))
                .putString(AppConfig.KEY_AVATAR, u.optString("avatar"))
                .apply()
            runOnUiThread {
                binding.etNickname.setText(u.optString("nickname"))
                avatarData = u.optString("avatar")
                renderAvatar()
            }
        }
    }

    private fun renderAvatar() {
        val bmp = decodeAvatar(avatarData)
        if (bmp != null) {
            binding.ivAvatar.setImageBitmap(bmp)
            binding.tvInitial.visibility = View.GONE
        } else {
            binding.ivAvatar.setImageDrawable(null)
            binding.ivAvatar.setBackgroundResource(R.drawable.bg_avatar)
            binding.tvInitial.visibility = View.VISIBLE
            val nick = prefs.getString(AppConfig.KEY_NICKNAME, "").orEmpty()
            val email = prefs.getString(AppConfig.KEY_USER_EMAIL, "").orEmpty()
            val seed = nick.ifBlank { email }
            binding.tvInitial.text = if (seed.isBlank()) "U" else seed.take(1).uppercase()
        }
    }

    private fun decodeAvatar(data: String): Bitmap? {
        if (data.isBlank()) return null
        return try {
            val comma = data.indexOf(',')
            val payload = if (comma >= 0) data.substring(comma + 1) else data
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun uploadAvatar(uri: Uri) {
        setMsg("正在处理图片…")
        io.execute {
            try {
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("无法读取图片")
                val origin = input.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalStateException("图片格式不支持")
                val side = minOf(origin.width, origin.height)
                val square = Bitmap.createBitmap(origin, (origin.width - side) / 2, (origin.height - side) / 2, side, side)
                val scaled = Bitmap.createScaledBitmap(square, 200, 200, true)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                val dataUrl = "data:image/jpeg;base64,$b64"

                val resp = AuthApi.updateProfile(base, token, JSONObject().put("avatar", dataUrl))
                if (resp.code == 200) {
                    avatarData = dataUrl
                    prefs.edit().putString(AppConfig.KEY_AVATAR, dataUrl).apply()
                    runOnUiThread {
                        renderAvatar()
                        setMsg("头像已更新")
                    }
                } else {
                    val msg = AuthApi.errorOf(resp)
                    runOnUiThread { setMsg(msg) }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "上传失败"
                runOnUiThread { setMsg(msg) }
            }
        }
    }

    private fun saveNickname() {
        val nickname = binding.etNickname.text.toString().trim()
        if (token.isEmpty()) return setMsg("登录已失效，请重新登录")
        setMsg("保存中…")
        pushProfile(JSONObject().put("nickname", nickname)) {
            prefs.edit().putString(AppConfig.KEY_NICKNAME, nickname).apply()
            setMsg("昵称已保存")
            renderAvatar()
        }
    }

    private fun pushProfile(body: JSONObject, silent: Boolean = false, onOk: (() -> Unit)? = null) {
        if (token.isEmpty()) return
        io.execute {
            val resp = AuthApi.updateProfile(base, token, body)
            runOnUiThread {
                if (resp.code == 200) {
                    if (!silent) onOk?.invoke()
                } else if (!silent) {
                    setMsg(AuthApi.errorOf(resp))
                }
            }
        }
    }

    // ------------------------------------------------------------ 账号
    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("退出后需要重新登录才能开播，确定退出？")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ -> doLogout() }
            .show()
    }

    private fun doLogout() {
        if (token.isNotEmpty()) {
            io.execute { AuthApi.logout(base, token) }
        }
        clearSession()
        goLogin()
    }

    private fun confirmDelete() {
        val input = EditText(this).apply {
            hint = "登录密码"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("注销账号")
            .setMessage("将永久删除账号、昵称、头像与全部数据，且无法恢复。请输入密码确认：")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认注销") { _, _ -> doDelete(input.text.toString()) }
            .show()
    }

    private fun doDelete(password: String) {
        if (password.isEmpty()) return setMsg("请输入密码")
        setMsg("正在注销…")
        io.execute {
            val resp = AuthApi.deleteAccount(base, token, password)
            runOnUiThread {
                if (resp.code == 200) {
                    clearSession()
                    Toast.makeText(this, "账号已注销", Toast.LENGTH_SHORT).show()
                    goLogin()
                } else {
                    setMsg(AuthApi.errorOf(resp))
                }
            }
        }
    }

    private fun clearSession() {
        prefs.edit()
            .remove(AppConfig.KEY_TOKEN)
            .remove(AppConfig.KEY_USER_EMAIL)
            .remove(AppConfig.KEY_NICKNAME)
            .remove(AppConfig.KEY_AVATAR)
            .apply()
    }

    private fun goLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            setMsg("无法打开链接")
        }
    }

    private fun setMsg(text: String) {
        if (binding.tvMsg.text.toString() == text) return
        binding.tvMsg.text = text
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
