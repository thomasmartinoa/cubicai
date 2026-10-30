#!/usr/bin/env python3
"""CubicAI server: a private, authenticated HTTPS gateway from the CubicAI phone app to Ollama on this PC.

- Ollama stays bound to 127.0.0.1; only this gateway is reachable, and only from the home network
  (private addresses), plus Tailscale when remote access is switched on. Everyone else is dropped
  before the TLS handshake.
- Every request needs the pairing token; traffic is TLS 1.3 with a self-signed cert the app pins.
- Web search runs here (through a local SearXNG), so the phone never talks to search engines.
- Message content is never logged or stored.

Usage:
    cubicai_server.py serve [--port 8443] [--bind 0.0.0.0]   run the gateway
    cubicai_server.py pair [--url https://HOST:PORT]          show the pairing QR code / link
    cubicai_server.py remote on|off|status                    allow the paired phone in over Tailscale
    cubicai_server.py rotate                                  new token (un-pairs every phone)
"""

import argparse
import collections
import datetime
import hashlib
import hmac
import ipaddress
import json
import os
import re
import secrets
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from html.parser import HTMLParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

CONFIG_DIR = Path(os.environ.get("CUBICAI_CONFIG_DIR", "~/.config/cubicai-server")).expanduser()
OLLAMA_URL = os.environ.get("CUBICAI_OLLAMA_URL", "http://127.0.0.1:11434")
SEARXNG_URL = os.environ.get("CUBICAI_SEARXNG_URL", "http://127.0.0.1:8888")
DEFAULT_PORT = 8443
DEFAULT_CTX = int(os.environ.get("CUBICAI_NUM_CTX", "16384"))  # Ollama's own default is 4096; settings.json "num_ctx" overrides
MAX_BODY = 64 * 1024 * 1024
MAX_CONNECTIONS = 32
PRE_AUTH_TIMEOUT = 30
SEARCH_RESULTS = 8
PAGES_TO_READ = 4
PAGE_CHARS = 4000
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"


def log(msg):
    print(f"{datetime.datetime.now():%Y-%m-%d %H:%M:%S} {msg}", flush=True)


# --- Activity (metadata only, never message content) ---------------------------------------------

class Activity:
    """In-memory record of what the gateway is doing, for the CubicAI Server control app."""

    def __init__(self):
        self.lock = threading.Lock()
        self.started = time.time()
        self.active = {}
        self.recent = collections.deque(maxlen=200)
        self.devices = {}
        self.served = 0
        self.blocked = 0
        self._next = 0

    def begin(self, ip, model, search):
        with self.lock:
            self._next += 1
            self.active[self._next] = {"id": self._next, "ip": ip, "model": model, "search": search,
                                       "phase": "Starting", "started": time.time()}
            self.devices[ip] = time.time()
            return self._next

    def phase(self, rid, text):
        with self.lock:
            if rid in self.active:
                self.active[rid]["phase"] = text

    def end(self, rid, outcome, tokens=0, gen_seconds=0.0):
        with self.lock:
            item = self.active.pop(rid, None)
            if not item:
                return
            item.update(outcome=outcome, ended=time.time(), tokens=tokens,
                        tps=round(tokens / gen_seconds, 1) if gen_seconds else 0)
            item.pop("phase", None)
            self.recent.appendleft(item)
            self.served += 1

    def reject(self, ip):
        with self.lock:
            self.blocked += 1
            self.recent.appendleft({"ip": ip, "outcome": "blocked", "started": time.time(), "ended": time.time()})

    def snapshot(self):
        with self.lock:
            return {
                "uptime": time.time() - self.started,
                "served": self.served,
                "blocked": self.blocked,
                "active": list(self.active.values()),
                "recent": list(self.recent),
                "devices": [{"ip": ip, "last_seen": t} for ip, t in sorted(self.devices.items(), key=lambda x: -x[1])],
            }


ACTIVITY = Activity()


# --- Identity: TLS cert + token ------------------------------------------------------------------

