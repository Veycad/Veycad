"""Loopback-only, read-only review of three explicitly selected local MP4s."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse
import re

def byte_range(header, size):
    if header is None:
        return 0, size - 1, False
    match = re.fullmatch(r"bytes=(\d+)-(\d*)", header)
    if not match:
        raise ValueError("Unsupported range")
    start = int(match[1])
    end = min(int(match[2]) if match[2] else size - 1, size - 1)
    if start > end or start >= size:
        raise ValueError("Unsatisfiable range")
    return start, end, True

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("reference", "before", "candidate"):
        parser.add_argument(name, type=Path)
    parser.add_argument("--port", type=int, default=8768)
    args = parser.parse_args()
    files = {f"/{name}.mp4": getattr(args, name).resolve(strict=True)
             for name in ("reference", "before", "candidate")}
    files["/"] = Path(__file__).with_name("heartbeat_review.html")

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            path = files.get(self.path)
            if path is None:
                self.send_error(404)
                return
            size = path.stat().st_size
            try:
                start, end, partial = byte_range(self.headers.get("Range"), size)
            except ValueError:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.end_headers()
                return
            self.send_response(206 if partial else 200)
            self.send_header("Content-Type", "text/html; charset=utf-8" if self.path == "/" else "video/mp4")
            self.send_header("Content-Length", str(end-start+1))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Cache-Control", "no-store")
            if partial:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            try:
                with path.open("rb") as source:
                    source.seek(start)
                    remaining = end-start+1
                    while remaining:
                        chunk = source.read(min(remaining, 65536))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        remaining -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass

    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
