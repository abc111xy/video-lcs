import Foundation

/// 与 Android / 桌面 / 网页端完全一致的 WebSocket 适配层：
/// 文本帧是 JSON 信令，二进制帧是裸 JPEG。
final class RelayClient: NSObject {

    enum State: Equatable {
        case idle, connecting, live, failed(String)
    }

    struct Stats {
        var viewers = 0
        var frames = 0
        var bytes = 0
        var startedAt = Date()
    }

    private var task: URLSessionWebSocketTask?
    private var session: URLSession!
    private var room = ""
    private var token = ""

    var onState: ((State) -> Void)?
    var onViewers: ((Int) -> Void)?

    override init() {
        super.init()
        session = URLSession(configuration: .default)
    }

    // MARK: - 连接

    func connect(server: URL, room: String, name: String, token: String) async throws {
        self.room = room
        self.token = token
        onState?(.connecting)

        var comps = URLComponents(url: server, resolvingAgainstBaseURL: false)!
        comps.scheme = server.scheme == "https" ? "wss" : "ws"
        comps.path = "/ws"
        guard let url = comps.url else { throw URLError(.badURL) }

        let t = session.webSocketTask(with: url)
        task = t
        t.resume()

        let join: [String: Any] = [
            "type": "join", "room": room, "role": "sender",
            "name": name.isEmpty ? "iOS 主播" : name, "token": token,
        ]
        try await send(json: join)

        // 等服务端确认（joined / error）
        let reply = try await receiveJSON(timeout: 10)
        if reply["type"] as? String == "error" {
            t.cancel()
            task = nil
            throw NSError(domain: "StreamCast", code: 401,
                          userInfo: [NSLocalizedDescriptionKey: (reply["message"] as? String) ?? "加入房间失败"])
        }
        guard reply["type"] as? String == "joined" else {
            throw NSError(domain: "StreamCast", code: 402,
                          userInfo: [NSLocalizedDescriptionKey: "服务端未确认加入"])
        }
        onState?(.live)
        onViewers?(reply["receivers"] as? Int ?? 0)
        listen()
    }

    private func listen() {
        task?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure:
                self.onState?(.failed("与服务器的连接已断开"))
            case .success(let message):
                if case .string(let text) = message,
                   let data = text.data(using: .utf8),
                   let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let type = obj["type"] as? String {
                    if type == "stats" { self.onViewers?(obj["receivers"] as? Int ?? 0) }
                    if type == "error", let msg = obj["message"] as? String { self.onState?(.failed(msg)) }
                }
                self.listen()
            }
        }
    }

    // MARK: - 发送

    func sendMeta(width: Int, height: Int, fps: Int, quality: Int) async {
        try? await send(json: [
            "type": "meta", "width": width, "height": height,
            "fps": fps, "quality": quality, "codec": "jpeg",
        ])
    }

    func sendFrame(_ jpeg: Data) {
        task?.send(.data(jpeg)) { _ in }
    }

    func close() {
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        onState?(.idle)
    }

    // MARK: - 底层

    private func send(json: [String: Any]) async throws {
        let data = try JSONSerialization.data(withJSONObject: json)
        guard let text = String(data: data, encoding: .utf8) else { return }
        try await task?.send(.string(text))
    }

    private func receiveJSON(timeout: TimeInterval) async throws -> [String: Any] {
        guard let task else { throw URLError(.notConnectedToInternet) }
        return try await withThrowingTaskGroup(of: [String: Any].self) { group in
            group.addTask {
                let msg = try await task.receive()
                guard case .string(let text) = msg,
                      let data = text.data(using: .utf8),
                      let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    return [:]
                }
                return obj
            }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                throw URLError(.timedOut)
            }
            let first = try await group.next() ?? [:]
            group.cancelAll()
            return first
        }
    }
}
