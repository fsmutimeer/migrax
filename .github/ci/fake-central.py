#!/usr/bin/env python3
"""A stand-in for Maven Central's publisher API on localhost, for the release check.

The publishing plugin uploads its bundle here instead of to Central: each upload is saved
as upload-<n>.zip in the given folder, and status questions are answered "published", so
'mvn deploy' runs to the end without publishing anything. check-bundle.py then reads the
saved uploads.

    python3 .github/ci/fake-central.py <folder> [port]
"""
import http.server
import json
import os
import sys

FOLDER = sys.argv[1]
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8799


class Publisher(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if self.path.startswith("/api/v1/publisher/upload"):
            # The bundle is the zip inside the multipart body.
            start, end = body.find(b"PK\x03\x04"), body.rfind(b"PK\x05\x06")
            if start < 0 or end < 0:
                self.answer(400, b"the upload has no zip bundle", "text/plain")
                return
            count = len([f for f in os.listdir(FOLDER) if f.startswith("upload-")])
            with open(os.path.join(FOLDER, f"upload-{count + 1}.zip"), "wb") as out:
                out.write(body[start:end + 22])
            self.answer(201, b"check-deployment", "text/plain")
        else:
            self.status()

    def do_GET(self):
        self.status()

    def status(self):
        self.answer(200, json.dumps({"deploymentId": "check-deployment",
                                     "deploymentState": "PUBLISHED", "purls": []}).encode(),
                    "application/json")

    def answer(self, code, body, kind):
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


os.makedirs(FOLDER, exist_ok=True)
http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Publisher).serve_forever()