def identity():
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    os.chmod(CONFIG_DIR, 0o700)
    cert, key, token_file = CONFIG_DIR / "cert.pem", CONFIG_DIR / "key.pem", CONFIG_DIR / "token"
    if not cert.exists() or not key.exists():
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
             "-nodes", "-keyout", str(key), "-out", str(cert), "-days", "3650", "-subj", "/CN=cubicai-server"],
            check=True, capture_output=True,
        )
        os.chmod(key, 0o600)
    if not token_file.exists():
        rotate_token()
    der = ssl.PEM_cert_to_DER_cert(cert.read_text())
    return cert, key, token_file.read_text().strip(), hashlib.sha256(der).hexdigest()


def rotate_token():
    token_file = CONFIG_DIR / "token"
    token_file.write_text(secrets.token_urlsafe(32))
    os.chmod(token_file, 0o600)


# --- Settings and who may connect ----------------------------------------------------------------

_settings_cache = {"mtime": None, "value": {}}


def load_settings():
    """settings.json in CONFIG_DIR, re-read when it changes so the control app's toggles apply live."""
    f = CONFIG_DIR / "settings.json"
    try:
        mtime = f.stat().st_mtime
    except OSError:
        return {}
    if mtime != _settings_cache["mtime"]:
        try:
            _settings_cache.update(mtime=mtime, value=json.loads(f.read_text()))
        except (OSError, ValueError):
            _settings_cache.update(mtime=mtime, value={})
    return _settings_cache["value"]


def save_settings(**changes):
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    f = CONFIG_DIR / "settings.json"
    tmp = f.with_suffix(".tmp")
    tmp.write_text(json.dumps({**load_settings(), **changes}, indent=2))
    os.chmod(tmp, 0o600)
    tmp.replace(f)


def remote_enabled():
    return bool(load_settings().get("remote"))


TAILNET = [ipaddress.ip_network("100.64.0.0/10"), ipaddress.ip_network("fd7a:115c:a1e0::/48")]
HOME = [ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7", "fe80::/10")]


def client_allowed(ip):
    """Loopback and home-network addresses always; Tailscale only while remote access is on."""
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    if getattr(addr, "ipv4_mapped", None):
        addr = addr.ipv4_mapped
    if addr.is_loopback:
        return True
    if any(addr in n for n in TAILNET):
        return remote_enabled()
    return any(addr in n for n in HOME)


_ts_cache = {"at": 0.0, "ip": None}


def tailscale_ip():
    """This PC's Tailscale IPv4 address, or None if Tailscale isn't installed or connected."""
    if time.time() - _ts_cache["at"] < 30:
        return _ts_cache["ip"]
    ip = None
    try:
        out = subprocess.run(["tailscale", "ip", "-4"], capture_output=True, text=True, timeout=5)
        if out.returncode == 0 and out.stdout.strip():
            ip = out.stdout.split()[0]
    except (OSError, subprocess.TimeoutExpired):
        pass
    _ts_cache.update(at=time.time(), ip=ip)
    return ip


def remote_url(port=DEFAULT_PORT):
    ip = tailscale_ip() if remote_enabled() else None
    return f"https://{ip}:{port}" if ip else None


def lan_ip():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("10.255.255.255", 1))  # no packet is sent; this just picks the outbound interface
            return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def pairing_link(url, token, fingerprint, remote=None):
    params = {"u": url, "t": token, "f": fingerprint}
    if remote:
        params["r"] = remote
    return "cubicai://pair?" + urllib.parse.urlencode(params)


def show_pairing(url, port=DEFAULT_PORT):
    _, _, token, fp = identity()
    remote = remote_url(port)
    link = pairing_link(url, token, fp, remote)
    print(f"\nPair the CubicAI app with this PC ({url}" + (f", remote {remote}" if remote else "") + "):")
    print("  CubicAI → Settings → Local PC → Scan pairing QR code\n")
    try:
        import qrcode
        qr = qrcode.QRCode(border=2)
        qr.add_data(link)
        qr.print_ascii(invert=True)
    except ImportError:
        print("  (install the 'qrcode' Python package to show a QR code here)")
    print("\nOr paste this link in the app (it contains your secret token, keep it private):")
    print(f"  {link}\n")


# --- Ollama helpers ------------------------------------------------------------------------------

_show_cache = {}
_show_lock = threading.Lock()


def ollama(path, payload=None, timeout=30):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(OLLAMA_URL + path, data=data, headers={"Content-Type": "application/json"})
    return urllib.request.urlopen(req, timeout=timeout)


