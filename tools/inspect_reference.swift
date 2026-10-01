import AVFoundation
import AppKit
import CoreText
import ImageIO
import UniformTypeIdentifiers

// Local analysis only. Contact sheets are evidence, never application assets.
let input = CommandLine.arguments[1]
let output = CommandLine.arguments[2]
try FileManager.default.createDirectory(atPath: output, withIntermediateDirectories: true)
let asset = AVURLAsset(url: URL(fileURLWithPath: input))
let duration = CMTimeGetSeconds(asset.duration)
let video = asset.tracks(withMediaType: .video).first!
print("duration=\(duration) size=\(video.naturalSize) fps=\(video.nominalFrameRate) transform=\(video.preferredTransform) audioTracks=\(asset.tracks(withMediaType: .audio).count)")
// Explicit opt-in only for tracks the user has authorised for reuse.
if CommandLine.arguments.contains("--licensed-audio") {
    let target = URL(fileURLWithPath: "\(output)/heartbeat-author.m4a")
    if !FileManager.default.fileExists(atPath: target.path) {
        let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A)!
        export.outputURL = target
        export.outputFileType = .m4a
        let completed = DispatchSemaphore(value: 0)
        export.exportAsynchronously { completed.signal() }
        completed.wait()
        guard export.status == .completed else { throw export.error ?? NSError(domain:"audio-export",code:1) }
        print("licensed_audio=\(target.path)")
    }
}
let generator = AVAssetImageGenerator(asset: asset)
generator.appliesPreferredTrackTransform = true
// Social exports do not always carry a decodable sample at the exact requested timestamp.
// A one-frame tolerance keeps the sheet temporal while avoiding a hard failure on those files.
generator.requestedTimeToleranceBefore = CMTime(seconds: 1.0 / max(Double(video.nominalFrameRate), 1), preferredTimescale: 1_000_000)
generator.requestedTimeToleranceAfter = generator.requestedTimeToleranceBefore
let step = 0.5
let customTimes: [Double]? = CommandLine.arguments.first(where: { $0.hasPrefix("--times=") })
    .map { String($0.dropFirst(8)).split(separator:",").map { Double($0)! } }
let count = customTimes?.count ?? Int(ceil(duration / step))
for page in 0..<Int(ceil(Double(count) / 24)) {
    let width = 960
    let height = 1080
    let bytesPerRow = width * 4
    let pixels = NSMutableData(length: bytesPerRow * height)!
    guard let context = CGContext(
        data: pixels.mutableBytes,
        width: width,
        height: height,
        bitsPerComponent: 8,
        bytesPerRow: bytesPerRow,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else {
        throw NSError(domain: "contact-sheet", code: 1)
    }
    context.setFillColor(NSColor.black.cgColor)
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    context.textMatrix = .identity
    let font = CTFontCreateWithName("Helvetica" as CFString, 12, nil)
    for slot in 0..<24 {
        let i = page * 24 + slot
        if i >= count { break }
        var actual = CMTime.zero
        let frame = try generator.copyCGImage(at: CMTime(seconds: (customTimes?[i] ?? Double(i)*step) + 0.0001, preferredTimescale: 1_000_000), actualTime: &actual)
        if CommandLine.arguments.contains("--frames") {
            let bitmap = NSBitmapImageRep(cgImage: frame)
            try bitmap.representation(using: .png, properties: [:])!.write(to:
                URL(fileURLWithPath: "\(output)/frame-\(i).png"))
        }
        let x = (slot % 6) * 160
        let y = 1080 - (slot / 6 + 1) * 270
        let scale = min(160.0 / Double(frame.width), 246.0 / Double(frame.height))
        let w = Double(frame.width)*scale, h = Double(frame.height)*scale
        context.draw(frame, in: CGRect(x: Double(x)+(160-w)/2,y: Double(y)+24,width: w,height: h))
        let label = NSAttributedString(
            string: String(format: "%.3f s", CMTimeGetSeconds(actual)),
            attributes: [
                NSAttributedString.Key(kCTFontAttributeName as String): font,
                NSAttributedString.Key(kCTForegroundColorAttributeName as String): NSColor.white.cgColor
            ]
        )
        context.textPosition = CGPoint(x: x + 4, y: y + 3)
        CTLineDraw(CTLineCreateWithAttributedString(label), context)
    }
    guard let sheet = context.makeImage(),
          let jpeg = NSBitmapImageRep(cgImage: sheet).representation(
            using: .jpeg,
            properties: [.compressionFactor: 0.9]
          ) else {
        throw NSError(domain: "contact-sheet-encoding", code: 2)
    }
    try jpeg.write(to: URL(fileURLWithPath:"\(output)/sheet-\(page).jpg"))
}
