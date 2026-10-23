"""Author tiny synthetic AVC fixtures with a literal, edit-independent 400 ms origin.

Usage: python generate_raw_origin_fixture.py /path/to/ffmpeg /path/to/assets/media
60 planar YUV frames reproduce SyntheticVideo's luma 80 + frame, neutral chroma.
The sibling retains every encoded media byte and chunk offset; only moov changes.
"""
import hashlib
import pathlib
import struct
import subprocess
import sys


def boxes(data):
    cursor = 0
    while cursor < len(data):
        assert len(data) - cursor >= 8
        size, kind = struct.unpack_from(">I4s", data, cursor)
        assert 8 <= size <= len(data) - cursor, "Only bounded 32-bit boxes are supported"
        yield kind, data[cursor + 8:cursor + size], cursor, size
        cursor += size
    assert cursor == len(data)


def box(kind, payload):
    return struct.pack(">I4s", len(payload) + 8, kind) + payload


def u32(data, offset):
    return struct.unpack_from(">I", data, offset)[0]


def rewrite(data):
    result = bytearray()
    for kind, payload, _, _ in boxes(data):
        if kind == b"edts":
            continue  # No edit may cancel the authored positive composition offset.
        if kind in (b"moov", b"trak", b"mdia", b"minf", b"stbl"):
            payload = rewrite(payload)
            if kind == b"stbl":
                children = list(boxes(payload))
                assert not any(item[0] == b"ctts" for item in children)
                stts = next(item[1] for item in children if item[0] == b"stts")
                assert stts == struct.pack(">IIII", 0, 1, 60, 1000)
                stsz = next(item[1] for item in children if item[0] == b"stsz")
                assert u32(stsz, 8) == 60
                # 30,000 media ticks/s: each sample is 1,000 ticks; +12,000 = 400 ms.
                payload += box(b"ctts", struct.pack(">IIII", 0, 1, 60, 12000))
        elif kind in (b"mvhd", b"mdhd", b"tkhd"):
            payload = bytearray(payload)
            assert payload[0] == 0, "Version-0 literal duration fixture only"
            if kind == b"tkhd":
                duration_offset, duration = 20, 2400
            else:
                scale = u32(payload, 12)
                assert scale == (30000 if kind == b"mdhd" else 1000)
                duration_offset, duration = 16, scale * 12 // 5
            struct.pack_into(">I", payload, duration_offset, duration)
        result += box(kind, payload)
    return bytes(result)


def chunks(data):
    result = []
    for kind, payload, _, _ in boxes(data):
        if kind in (b"moov", b"trak", b"mdia", b"minf", b"stbl"):
            result.extend(chunks(payload))
        elif kind in (b"stco", b"co64"):
            count = u32(payload, 4)
            width, code = (4, "I") if kind == b"stco" else (8, "Q")
            assert len(payload) == 8 + count * width
            result.extend(struct.unpack_from(">" + code * count, payload, 8))
    return result


def main():
    ffmpeg, destination = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    destination.mkdir(parents=True, exist_ok=True)
    seed = destination / "raw-origin-seed.mp4"
    shifted = destination / "raw-origin-400ms.mp4"
    frame_bytes = [bytes([80 + index]) * (160 * 240) + bytes([128]) * (160 * 240 // 2)
                   for index in range(60)]
    subprocess.run([str(ffmpeg), "-v", "error", "-y", "-f", "rawvideo", "-pixel_format",
                    "yuv420p", "-video_size", "160x240", "-framerate", "30", "-i", "pipe:0",
                    "-an", "-c:v", "libx264", "-profile:v", "baseline", "-bf", "0", "-g", "30",
                    "-crf", "18", "-threads", "1", "-video_track_timescale", "30000",
                    "-map_metadata", "-1", str(seed)], input=b"".join(frame_bytes), check=True)
    original = seed.read_bytes()
    top = list(boxes(original))
    assert sum(kind == b"moov" for kind, *_ in top) == 1
    assert sum(kind == b"mdat" for kind, *_ in top) == 1
    assert sum(kind == b"trak" for kind, *_ in boxes(next(p for k, p, *_ in top if k == b"moov"))) == 1
    media = next(item for item in top if item[0] == b"mdat")
    movie = next(item for item in top if item[0] == b"moov")
    assert movie[2] >= media[2] + media[3], "moov must follow mdat; no chunk relocation is allowed"
    authored = b"".join(box(k, rewrite(p) if k == b"moov" else p) for k, p, *_ in top)
    assert chunks(original) == chunks(authored)
    assert all(media[2] + 8 <= offset < media[2] + media[3] for offset in chunks(authored))
    assert authored[:media[2] + media[3]] == original[:media[2] + media[3]]
    shifted.write_bytes(authored)
    print("unchanged_mdat_sha256=" + hashlib.sha256(media[1]).hexdigest())
    for path in (seed, shifted):
        print(path.name, len(path.read_bytes()), hashlib.sha256(path.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
