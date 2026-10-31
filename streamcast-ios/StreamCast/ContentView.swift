import SwiftUI
import AVFoundation

struct ContentView: View {
    // MARK: - 可调参数
    @State private var server = UserDefaults.standard.string(forKey: "server") ?? "https://spl.app.workbuddy.host"
    @State private var room = String(Int.random(in: 1000...9999))
    @State private var nick = ""
    @State private var fps = 15
    @State private var quality: Double = 0.65
    @State private var useFront = false

    // MARK: - 登录
    @State private var token = UserDefaults.standard.string(forKey: "token") ?? ""
    @State private var email = ""
    @State private var password = ""
    @State private var loginError = ""
    @State private var loggingIn = false

    // MARK: - 运行状态
    @State private var capture = CameraCapture()
    @State private var relay = RelayClient()
    @State private var state: RelayClient.State = .idle
    @State private var viewers = 0
    @State private var frames = 0
    @State private var startedAt: Date?

    private var isLive: Bool { state == .live }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    preview
                    if token.isEmpty { loginCard } else { controlCard }
                    footerLinks
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("StreamCast")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if !token.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("退出") { logout() }.font(.footnote)
                    }
                }
            }
        }
        .onChange(of: state) { _, new in
            if case .failed(let msg) = new {
                stopEverything()
                loginError = msg
            }
        }
    }

    // MARK: - 预览

    private var preview: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16).fill(Color.black)
            if isLive {
                CameraPreview(session: capture.previewSession)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
            } else {
                VStack(spacing: 8) {
                    Image(systemName: "video.slash")
                        .font(.system(size: 34)).foregroundStyle(.white.opacity(0.45))
                    Text("未开播").foregroundStyle(.white.opacity(0.55)).font(.footnote)
                }
            }
            if isLive {
                VStack {
                    HStack {
                        Label("直播中", systemImage: "dot.radiowaves.left.and.right")
                            .font(.caption2).padding(.horizontal, 10).padding(.vertical, 5)
                            .background(.black.opacity(0.45), in: Capsule())
                            .foregroundStyle(.white)
                        Spacer()
                        Text("房间 \(room)")
                            .font(.caption2).padding(.horizontal, 10).padding(.vertical, 5)
                            .background(.black.opacity(0.45), in: Capsule())
                            .foregroundStyle(.white)
                    }
                    Spacer()
                    HStack(spacing: 14) {
                        stat("观看", "\(viewers)")
                        stat("已发", "\(frames)")
                        stat("时长", elapsed)
                        Spacer()
                    }
                }
                .padding(12)
            }
        }
        .aspectRatio(9.0 / 16.0, contentMode: .fit)
        .frame(maxHeight: 380)
    }

    private func stat(_ title: String, _ value: String) -> some View {
        VStack(spacing: 1) {
            Text(value).font(.caption).bold().foregroundStyle(.white)
            Text(title).font(.system(size: 9)).foregroundStyle(.white.opacity(0.6))
        }
    }

    private var elapsed: String {
        guard let s = startedAt else { return "00:00" }
        let sec = Int(Date().timeIntervalSince(s))
        return String(format: "%02d:%02d", sec / 60, sec % 60)
    }

    // MARK: - 登录卡片

    private var loginCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("登录后开播").font(.headline)
            Text("账号与网页端通用，可在浏览器注册后再回来登录。")
                .font(.caption).foregroundStyle(.secondary)

            TextField("邮箱", text: $email)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.emailAddress)
                .textFieldStyle(.roundedBorder)

            SecureField("密码", text: $password)
                .textFieldStyle(.roundedBorder)

            if !loginError.isEmpty {
                Text(loginError).font(.caption).foregroundStyle(.red)
            }

            Button {
                Task { await login() }
            } label: {
                Text(loggingIn ? "登录中…" : "登录")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(loggingIn || email.isEmpty || password.isEmpty)
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
    }

    // MARK: - 开播面板

    private var controlCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("房间号").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("随机") { room = String(Int.random(in: 1000...9999)) }
                    .font(.caption)
            }
            TextField("房间号", text: $room)
                .keyboardType(.numberPad)
                .textFieldStyle(.roundedBorder)

            TextField("昵称（选填）", text: $nick)
                .textFieldStyle(.roundedBorder)

            Toggle("使用前置摄像头", isOn: $useFront)

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("帧率").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Text("\(fps) fps").font(.caption).monospacedDigit()
                }
                Slider(value: Binding(get: { Double(fps) },
                                      set: { fps = Int($0) }),
                       in: 5...30, step: 1)
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("画质").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Text("\(Int(quality * 100))").font(.caption).monospacedDigit()
                }
                Slider(value: $quality, in: 0.35...0.9)
            }

            Button {
                isLive ? stopEverything() : startLive()
            } label: {
                Text(isLive ? "结束直播" : "开始直播")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(isLive ? .bordered : .borderedProminent)
            .tint(isLive ? .red : .accentColor)

            Text("开播会持续使用摄像头，建议连接电源。观看端输入房间号即可收看。")
                .font(.caption2).foregroundStyle(.secondary)
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
    }

    private var footerLinks: some View {
        VStack(spacing: 6) {
            Text("服务器：\(server)").font(.caption2).foregroundStyle(.secondary)
            HStack(spacing: 12) {
                Link("用户协议", destination: URL(string: server + "/terms.html")!)
                Link("隐私政策", destination: URL(string: server + "/privacy.html")!)
                Link("实验性功能声明", destination: URL(string: server + "/disclaimer.html")!)
            }
            .font(.caption2)
        }
        .padding(.top, 4)
    }

    // MARK: - 动作

    private func login() async {
        loggingIn = true
        loginError = ""
        defer { loggingIn = false }
        do {
            guard let url = URL(string: server + "/api/auth/login") else { throw URLError(.badURL) }
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: ["email": email, "password": password])
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard let http = resp as? HTTPURLResponse else { throw URLError(.badServerResponse) }
            let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            if http.statusCode != 200 {
                throw NSError(domain: "login", code: http.statusCode,
                              userInfo: [NSLocalizedDescriptionKey: (obj["error"] as? String) ?? "登录失败"])
            }
            guard let t = obj["token"] as? String else { throw URLError(.cannotParseResponse) }
            token = t
            UserDefaults.standard.set(t, forKey: "token")
            UserDefaults.standard.set(server, forKey: "server")
        } catch {
            loginError = error.localizedDescription
        }
    }

    private func logout() {
        stopEverything()
        token = ""
        UserDefaults.standard.removeObject(forKey: "token")
    }

    private func startLive() {
        guard !token.isEmpty else { return }
        guard let base = URL(string: server) else { loginError = "服务器地址无效"; return }

        Task {
            guard await CameraCapture.requestAccess() else {
                loginError = "摄像头权限被拒绝，请在系统设置中开启"
                return
            }
            do {
                var cfg = CameraCapture.Config()
                cfg.fps = fps
                cfg.quality = CGFloat(quality)
                cfg.width = 720
                cfg.height = 1280
                cfg.position = useFront ? .front : .back

                capture.onFrame = { data, _, _ in
                    relay.sendFrame(data)
                    DispatchQueue.main.async { frames += 1 }
                }
                capture.onError = { msg in DispatchQueue.main.async { loginError = msg } }
                relay.onState = { s in DispatchQueue.main.async { state = s } }
                relay.onViewers = { n in DispatchQueue.main.async { viewers = n } }

                try capture.start(cfg)
                try await relay.connect(server: base, room: room, name: nick, token: token)
                await relay.sendMeta(width: 720, height: 1280, fps: fps, quality: Int(quality * 100))
                startedAt = Date()
                frames = 0
                state = .live
            } catch {
                stopEverything()
                loginError = error.localizedDescription
            }
        }
    }

    private func stopEverything() {
        capture.stop()
        capture.onFrame = nil
        relay.close()
        state = .idle
        startedAt = nil
        viewers = 0
    }
}

#Preview {
    ContentView()
}