def model_info(name):
    """Returns (capabilities, num_ctx set in its Modelfile or None, longest context it supports), cached."""
    with _show_lock:
        if name in _show_cache:
            return _show_cache[name]
    try:
        with ollama("/api/show", {"model": name}) as r:
            d = json.load(r)
        own = re.search(r"^num_ctx\s+(\d+)", d.get("parameters") or "", re.M)
        longest = max((v for k, v in (d.get("model_info") or {}).items() if k.endswith(".context_length")), default=0)
        info = (set(d.get("capabilities") or []), int(own.group(1)) if own else None, longest)
    except Exception:
        info = (set(), None, 0)
    with _show_lock:
        _show_cache[name] = info
    return info


def context_for(name):
    """Context window a request gets: the Modelfile's num_ctx if it sets one, else the configured default
    (capped at what the model supports). Longer remembers more of a chat but needs more GPU memory."""
    _, own, longest = model_info(name)
    if own:
        return own
    wanted = int(load_settings().get("num_ctx", DEFAULT_CTX))
    return min(wanted, longest) if longest else wanted


def gpu_memory():
    """Total VRAM in bytes (NVIDIA only), or None."""
    try:
        out = subprocess.run(["nvidia-smi", "--query-gpu=memory.total", "--format=csv,noheader,nounits"],
                             capture_output=True, text=True, timeout=5).stdout
        return int(out.split()[0]) * 1024 * 1024
    except Exception:
        return None


def list_models():
    with ollama("/api/tags") as r:
        tags = {m["name"]: m.get("size", 0) for m in json.load(r).get("models", [])}
    data = []
    for name in sorted(tags):
        caps, _, _ = model_info(name)
        if caps and "completion" not in caps:
            continue  # embedding models etc.
        data.append({
            "id": name,
            "object": "model",
            "owned_by": "local",
            "size": tags[name],
            "input_modalities": ["text"] + (["image"] if "vision" in caps else []),
            "context_length": context_for(name),
        })
    # Recommend the biggest vision model that fits in VRAM: smart enough, still fast.
    budget = gpu_memory() or 8 * 1024 ** 3
    vision = [m for m in data if "image" in m["input_modalities"] and m["size"]]
    fits = [m for m in vision if m["size"] <= budget]
    pick = max(fits, key=lambda m: m["size"]) if fits else min(vision, key=lambda m: m["size"], default=None)
    if pick:
        pick["cubic_recommended"] = True
    return {"object": "list", "data": data}


def loaded_models():
    try:
        with ollama("/api/ps") as r:
            return {m["name"] for m in json.load(r).get("models", [])}
    except Exception:
        return set()


def to_native(messages, vision):
    """OpenAI-style messages → Ollama /api/chat messages (images as bare base64)."""
    out = []
    last_user = max((i for i, m in enumerate(messages) if m.get("role") == "user"), default=-1)
    for i, m in enumerate(messages):
        content = m.get("content")
        if isinstance(content, str) or content is None:
            out.append({"role": m["role"], "content": content or ""})
            continue
        texts, images = [], []
        for part in content:
            if part.get("type") == "text":
                texts.append(part.get("text", ""))
            elif part.get("type") == "image_url":
                url = part["image_url"]["url"] if isinstance(part.get("image_url"), dict) else part.get("image_url", "")
                if url.startswith("data:"):
                    images.append(url.split(",", 1)[1])
        msg = {"role": m["role"], "content": "\n".join(texts)}
        if images and vision:
            msg["images"] = images
        elif images and i == last_user:
            raise ValueError("This model can't see images. Pick one marked \"sees images\" (e.g. gemma4 or qwen3.5).")
        elif images:
            msg["content"] += "\n[image omitted: current model has no vision]"
        out.append(msg)
    return out


def text_of(message):
    return message.get("content") or ""


# --- Web search ----------------------------------------------------------------------------------

