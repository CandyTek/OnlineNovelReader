# Serves a generated mock site (gen.py) the way a bad site on a bad line does. Deterministic for a seed.
#   python tools/mock_site/chaos_server.py <dir> [port=8766] [seed=7]
# Per request, by a draw that depends on the path and on how often it was asked:
#   15%  status 503 with a short error page
#   10%  the connection is cut after half of the body
#   15%  the answer waits 4 to 9 seconds
#   60%  the body dribbles out in 2 KB pieces, 30 ms apart
# Every hit is logged, so retries (and their absence) can be read from the output.
# Book 88 is served as book 99 (address and links): the app knows a book by host and path without the port,
# so under its own number it would be the book of the plain server on the same PC, read from the cache.
import os, sys, time, random, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

root = os.path.abspath(sys.argv[1])
port = int(sys.argv[2]) if len(sys.argv) > 2 else 8766
seed = sys.argv[3] if len(sys.argv) > 3 else "7"
seen = {}
lock = threading.Lock()
ERR = ("<html><head><title>503 Service Unavailable</title></head><body><h1>503 Service Unavailable</h1>"
       "<p>The server is temporarily unable to service your request. Please try again later.</p></body></html>").encode()

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"
    def log_message(self, *a): pass
    def do_GET(self):
        path = self.path.split("?")[0]
        with lock:
            n = seen[path] = seen.get(path, 0) + 1
        f = os.path.normpath(os.path.join(root, path.replace("/book/99/", "/book/88/").lstrip("/")))
        if os.path.isdir(f): f = os.path.join(f, "index.html")
        if not f.startswith(root) or not os.path.isfile(f):
            print("404", path, flush=True)
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers(); return
        body = open(f, "rb").read().replace(b"/book/88/", b"/book/99/")
        draw = random.Random("%s|%s|%d" % (seed, path, n)).random()
        ctype = "text/html" if f.endswith(".html") else "application/octet-stream"
        try:
            if draw < 0.15:
                print("503 ", path, "#%d" % n, flush=True)
                self.send_response(503); self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(ERR))); self.end_headers(); self.wfile.write(ERR); return
            if draw < 0.25:
                print("cut ", path, "#%d" % n, flush=True)
                self.send_response(200); self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body))); self.end_headers()
                self.wfile.write(body[:len(body) // 2]); self.wfile.flush(); self.connection.close(); return
            if draw < 0.40:
                wait = 4 + 5 * (draw - 0.25) / 0.15
                print("wait", path, "#%d %.1fs" % (n, wait), flush=True)
                time.sleep(wait)
            else:
                print("slow", path, "#%d" % n, flush=True)
            self.send_response(200); self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body))); self.end_headers()
            for i in range(0, len(body), 2048):
                self.wfile.write(body[i:i + 2048]); self.wfile.flush(); time.sleep(0.03)
        except OSError:
            pass

ThreadingHTTPServer(("0.0.0.0", port), H).serve_forever()
