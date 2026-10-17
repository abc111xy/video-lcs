package com.streamcast.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.streamcast.app.databinding.ActivityLoginBinding
import java.util.concurrent.Executors

/**
 * 登录 / 注册。开播需要账号，观看不需要。
 * 所有网络请求走后台线程，UI 更新回主线程。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var server = AppConfig.DEFAULT_SERVER
    private var registerMode = false
    private var countdown = 0
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE)
        server = prefs.getString(AppConfig.KEY_SERVER, AppConfig.DEFAULT_SERVER)
            ?: AppConfig.DEFAULT_SERVER
        binding.tvServer.text = "服务地址：${AppConfig.httpBase(server)}"

        binding.tabLogin.setOnClickListener { switchMode(false) }
        binding.tabRegister.setOnClickListener { switchMode(true) }

        binding.btnLogin.setOnClickListener { doLogin() }
        binding.btnRegister.setOnClickListener { doRegister() }
        binding.btnSendCode.setOnClickListener { doSendCode() }

        binding.tvPolicyLinks.setOnClickListener { showPolicyPicker() }
        binding.btnSkip.setOnClickListener {
            startActivity(Intent(this, ReceiverActivity::class.java))
            finish()
        }

        switchMode(false)
        loadMailConfig()

        // 已有登录态则直接进入控制台
        val token = prefs.getString(AppConfig.KEY_TOKEN, "").orEmpty()
        if (token.isNotEmpty()) {
            executor.execute {
                val resp = AuthApi.me(AppConfig.httpBase(server), token)
                onMain {
                    if (resp.code == 200) {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ UI 辅助
    private fun switchMode(register: Boolean) {
        registerMode = register
        binding.paneLogin.visibility = if (register) View.GONE else View.VISIBLE
        binding.paneRegister.visibility = if (register) View.VISIBLE else View.GONE

        val onBg = R.drawable.bg_chip_on
        val offBg = R.drawable.bg_chip
        binding.tabLogin.setBackgroundResource(if (register) offBg else onBg)
        binding.tabRegister.setBackgroundResource(if (register) onBg else offBg)
        binding.tabLogin.setTextColor(
            ContextCompat.getColor(this, if (register) R.color.muted else R.color.white)
        )
        binding.tabRegister.setTextColor(
            ContextCompat.getColor(this, if (register) R.color.white else R.color.muted)
        )
        setMsg("", false)
    }

    private fun setMsg(text: String, error: Boolean) {
        binding.tvMsg.text = text
        binding.tvMsg.setTextColor(
            ContextCompat.getColor(this, if (error) R.color.err else R.color.muted)
        )
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.btnLogin.isEnabled = !value
        binding.btnRegister.isEnabled = !value && binding.btnSendCode.isEnabled
        binding.btnLogin.alpha = if (value) 0.5f else 1f
        binding.btnRegister.alpha = if (value) 0.5f else 1f
    }

    private fun onMain(block: () -> Unit) {
        ui.post(block)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            setMsg("无法打开浏览器", true)
        }
    }

    private fun showPolicyPicker() {
        val items = arrayOf("《用户协议》", "《隐私政策》")
        AlertDialog.Builder(this)
            .setTitle("请阅读以下文件")
            .setItems(items) { _, which ->
                openUrl(
                    if (which == 0) AppConfig.termsUrl(server) else AppConfig.privacyUrl(server)
                )
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ------------------------------------------------------------ 邮件通道状态
    private fun loadMailConfig() {
        executor.execute {
            val resp = AuthApi.config(AppConfig.httpBase(server))
            onMain {
                if (resp.code == 200 && !resp.body.optBoolean("mailConfigured", true)) {
                    binding.tvBanner.visibility = View.VISIBLE
                    binding.tvBanner.text =
                        "当前服务端未配置邮件发送通道，暂时无法注册。请联系管理员。"
                    binding.btnSendCode.isEnabled = false
                    binding.btnRegister.isEnabled = false
                    binding.btnSendCode.alpha = 0.5f
                    binding.btnRegister.alpha = 0.5f
                }
            }
        }
    }

    // ------------------------------------------------------------ 业务
    private fun validEmail(email: String): Boolean =
        email.length in 3..254 && Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$").matches(email)

    private fun doSendCode() {
        if (busy) return
        val email = binding.etRegEmail.text.toString().trim()
        if (!validEmail(email)) {
            setMsg("请输入正确的邮箱地址", true)
            return
        }
        setBusy(true)
        setMsg("正在发送验证码…", false)
        executor.execute {
            val resp = AuthApi.sendCode(AppConfig.httpBase(server), email)
            onMain {
                setBusy(false)
                if (resp.code == 200) {
                    setMsg("验证码已发送，请查收邮件（含垃圾箱）", false)
                    startCountdown()
                } else {
                    setMsg(AuthApi.errorOf(resp), true)
                }
            }
        }
    }

    private fun startCountdown() {
        countdown = 60
        binding.btnSendCode.isEnabled = false
        binding.btnSendCode.alpha = 0.5f
        val tick = object : Runnable {
            override fun run() {
                countdown -= 1
                if (countdown <= 0) {
                    binding.btnSendCode.isEnabled = true
                    binding.btnSendCode.alpha = 1f
                    binding.btnSendCode.text = "重新获取"
                } else {
                    binding.btnSendCode.text = "${countdown}s"
                    ui.postDelayed(this, 1000)
                }
            }
        }
        binding.btnSendCode.text = "${countdown}s"
        ui.postDelayed(tick, 1000)
    }

    private fun doLogin() {
        if (busy) return
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (!validEmail(email)) {
            setMsg("请输入正确的邮箱地址", true)
            return
        }
        if (password.isEmpty()) {
            setMsg("请输入密码", true)
            return
        }
        setBusy(true)
        setMsg("正在登录…", false)
        executor.execute {
            val resp = AuthApi.login(AppConfig.httpBase(server), email, password)
            onMain {
                setBusy(false)
                val token = resp.body.optString("token")
                if (resp.code == 200 && token.isNotEmpty()) {
                    onAuthSuccess(token, email)
                } else {
                    setMsg(AuthApi.errorOf(resp), true)
                }
            }
        }
    }

    private fun doRegister() {
        if (busy) return
        val email = binding.etRegEmail.text.toString().trim()
        val code = binding.etRegCode.text.toString().trim()
        val pw = binding.etRegPassword.text.toString()
        val pw2 = binding.etRegPassword2.text.toString()

        if (!validEmail(email)) return setMsg("请输入正确的邮箱地址", true)
        if (!Regex("^\\d{6}$").matches(code)) return setMsg("请填写 6 位验证码", true)
        if (pw.length < 8) return setMsg("密码至少 8 位", true)
        if (!Regex("[A-Za-z]").containsMatchIn(pw) || !Regex("\\d").containsMatchIn(pw)) {
            return setMsg("密码需同时包含字母和数字", true)
        }
        if (pw != pw2) return setMsg("两次输入的密码不一致", true)
        if (!binding.cbAgree.isChecked) {
            return setMsg("请先阅读并勾选同意《用户协议》与《隐私政策》", true)
        }

        setBusy(true)
        setMsg("正在注册…", false)
        executor.execute {
            val resp = AuthApi.register(AppConfig.httpBase(server), email, pw, code)
            onMain {
                setBusy(false)
                val token = resp.body.optString("token")
                if (resp.code == 200 && token.isNotEmpty()) {
                    onAuthSuccess(token, email)
                } else {
                    setMsg(AuthApi.errorOf(resp), true)
                }
            }
        }
    }

    private fun onAuthSuccess(token: String, email: String) {
        getSharedPreferences(AppConfig.PREFS, MODE_PRIVATE).edit()
            .putString(AppConfig.KEY_TOKEN, token)
            .putString(AppConfig.KEY_USER_EMAIL, email)
            .apply()
        setMsg("登录成功", false)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