def make_query(model, caps, messages):
    users = [m for m in messages if m["role"] == "user"]
    last = text_of(users[-1]) if users else ""
    recent = [m for m in messages if m["role"] in ("user", "assistant")][-5:-1]
    context = "\n".join(f"{m['role']}: {text_of(m)[:500]}" for m in recent)
    today = datetime.date.today().isoformat()
    payload = {
        "model": model,
        "stream": False,
        "messages": [
            {"role": "system", "content": f"Today is {today}. Rewrite the user's latest message as one concise web search query. "
                                          "Output only the query text, no quotes, no explanation."},
            {"role": "user", "content": f"Conversation so far:\n{context or '(none)'}\n\nLatest message: {last}"},
        ],
        "options": {"temperature": 0, "num_predict": 256},
    }
    if "thinking" in caps:
        payload["think"] = False
    try:
        try:
            r = ollama("/api/chat", payload, timeout=180)
        except urllib.error.HTTPError:
            payload.pop("think", None)
            r = ollama("/api/chat", payload, timeout=180)
        with r:
            query = json.load(r).get("message", {}).get("content", "")
        query = re.sub(r"<think>[\s\S]*?</think>", "", query).strip().splitlines()[0].strip().strip('"\'`')
    except Exception:
        query = ""
    return (query or last.strip())[:200]


def search(query):
    url = SEARXNG_URL + "/search?" + urllib.parse.urlencode({"q": query, "format": "json"})
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=15) as r:
            results = json.load(r).get("results", [])
    except Exception as e:
        log(f"search failed: {e.__class__.__name__}")
        return []
    seen, out = set(), []
    for x in results:
        u = x.get("url")
        if u and u not in seen:
            seen.add(u)
            out.append({"title": x.get("title", ""), "url": u, "snippet": x.get("content", "")})
        if len(out) >= SEARCH_RESULTS:
            break
    return out


class _TextExtractor(HTMLParser):
    SKIP = {"script", "style", "noscript", "svg", "nav", "footer", "header", "form", "aside", "iframe", "template"}
    BLOCK = {"p", "div", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "section", "article", "pre", "blockquote"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts, self.skip = [], 0

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self.skip += 1
        elif tag in self.BLOCK:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in self.SKIP and self.skip:
            self.skip -= 1
        elif tag in self.BLOCK:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self.skip:
            self.parts.append(data)

    def text(self):
        lines = (re.sub(r"[ \t\r\f\v]+", " ", l).strip() for l in "".join(self.parts).split("\n"))
        return "\n".join(l for l in lines if len(l) > 1)


def public_url(url):
    """True only for http(s) URLs whose host resolves solely to public addresses (no LAN, loopback, Ollama…)."""
    try:
        parts = urllib.parse.urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            return False
        infos = socket.getaddrinfo(parts.hostname, parts.port or (443 if parts.scheme == "https" else 80))
        return bool(infos) and all(ipaddress.ip_address(i[4][0].split("%")[0]).is_global for i in infos)
    except (OSError, ValueError):
        return False


class _PublicRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not public_url(newurl):
            raise urllib.error.URLError("redirect to a non-public address")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


_page_opener = urllib.request.build_opener(_PublicRedirects)


def read_page(url):
    # Search results are untrusted: never let one point this PC at the home network or itself.
    if not public_url(url):
        return ""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "text/html,text/plain"})
        with _page_opener.open(req, timeout=8) as r:
            ctype = r.headers.get("Content-Type", "")
            if "html" not in ctype and "text/plain" not in ctype:
                return ""
            raw = r.read(2 * 1024 * 1024)
            charset = r.headers.get_content_charset() or "utf-8"
        html = raw.decode(charset, errors="replace")
        if "html" not in ctype:
            return html[:PAGE_CHARS]
        parser = _TextExtractor()
        parser.feed(html)
        return parser.text()[:PAGE_CHARS]
    except Exception:
        return ""


def research(query):
    results = search(query)
    if not results:
        return [], ""
    with ThreadPoolExecutor(PAGES_TO_READ) as pool:
        pages = list(pool.map(read_page, [r["url"] for r in results[:PAGES_TO_READ]]))
    blocks = []
    for i, r in enumerate(results):
        body = pages[i] if i < len(pages) and pages[i] else r["snippet"]
        blocks.append(f"[{i + 1}] {r['title']}\nURL: {r['url']}\n{body}")
    now = datetime.datetime.now().strftime("%A %d %B %Y %H:%M")
    context = (
        f"Live web search results for \"{query}\" (retrieved {now}):\n\n" + "\n\n".join(blocks) +
        "\n\nUse these results to answer the user's latest message. Cite sources inline like [1] or [2], "
        "matching the numbers above. If the results don't answer it, say so and answer from your own knowledge."
    )
    return [r["url"] for r in results], context


