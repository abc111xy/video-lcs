import AVFoundation
import UIKit

/// 摄像头采集：输出 420f 视频帧，按目标帧率抽帧后压缩成 JPEG。
/// 之所以用 JPEG 而不是 H.264，是为了和 Android / 桌面 / 网页端共用同一条
/// 「0x00 裸 JPEG 帧」通路——服务端不需要转码，任何接收端都能直接显示。
final class CameraCapture: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {

    struct Config {
        var width: Int32 = 1280
        var height: Int32 = 720
        var fps: Int = 15
        var quality: CGFloat = 0.65
        var position: AVCaptureDevice.Position = .back
    }

    /// 每帧回调（在串行队列上），参数为 JPEG 数据与像素尺寸
    var onFrame: ((Data, Int, Int) -> Void)?
    var onError: ((String) -> Void)?

    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "streamcast.camera")
    private var output: AVCaptureVideoDataOutput?
    private var lastSent = CFAbsoluteTimeGetCurrent()
    private var config = Config()
    private(set) var isRunning = false

    var previewSession: AVCaptureSession { session }

    // MARK: - 权限

    static func requestAccess() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return true
        case .notDetermined:
            return await withCheckedContinuation { cont in
                AVCaptureDevice.requestAccess(for: .video) { cont.resume(returning: $0) }
            }
        default: return false
        }
    }

    // MARK: - 启停

    func start(_ cfg: Config) throws {
        config = cfg
        if isRunning { return }

        session.beginConfiguration()
        session.sessionPreset = cfg.height >= 1080 ? .hd1920x1080 : (cfg.height >= 720 ? .hd1280x720 : .vga640x480)

        session.inputs.forEach { session.removeInput($0) }
        session.outputs.forEach { session.removeOutput($0) }

        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: cfg.position)
                ?? AVCaptureDevice.default(for: .video) else {
            throw NSError(domain: "StreamCast", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "没有可用的摄像头"])
        }
        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else {
            throw NSError(domain: "StreamCast", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "无法添加摄像头输入"])
        }
        session.addInput(input)

        let out = AVCaptureVideoDataOutput()
        out.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
        out.alwaysDiscardsLateVideoFrames = true
        out.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(out) else {
            throw NSError(domain: "StreamCast", code: 3,
                          userInfo: [NSLocalizedDescriptionKey: "无法添加视频输出"])
        }
        session.addOutput(out)
        output = out

        if let conn = out.connection, conn.isVideoOrientationSupported {
            conn.videoOrientation = .portrait
        }
        session.commitConfiguration()

        session.startRunning()
        isRunning = true
    }

    func stop() {
        guard isRunning else { return }
        queue.async {
            self.session.stopRunning()
            self.session.beginConfiguration()
            self.session.inputs.forEach { self.session.removeInput($0) }
            self.session.outputs.forEach { self.session.removeOutput($0) }
            self.session.commitConfiguration()
            self.isRunning = false
        }
    }

    // MARK: - 抽帧 + 压缩

    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        let now = CFAbsoluteTimeGetCurrent()
        let interval = 1.0 / Double(max(1, config.fps))
        guard now - lastSent >= interval else { return }
        lastSent = now

        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let ci = CIImage(cvPixelBuffer: buffer)
        let context = CIContext(options: [.useSoftwareRenderer: false])
        guard let cg = context.createCGImage(ci, from: ci.extent) else { return }
        let image = UIImage(cgImage: cg)
        guard let jpeg = image.jpegData(compressionQuality: config.quality) else { return }
        onFrame?(jpeg, Int(ci.extent.width), Int(ci.extent.height))
    }
}

/// SwiftUI 里的预览层
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.videoPreviewLayer.session = session
        v.videoPreviewLayer.videoGravity = .resizeAspectFill
        return v
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoPreviewLayer: AVCaptureVideoPreviewLayer {
            layer as! AVCaptureVideoPreviewLayer
        }
    }
}
