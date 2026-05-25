#!/usr/bin/env python3
"""
Tiny mock processing endpoint for Voice Note Capture (Phase 1 testing).

Accepts a multipart POST with an 'audio' file and returns canned Markdown text,
so the phone app can be tested against a REAL network call (mock mode OFF) without
standing up the home server. Run on your PC; point the phone app's endpoint at
http://<PC-LAN-or-tailscale-ip>:8099/process

Usage:  python3 mock_endpoint.py
"""
from http.server import BaseHTTPRequestHandler, HTTPServer
from datetime import datetime

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        _ = self.rfile.read(length)  # discard body; we only mock the response
        body = (
            f"# Voice note (mock server)\n\n"
            f"Received {length} bytes at {datetime.now().isoformat(timespec='seconds')}.\n\n"
            f"Replace this server with your real transcribe+summarise endpoint.\n"
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/markdown; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("[mock]", fmt % args)

if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8099), Handler).serve_forever()
