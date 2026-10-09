import AVFoundation
import Foundation

// Sequential decoded evidence. Peaks are candidates, not automatically labelled cuts/beats.
let source = URL(fileURLWithPath: CommandLine.arguments[1])
let directory = URL(fileURLWithPath: CommandLine.arguments[2], isDirectory: true)
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
let asset = AVURLAsset(url: source)
let reader = try AVAssetReader(asset: asset)
guard let track = asset.tracks(withMediaType: .video).first else { fatalError("No video") }
let output = AVAssetReaderTrackOutput(track: track, outputSettings: [kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32BGRA])
reader.add(output)
guard reader.startReading() else { throw reader.error! }
var previous = [Double]()
var previousChromaU = 0.5
var previousChromaV = 0.5
var rows = "pts_us\tluma\tchroma_u\tchroma_v\tcolour_jump\tmean_abs_delta\tcentred_delta\tspatial_gradient\n"
var total = 0
while let sample = output.copyNextSampleBuffer() {
    guard let image = CMSampleBufferGetImageBuffer(sample) else { continue }
    CVPixelBufferLockBaseAddress(image, .readOnly)
    let bytes = CVPixelBufferGetBaseAddress(image)!.assumingMemoryBound(to: UInt8.self)
    let stride = CVPixelBufferGetBytesPerRow(image)
    let width = CVPixelBufferGetWidth(image), height = CVPixelBufferGetHeight(image)
    var pixels = [Double](); pixels.reserveCapacity(4096)
    var redSum = 0.0, greenSum = 0.0, blueSum = 0.0
    for y in 0..<64 { for x in 0..<64 {
        let i = (y * height / 64) * stride + (x * width / 64) * 4
        let blue = Double(bytes[i]) / 255
        let green = Double(bytes[i+1]) / 255
        let red = Double(bytes[i+2]) / 255
        redSum += red; greenSum += green; blueSum += blue
        pixels.append(0.299*red + 0.587*green + 0.114*blue)
    } }
    CVPixelBufferUnlockBaseAddress(image, .readOnly)
    let mean = pixels.reduce(0,+)/Double(pixels.count)
    let oldMean = previous.isEmpty ? mean : previous.reduce(0,+)/Double(previous.count)
    let meanRed = redSum / 4096, meanGreen = greenSum / 4096, meanBlue = blueSum / 4096
    let chromaU = (-0.168736*meanRed - 0.331264*meanGreen + 0.5*meanBlue + 0.5)
    let chromaV = (0.5*meanRed - 0.418688*meanGreen - 0.081312*meanBlue + 0.5)
    let colourJump = previous.isEmpty ? 0 :
        abs(mean-oldMean)*0.65 + sqrt(pow(chromaU-previousChromaU,2)+pow(chromaV-previousChromaV,2))*0.35
    var delta = 0.0, centred = 0.0
    if !previous.isEmpty { for i in pixels.indices {
        delta += abs(pixels[i]-previous[i]); centred += abs((pixels[i]-mean)-(previous[i]-oldMean))
    } }
    let pts = Int64((CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))*1e6).rounded())
    // Same-frame local detail statistic for paired render comparisons, not a quality score.
    var gradient = 0.0
    for y in 0..<63 { for x in 0..<63 {
        let i = y*64+x
        gradient += abs(pixels[i]-pixels[i+1]) + abs(pixels[i]-pixels[i+64])
    } }
    rows += "\(pts)\t\(mean)\t\(chromaU)\t\(chromaV)\t\(colourJump)\t\(delta/4096)\t\(centred/4096)\t\(gradient/(63*63*2))\n"
    previous = pixels; previousChromaU = chromaU; previousChromaV = chromaV; total += 1
}
guard reader.status == .completed else { throw reader.error ?? NSError(domain:"video-read",code:1) }
try rows.write(to: directory.appendingPathComponent("video-frames.tsv"), atomically:true, encoding:.utf8)
print("decoded_video_frames=\(total)")

if let audio = asset.tracks(withMediaType:.audio).first {
    let ar = try AVAssetReader(asset:asset)
    let ao = AVAssetReaderTrackOutput(track:audio, outputSettings:[AVFormatIDKey:kAudioFormatLinearPCM, AVLinearPCMIsFloatKey:true, AVLinearPCMBitDepthKey:32, AVLinearPCMIsNonInterleaved:false])
    ar.add(ao); guard ar.startReading() else { throw ar.error! }
    var audioRows = "pts_us\tduration_us\trms\tpeak\n"
    var buffers = 0
    var windows = "pts_us\trms\tpeak\n"
    var energy = 0.0, windowPeak = 0.0
    var windowCount = 0
    var windowStart = 0.0
    while let sample = ao.copyNextSampleBuffer() {
        guard let block = CMSampleBufferGetDataBuffer(sample) else { continue }
        let count = CMBlockBufferGetDataLength(block)/4
        var values = [Float](repeating:0,count:count)
        let status = values.withUnsafeMutableBytes { ptr in
            CMBlockBufferCopyDataBytes(block, atOffset:0, dataLength:count*4, destination:ptr.baseAddress!)
        }
        guard status == noErr, count > 0 else { fatalError("PCM copy failed") }
        let format = CMAudioFormatDescriptionGetStreamBasicDescription(CMSampleBufferGetFormatDescription(sample)!)!.pointee
        let channels = Int(format.mChannelsPerFrame)
        let rate = format.mSampleRate
        let start = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
        let framesPerWindow = max(1, Int((rate*0.01).rounded()))
        for frame in 0..<(count/channels) {
            if windowCount == 0 { windowStart = start + Double(frame)/rate }
            var mono = 0.0
            for channel in 0..<channels { mono += Double(values[frame*channels+channel])/Double(channels) }
            energy += mono*mono; windowPeak = max(windowPeak,abs(mono)); windowCount += 1
            if windowCount == framesPerWindow {
                windows += "\(Int64((windowStart*1e6).rounded()))\t\(sqrt(energy/Double(windowCount)))\t\(windowPeak)\n"
                energy = 0; windowPeak = 0; windowCount = 0
            }
        }
        let rms = sqrt(values.reduce(0.0) { $0 + Double($1)*Double($1) }/Double(count))
        let peak = values.map { abs($0) }.max() ?? 0
        let pts = Int64((CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))*1e6).rounded())
        let duration = Int64((CMTimeGetSeconds(CMSampleBufferGetDuration(sample))*1e6).rounded())
        audioRows += "\(pts)\t\(duration)\t\(rms)\t\(peak)\n"; buffers += 1
    }
    guard ar.status == .completed else { throw ar.error ?? NSError(domain:"audio-read",code:1) }
    try audioRows.write(to:directory.appendingPathComponent("audio-energy.tsv"),atomically:true,encoding:.utf8)
    try windows.write(to:directory.appendingPathComponent("audio-10ms.tsv"),atomically:true,encoding:.utf8)
    print("decoded_audio_buffers=\(buffers)")
}
