#!/usr/bin/env python3
import http.server
import socketserver
import os
import sys
import logging

PORT = 8080

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# Minimal handler with required WASM threading headers
class WASMHandler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        # Required headers for SharedArrayBuffer and WASM threading
        self.send_header("Cross-Origin-Opener-Policy", "same-origin")
        self.send_header("Cross-Origin-Embedder-Policy", "require-corp")
        super().end_headers()

logger.info(f"Starting server at http://localhost:{PORT}")
logger.info(f"Serving files from: {os.getcwd()}")
logger.info("Press Ctrl+C to stop")

# Create the server with address reuse
socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("", PORT), WASMHandler) as httpd:
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down server...")
        httpd.server_close()
        sys.exit(0)