# --- HTTP ----------------------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "cubicai"
    sys_version = ""

    def log_message(self, *args):
        pass  # never log request lines or bodies

    def _authorized(self):
        expected = f"Bearer {self.server.token}".encode()
        if hmac.compare_digest(self.headers.get("Authorization", "").encode(), expected):
            return True
        log(f"rejected unauthorized request from {self.client_address[0]}")
        ACTIVITY.reject(self.client_address[0])
        time.sleep(1)
        self._json(401, {"error": {"message": "Not paired: invalid token"}})
        return False

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self._authorized():
            return
        path = self.path.split("?")[0].rstrip("/")
        if path in ("/health", "/v1/health"):
            self._json(200, {"ok": True})
        elif path == "/v1/cubicai/status":
            self._json(200, ACTIVITY.snapshot())
        elif path == "/v1/models":
            try:
                self._json(200, list_models())
            except Exception as e:
                self._json(502, {"error": {"message": f"Can't reach Ollama: {e}"}})
        else:
            self._json(404, {"error": {"message": "Not found"}})

    def do_POST(self):
        if not self._authorized():
            return
        if self.path.split("?")[0].rstrip("/") != "/v1/chat/completions":
            self._json(404, {"error": {"message": "Not found"}})
            return
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            self._json(413, {"error": {"message": "Request too large"}})
            return
        try:
            body = json.loads(self.rfile.read(length))
        except ValueError:
            self._json(400, {"error": {"message": "Invalid JSON"}})
            return
        self.connection.settimeout(900)  # authorized: allow long generations
        self._chat(body)

    def _emit(self, obj):
        self.wfile.write(b"data: " + json.dumps(obj).encode() + b"\n\n")
        self.wfile.flush()

    def _chat(self, body):
        model = body.get("model", "")
        web = bool(body.get("cubic_web_search"))
        caps, _, _ = model_info(model)
        log(f"chat from {self.client_address[0]} model={model} search={web}")
        rid = ACTIVITY.begin(self.client_address[0], model, web)
        outcome, tokens, gen_seconds = "error", 0, 0.0
        try:
            outcome, tokens, gen_seconds = self._chat_stream(body, model, web, caps, rid)
        finally:
            ACTIVITY.end(rid, outcome, tokens, gen_seconds)

    def _chat_stream(self, body, model, web, caps, rid):
        """Streams one reply; returns (outcome, generated tokens, generation seconds)."""

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

        try:
            messages = to_native(body.get("messages", []), vision="vision" in caps)
            if model not in loaded_models():
                ACTIVITY.phase(rid, "Loading model")
                self._emit({"cubic_status": f"Loading {model.split(':')[0]} on your PC…"})
            if web:
                ACTIVITY.phase(rid, "Searching the web")
                self._emit({"cubic_status": "Searching the web…"})
                query = make_query(model, caps, messages)
                self._emit({"cubic_status": f"Searching: {query}"})
                urls, context = research(query)
                if urls:
                    self._emit({"citations": urls})
                    self._emit({"cubic_status": f"Reading {len(urls)} results…"})
                    ACTIVITY.phase(rid, f"Reading {len(urls)} results")
                else:
                    context = "Web search was requested but returned no results. Answer from your own knowledge and say so."
                if messages and messages[0]["role"] == "system":
                    messages[0]["content"] += "\n\n" + context
                else:
                    messages.insert(0, {"role": "system", "content": context})

            # Keep a model warm between chats; Ollama still evicts it early if another model needs the VRAM.
            payload = {"model": model, "messages": messages, "stream": True, "options": {},
                       "keep_alive": load_settings().get("keep_alive", "15m")}
            if "thinking" in caps:
                payload["think"] = True
            payload["options"]["num_ctx"] = context_for(model)
            ACTIVITY.phase(rid, "Waiting for model")
            tokens, gen_seconds = 0, 0.0
            with ollama("/api/chat", payload, timeout=900) as r:
                for line in r:
                    if not line.strip():
                        continue
                    chunk = json.loads(line)
                    if chunk.get("error"):
                        self._emit({"error": {"message": chunk["error"]}})
                        return "error", 0, 0.0
                    msg = chunk.get("message") or {}
                    delta = {}
                    if msg.get("thinking"):
                        delta["reasoning"] = msg["thinking"]
                        tokens += 1
                        if tokens == 1:
                            ACTIVITY.phase(rid, "Thinking")
                    if msg.get("content"):
                        delta["content"] = msg["content"]
                        ACTIVITY.phase(rid, "Answering")
                    if delta:
                        self._emit({"choices": [{"index": 0, "delta": delta}]})
                    if chunk.get("done"):
                        tokens = chunk.get("eval_count", tokens)
                        gen_seconds = chunk.get("eval_duration", 0) / 1e9
                        break
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            return "ok", tokens, gen_seconds
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
            log("client disconnected; generation stopped")  # closing the Ollama response cancels generation
            return "stopped", 0, 0.0
        except urllib.error.HTTPError as e:
            try:
                detail = json.loads(e.read()).get("error", str(e))
            except Exception:
                detail = str(e)
            self._safe_error(f"Ollama: {detail}")
        except ValueError as e:
            self._safe_error(str(e))
        except Exception as e:
            self._safe_error(f"{e.__class__.__name__}: {e}")
        return "error", 0, 0.0

    def _safe_error(self, message):
        try:
            self._emit({"error": {"message": message}})
        except Exception:
            pass


class TLSServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, tls, token):
        super().__init__(address, Handler)
        self.tls, self.token = tls, token
        self.slots = threading.BoundedSemaphore(MAX_CONNECTIONS)

    def verify_request(self, request, client_address):
        # Dropped before TLS: outsiders can't even see a certificate.
        if client_allowed(client_address[0]):
            return True
        log(f"dropped connection from {client_address[0]} (not home network" + ("" if remote_enabled() else " / remote off") + ")")
        ACTIVITY.reject(client_address[0])
        return False

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def finish_request(self, request, client_address):
        # TLS handshake happens here, on the worker thread, so one slow client can't block accept().
        request.settimeout(PRE_AUTH_TIMEOUT)
        try:
            conn = self.tls.wrap_socket(request, server_side=True)
        except (ssl.SSLError, OSError):
            return
        self.RequestHandlerClass(conn, client_address, self)


def serve(bind, port):
    cert, key, token, fp = identity()
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_3
    tls.load_cert_chain(cert, key)
    server = TLSServer((bind, port), tls, token)
    log(f"CubicAI server on https://{lan_ip()}:{port} (bind {bind}) → Ollama {OLLAMA_URL}, search {SEARXNG_URL}")
    log("remote access over Tailscale: " + (f"on ({tailscale_ip() or 'Tailscale not connected'})" if remote_enabled() else "off"))
    log(f"cert fingerprint {fp[:16]}…  — run '{Path(sys.argv[0]).name} pair' to pair a phone")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


def main():
    p = argparse.ArgumentParser(description="Private gateway from the CubicAI app to Ollama")
    sub = p.add_subparsers(dest="cmd")
    s = sub.add_parser("serve")
    s.add_argument("--bind", default="0.0.0.0")
    s.add_argument("--port", type=int, default=DEFAULT_PORT)
    pr = sub.add_parser("pair")
    pr.add_argument("--url", help="address the phone should use, e.g. https://192.168.1.20:8443")
    pr.add_argument("--port", type=int, default=DEFAULT_PORT)
    sub.add_parser("rotate")
    rm = sub.add_parser("remote")
    rm.add_argument("state", choices=["on", "off", "status"])
    a = p.parse_args()
    if a.cmd == "pair":
        show_pairing(a.url or f"https://{lan_ip()}:{a.port}", a.port)
    elif a.cmd == "remote":
        if a.state != "status":
            save_settings(remote=a.state == "on")
        ip = tailscale_ip()
        print(f"Remote access: {'on' if remote_enabled() else 'off'}; Tailscale: {ip or 'not connected'}")
        if a.state == "on":
            print("Pair the phone again so it learns the Tailscale address.")
    elif a.cmd == "rotate":
        identity()
        rotate_token()
        print("New token created. Restart the server and pair your phone again.")
    else:
        serve(getattr(a, "bind", "0.0.0.0"), getattr(a, "port", DEFAULT_PORT))


if __name__ == "__main__":
    main()
