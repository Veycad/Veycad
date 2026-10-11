"""CI-only independent float oracle for exact emulator-produced AAC, never a product backend."""
import array
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import tarfile


def analyze(samples):
    if not samples or not all(math.isfinite(x) for x in samples):
        raise ValueError("Missing/nonfinite float AAC samples")
    peak = max(abs(x) for x in samples)
    run = longest = 0
    for value in samples:
        run = run + 1 if abs(value) >= 0.999 else 0
        longest = max(longest, run)
    if not 0.1 < peak <= 1 or longest >= 16:
        raise ValueError(f"AAC headroom failed: unclamped peak={peak}, fullscale run={longest}")
    return {"unclampedFloatPeak": peak, "longestFullscaleRun": longest}


def run(args):
    return subprocess.check_output(args, stderr=subprocess.PIPE)


def verify(root):
    archives = list(Path(root).rglob("source-audio-native-evidence.tar"))
    if len(archives) != 1:
        raise ValueError(f"Expected one exact-run AAC evidence archive, got {len(archives)}")
    archive = archives[0]
    destination = archive.parent / "source-audio-host-oracle"
    destination.mkdir(exist_ok=True)
    with tarfile.open(archive) as package:
        for member in package.getmembers():
            relative = Path(member.name)
            if relative.is_absolute() or ".." in relative.parts or member.issym() or member.islnk():
                raise ValueError("Unsafe evidence path/link")
            if not member.isdir() and not member.isfile():
                raise ValueError("Unsupported evidence member")
            if member.size > 32 * 1024 * 1024:
                raise ValueError("Oversized evidence member")
        package.extractall(destination, filter="data")
    manifests = list(destination.rglob("float-oracle.json"))
    if len(manifests) != 1:
        raise ValueError(f"Actual AAC float evidence missing/ambiguous: {len(manifests)}")
    evidence = json.loads(manifests[0].read_text())
    if (evidence.get("sampleFrames"), evidence.get("sampleRate"), evidence.get("channels")) != (48000, 48000, 2):
        raise ValueError("Malformed/unexpected authored float evidence")
    if evidence["aac"] != "mixed-for-listening.m4a":
        raise ValueError("Unexpected AAC evidence name")
    aac = manifests[0].parent / evidence["aac"]
    digest = hashlib.sha256(aac.read_bytes()).hexdigest()
    if digest != evidence["sha256"]:
        raise ValueError("AAC differs from device-recorded hash")
    probe = json.loads(run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_streams", "-show_frames", "-of", "json", str(aac)]))
    (destination / "ffprobe.json").write_text(json.dumps(probe, indent=2))
    stream = probe["streams"][0]
    frames = probe["frames"]
    if stream["codec_name"] != "aac" or stream["sample_fmt"] != "fltp" or not frames or any(f["sample_fmt"] != "fltp" for f in frames):
        raise ValueError("Oracle did not actually decode AAC to planar float")
    if int(stream["sample_rate"]) != 48000 or stream["channels"] != 2:
        raise ValueError("Unexpected actual AAC format")
    pcm = destination / "actual-aac-unclamped-f32le.pcm"
    # FFmpeg's native AAC decoder outputs fltp. pcm_f32le only interleaves the floating-point
    # samples; there is no S16 conversion, limiter, gain, resample or clipping filter.
    run(["ffmpeg", "-v", "error", "-y", "-c:a", "aac", "-i", str(aac), "-map", "0:a:0", "-c:a", "pcm_f32le", "-f", "f32le", str(pcm)])
    samples = array.array("f")
    samples.frombytes(pcm.read_bytes())
    if sys.byteorder != "little":
        samples.byteswap()
    if len(samples) != evidence["sampleFrames"] * 2:
        raise ValueError(f"Host decoded count {len(samples)//2} != actual input {evidence['sampleFrames']}")
    report = analyze(samples)
    markers = []
    for name in ("prepared-30.m4a", "prepared-60.m4a", "av-30.mp4", "av-60.mp4"):
        matches = list(destination.rglob(name))
        if len(matches) != 1:
            raise ValueError(f"Missing/ambiguous actual marker artifact {name}")
        marker_file = matches[0]
        data = run(["ffmpeg", "-v", "error", "-c:a", "aac", "-i", str(marker_file), "-map", "0:a:0", "-c:a", "pcm_f32le", "-f", "f32le", "-"])
        decoded = array.array("f"); decoded.frombytes(data)
        if sys.byteorder != "little":
            decoded.byteswap()
        if len(decoded) != 96000 or not all(math.isfinite(x) for x in decoded):
            raise ValueError(f"Wrong decoded length/format for {name}")
        peaks = []
        for authored, (start, end) in zip((1600, 24000, 46400), ((0, 7200), (16800, 31200), (40800, 48000))):
            peak = max(range(start, end), key=lambda i: abs(decoded[i*2]))
            if abs(peak-authored) > 1 or abs(decoded[peak*2]) <= 0.3:
                raise ValueError(f"Independent {name} marker {authored} failed: sample={peak}")
            peaks.append(peak)
        markers.append({"file": name, "sha256": hashlib.sha256(marker_file.read_bytes()).hexdigest(), "markerSamples": peaks, "decodedFrames": 48000})
        (destination / (name + ".unclamped-f32le.pcm")).write_bytes(data)
    report["independentBoundaryMarkers"] = markers
    report.update({"aacSha256": digest, "decodedSampleFrames": len(samples)//2, "decoderFormat": "fltp", "androidPcmEncoding": evidence["androidPcmEncoding"], "scope": "host AAC oracle only; Android float and human listening not established"})
    (destination / "result.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == "__main__":
    verify(sys.argv[1])
