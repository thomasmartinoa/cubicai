"""Image, video, music and speech generation for the phone, run by ComfyUI on this PC.

- ComfyUI only listens on 127.0.0.1; the phone reaches it through the CubicAI gateway like chat.
- ComfyUI is started on demand (its own systemd unit) and stopped after a while with nothing to do.
- Nothing generated stays on the PC: results move from ComfyUI's folders into a private temp folder and are
  deleted as soon as the phone has downloaded them (or after an hour if it never does). Only what the user
  saves on the phone comes back to the PC, into SAVE_DIR.
- Prompts are never logged.
"""

import base64
import json
import os
import random
import re
import secrets
import shutil
import socket
import struct
import subprocess
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

from comfy_workflow import to_api

OLLAMA_URL = os.environ.get("CUBICAI_OLLAMA_URL", "http://127.0.0.1:11434")
COMFY_DIR = Path(os.environ.get("CUBICAI_COMFY_DIR", "/mnt/storage/games/AI_local/ComfyUI"))
COMFY_HOST, COMFY_PORT = "127.0.0.1", int(os.environ.get("CUBICAI_COMFY_PORT", "8188"))
COMFY_URL = f"http://{COMFY_HOST}:{COMFY_PORT}"
COMFY_UNIT = "cubicai-comfyui.service"
SAVE_DIR = Path(os.environ.get("CUBICAI_SAVE_DIR", "/mnt/storage/games/AI_local/CubicAI"))
CACHE_DIR = Path(os.environ.get("CUBICAI_CACHE_DIR", "~/.cache/cubicai-server")).expanduser()
TEMP_DIR = CACHE_DIR / "media"
INFO_CACHE = CACHE_DIR / "object_info.json"  # ComfyUI's node list, so models can be checked while it's stopped
WORKFLOWS = COMFY_DIR / "user" / "default" / "workflows"
SUBFOLDER = "cubicai"  # inside ComfyUI's input/ and output/, emptied after every job
IDLE_STOP = int(os.environ.get("CUBICAI_COMFY_IDLE", "600"))  # seconds
UNDELIVERED_TTL = 3600
START_TIMEOUT = 180
MAX_SAVE = 1024 * 1024 * 1024
SAVE_FOLDERS = {"image": "Images", "video": "Videos", "audio": "Music"}

# What the phone can make. Selectors: "#<node id>.<input>" for a node of the flattened workflow,
# "@<name>" for an input of the workflow's top-level subgraph. "fixed" values are always applied.
GENERATORS = [
    {"id": "qwen-uncensored", "name": "Qwen Image 2.1 Uncensored", "kind": "image", "mode": "create",
     "workflow": "qwen_image_2_1_UNCENSORED_t2i.json", "note": "Best quality, about 1½ min",
     "prompt": ["@prompt"], "negative": ["@negative_prompt"], "seed": ["@seed"],
     "steps": ["#459:458.steps"], "cfg": ["#459:458.cfg"],
     "size": {"width": ["@width"], "height": ["@height"], "side": 1024}},
    {"id": "z-image", "name": "Z-Image Turbo", "kind": "image", "mode": "create",
     "workflow": "image_z_image_turbo_int8.json", "note": "Fast, about 25 s",
     "prompt": ["@text"], "seed": ["@seed"],
     "size": {"width": ["@width"], "height": ["@height"], "side": 1024}},
    {"id": "flux3-klein", "name": "Flux 3 Klein", "kind": "image", "mode": "create",
     "workflow": "image_flux3_klein_distilled_t2i.json", "note": "Sharp detail, rewrites your prompt first, about 35 s",
     "prompt": ["@value"], "seed": ["@seed"],
     "size": {"width": ["@width"], "height": ["@height"], "side": 1024}},
    {"id": "flux2-klein", "name": "Flux 2 Klein", "kind": "image", "mode": "create",
     "workflow": "image_flux2_klein_text_to_image.json", "note": "Fastest, about 10 s",
     "prompt": ["#76.value"], "seed": ["#77:73.noise_seed"],
     "size": {"width": ["#77:68.value"], "height": ["#77:69.value"], "side": 1024}},
    {"id": "illustrious", "name": "Illustrious XL (anime)", "kind": "image", "mode": "create",
     "workflow": "my_illustrious_anime.json", "note": "Anime and illustration; use tags",
     "prompt": ["#10.text"], "negative": ["#11.text"], "seed": ["#12.seed"], "steps": ["#12.steps"], "cfg": ["#12.cfg"],
     "size": {"width": ["#13.width"], "height": ["#13.height"], "side": 1024, "multiple": 64}},
    {"id": "qwen-edit", "name": "Qwen Image Edit", "kind": "image", "mode": "edit",
     "workflow": "image_qwen_image_edit_2511_int8.json", "note": "Describe the change to your photo, about 45 s",
     "image": "required", "image_into": ["#41.image"],
     "prompt": ["@prompt"], "seed": ["@seed"], "fixed": {"#170:168.value": True}},
    {"id": "flux2-edit", "name": "Flux 2 Klein Edit", "kind": "image", "mode": "edit",
     "workflow": "image_flux2_klein_image_edit_4b_distilled.json", "note": "Quick edits to your photo, about 20 s",
     "image": "required", "image_into": ["#76.image"], "prompt": ["@text"], "seed": ["@noise_seed"]},
    {"id": "cutout", "name": "Remove background", "kind": "image", "mode": "tool",
     "workflow": "utility_birefnet_remove_background.json", "note": "Transparent PNG of the subject",
     "image": "required", "image_into": ["#17.image"]},
    {"id": "upscale", "name": "Upscale 2×", "kind": "image", "mode": "tool",
     "workflow": "utility_seedvr2_3b_int8_upscale_image.json", "note": "Sharper, twice the size",
     "image": "required", "image_into": ["#1.image"], "seed": ["@seed"],
     "fixed": {"@resize_type.multiplier": 2}},
    {"id": "wan-5b", "name": "Wan 2.2 5B", "kind": "video", "mode": "create",
     "workflow": "video_wan2_2_5B_ti2v.json", "note": "Text or photo to video, about 1 min per second",
     "image": "optional", "image_into": ["#55.start_image"],
     "prompt": ["#6.text"], "seed": ["#3.seed"], "steps": ["#3.steps"], "cfg": ["#3.cfg"],
     "size": {"width": ["#55.width"], "height": ["#55.height"], "side": 704, "multiple": 32},
     "seconds": {"into": ["#55.length"], "fps": 24, "default": 3, "max": 5}},
    {"id": "wan-14b", "name": "Wan 2.2 14B", "kind": "video", "mode": "animate",
     "workflow": "video_wan2_2_14B_i2v.json", "note": "Brings your photo to life, about 1 min per second",
     "image": "required", "image_into": ["#97.image"],
     "prompt": ["@text"], "seed": ["@noise_seed"], "fixed": {"#129:131.value": True},
     "size": {"width": ["@width"], "height": ["@height"], "side": 640, "multiple": 16},
     "seconds": {"into": ["#129:161.value"], "default": 4, "max": 5}},
    {"id": "ace-step", "name": "ACE-Step 1.5", "kind": "audio", "mode": "music",
     "workflow": "audio_ace_step_1_5_checkpoint.json", "note": "Describe the style; lyrics optional. About 15 s per minute",
     "prompt": ["#94.tags"], "lyrics": ["#94.lyrics"], "seed": ["#94.seed", "#3.seed"],
     "seconds": {"into": ["#94.duration", "#98.seconds"], "default": 60, "max": 240}},
    {"id": "chatterbox", "name": "Chatterbox voice", "kind": "audio", "mode": "speech",
     "workflow": "audio-chatterbox_tts_multilingual.json", "note": "Reads your text aloud",
     "prompt": ["#1.text"], "seed": ["#1.seed"], "language": ["#1.language"],
     # The node's default penalty (2.0) keeps it from ever stopping: a short sentence came back as 25 s of babble.
     "fixed": {"#1.repetition_penalty": 1.2},
     "audio": "optional", "audio_into": ["#2.audio"]},
]

# Steps and CFG are offered only where the workflow sets them (distilled models are tuned to CFG 1 and a few
# steps, and other values mostly ruin them). The defaults the phone shows are the workflow's own.
STEPS_RANGE = (4, 60)
CFG_RANGE = (1.0, 12.0)
SEED_MAX = 2 ** 32 - 1  # short enough to read and type, and within every seed input's range

# Tall shapes first, then wide: the app shows them four to a row.
ASPECTS = {"1:1": (1, 1), "3:4": (3, 4), "2:3": (2, 3), "9:16": (9, 16), "4:3": (4, 3), "3:2": (3, 2), "16:9": (16, 9)}
SAVE_NODES = {"SaveImage", "SaveImageAdvanced", "SaveVideo", "SaveAudio", "SaveAudioMP3", "SaveAudioOpus",
              "SaveAudioAdvanced", "SaveAnimatedWEBP", "VHS_VideoCombine"}
TERMINAL = {"done", "error", "cancelled"}


def _log(msg):
    print(f"{time.strftime('%Y-%m-%d %H:%M:%S')} media: {msg}", flush=True)


# --- ComfyUI process -----------------------------------------------------------------------------

def _comfy(path, body=None, timeout=30):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(COMFY_URL + path, data, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
    return json.loads(raw) if raw[:1] in (b"{", b"[") else raw


def comfy_running():
    try:
        _comfy("/system_stats", timeout=2)
        return True
    except Exception:
        return False


def _unit_state():
    return subprocess.run(["systemctl", "--user", "show", "-p", "ActiveState", "--value", COMFY_UNIT],
                          capture_output=True, text=True).stdout.strip()


def _unit_active():
    return subprocess.run(["systemctl", "--user", "is-active", "--quiet", COMFY_UNIT]).returncode == 0


class Comfy:
    """Starts ComfyUI when a job needs it and stops it again when it's been idle (only if we started it)."""

    def __init__(self):
        self.lock = threading.Lock()
        # Only CubicAI starts this unit (a ComfyUI opened by hand runs outside it), so one left running by an
        # earlier server process is ours to stop too.
        self.started_by_us = _unit_active()
        self.last_used = time.time()
        self.busy = 0
        self.dirty = False  # ComfyUI holds models in VRAM that chat (Ollama) may need back
        self._info = None
        threading.Thread(target=self._idle_watch, daemon=True).start()

    def ensure(self, on_status=lambda text: None):
        if comfy_running():
            return
        with self.lock:
            if comfy_running():
                return
            on_status("Starting ComfyUI on your PC…")
            _log("starting ComfyUI")
            # Right after a crash systemd can still call the dying unit "active" for a moment, so this start is a
            # no-op and the unit turns "failed" just after. The first failure seen is that leftover: clear it and
            # start again; only a second one is this start failing.
            deadline = time.time() + START_TIMEOUT
            for attempt in (1, 2):
                subprocess.run(["systemctl", "--user", "reset-failed", COMFY_UNIT], capture_output=True)
                r = subprocess.run(["systemctl", "--user", "start", COMFY_UNIT], capture_output=True, text=True)
                if r.returncode != 0:
                    raise RuntimeError("Couldn't start ComfyUI on your PC: " + (r.stderr.strip() or "systemctl failed"))
                self.started_by_us = True
                while time.time() < deadline:
                    if comfy_running():
                        return
                    if _unit_state() == "failed":
                        break
                    time.sleep(1)
                else:
                    break
            if _unit_state() == "failed":
                raise RuntimeError("ComfyUI failed to start on your PC. Check `journalctl --user -u cubicai-comfyui`.")
            raise RuntimeError("ComfyUI is taking too long to start on your PC.")

    def object_info(self):
        if self._info is None:
            self._info = _comfy("/object_info", timeout=60)
            try:
                INFO_CACHE.write_text(json.dumps(self._info))
            except OSError:
                pass
        return self._info

    def known_info(self):
        """The node list without starting ComfyUI: live if it's running, else the last one seen."""
        if self._info is None and comfy_running():
            try:
                return self.object_info()
            except Exception:
                pass
        if self._info is not None:
            return self._info
        try:
            return json.loads(INFO_CACHE.read_text())
        except (OSError, ValueError):
            return None

    def free_for_chat(self):
        """Gives the GPU back to Ollama once generation is over."""
        if self.dirty and self.busy == 0 and comfy_running():
            try:
                _comfy("/free", {"unload_models": True, "free_memory": True})
                self.dirty = False
            except Exception:
                return
            # A model loaded while ComfyUI held the card sits partly on the CPU (about 7x slower) until it
            # expires. Unloaded now, the next reply loads it onto the card properly.
            free_ollama(partial_only=True)

    def _idle_watch(self):
        while True:
            time.sleep(30)
            if self.started_by_us and self.busy == 0 and time.time() - self.last_used > IDLE_STOP:
                _log("stopping ComfyUI after being idle")
                subprocess.run(["systemctl", "--user", "stop", COMFY_UNIT], capture_output=True)
                self.started_by_us = False
                self.dirty = False
                self._info = None


COMFY = Comfy()


# --- Minimal WebSocket client (ComfyUI progress + previews) -------------------------------------

class _WebSocket:
    def __init__(self, path, timeout=10):
        self.sock = socket.create_connection((COMFY_HOST, COMFY_PORT), timeout=timeout)
        key = base64.b64encode(secrets.token_bytes(16)).decode()
        self.sock.sendall((f"GET {path} HTTP/1.1\r\nHost: {COMFY_HOST}:{COMFY_PORT}\r\nUpgrade: websocket\r\n"
                           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("websocket handshake failed")
            head += chunk
        if b" 101 " not in head.split(b"\r\n", 1)[0]:
            raise ConnectionError("websocket handshake refused")
        self.buf = head.split(b"\r\n\r\n", 1)[1]

    def _read(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("websocket closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _send(self, opcode, payload=b""):
        mask = secrets.token_bytes(4)
        head = bytes([0x80 | opcode, 0x80 | len(payload)])  # control frames only: payload < 126
        self.sock.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def recv(self):
        """Returns (opcode, payload) for the next text (1) or binary (2) message."""
        message, kind = b"", None
        while True:
            b1, b2 = self._read(2)
            fin, opcode, n = b1 & 0x80, b1 & 0x0F, b2 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._read(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._read(8))[0]
            payload = self._read(n)
            if opcode == 8:
                raise ConnectionError("websocket closed")
            if opcode == 9:
                self._send(10, payload)
                continue
            if opcode in (1, 2):
                kind, message = opcode, payload
            elif opcode == 0:
                message += payload
            if fin and kind:
                return kind, message

    def close(self):
        try:
            self._send(8)
        except OSError:
            pass
        self.sock.close()


# --- Building a prompt from a workflow ----------------------------------------------------------

def _targets(prompt, named, selector):
    if selector.startswith("@"):
        return named.get(selector[1:], [])
    node, _, name = selector[1:].partition(".")
    return [(node, name)] if node in prompt else []


def _set(prompt, named, selectors, value):
    for sel in selectors:
        for node, name in _targets(prompt, named, sel):
            prompt[node]["inputs"][name] = value


def _size(aspect, side, multiple=16):
    w, h = ASPECTS.get(aspect, (1, 1))
    area = side * side
    width = (area * w / h) ** 0.5
    height = width * h / w
    snap = lambda v: max(multiple, int(round(v / multiple)) * multiple)
    return snap(width), snap(height)


def _load_node(prompt, kind, filename):
    node_id = f"cubicai_{kind}"
    prompt[node_id] = {"class_type": "LoadImage" if kind == "image" else "LoadAudio",
                       "inputs": {"image" if kind == "image" else "audio": filename}, "_meta": {"title": "CubicAI input"}}
    return node_id


def _attach(prompt, named, selectors, kind, filename):
    """Points the workflow's loader at the uploaded file, or wires in a loader where the input expects an image."""
    loader_input = "image" if kind == "image" else "audio"
    for sel in selectors:
        for node, name in _targets(prompt, named, sel):
            if prompt[node]["class_type"] in ("LoadImage", "LoadAudio") and name == loader_input:
                prompt[node]["inputs"][name] = filename
            else:
                prompt[node]["inputs"][name] = [_load_node(prompt, kind, filename), 0]


def _detach(prompt, named, selectors):
    """No file given for an optional input: drop the loader and every link to it."""
    for sel in selectors:
        for node, name in _targets(prompt, named, sel):
            if prompt[node]["class_type"] in ("LoadImage", "LoadAudio"):
                prompt.pop(node, None)
                for other in prompt.values():
                    for k, v in list(other["inputs"].items()):
                        if isinstance(v, list) and v and v[0] == node:
                            del other["inputs"][k]
            else:
                prompt[node]["inputs"].pop(name, None)


def _reachable(prompt, outputs):
    keep, todo = set(), list(outputs)
    while todo:
        node = todo.pop()
        if node in keep or node not in prompt:
            continue
        keep.add(node)
        todo += [v[0] for v in prompt[node]["inputs"].values() if isinstance(v, list) and len(v) == 2 and isinstance(v[0], str)]
    return keep


def build(gen, params, job_id, image_name=None, audio_name=None):
    workflow = json.loads((WORKFLOWS / gen["workflow"]).read_text())
    prompt, named = to_api(workflow, COMFY.object_info())
    text = (params.get("prompt") or "").strip()
    if gen.get("prompt"):
        if not text:
            raise ValueError("Type what you want to make.")
        _set(prompt, named, gen["prompt"], text)
    if gen.get("negative") and params.get("negative") is not None:
        _set(prompt, named, gen["negative"], str(params["negative"]))
    if gen.get("lyrics"):
        _set(prompt, named, gen["lyrics"], (params.get("lyrics") or "").strip() or "[Instrumental]")
    if gen.get("language") and params.get("language"):
        _set(prompt, named, gen["language"], params["language"])
    seed = params.get("seed")
    seed = int(seed) if isinstance(seed, int) and not isinstance(seed, bool) and seed >= 0 else random.randrange(SEED_MAX + 1)
    info = COMFY.known_info() or {}
    for sel in gen.get("seed", []):
        for node, name in _targets(prompt, named, sel):
            # Seed ranges differ per node (Chatterbox stops at 2^32 - 1).
            spec = info.get(prompt[node]["class_type"], {}).get("input", {})
            entry = (spec.get("required") or {}).get(name) or (spec.get("optional") or {}).get(name)
            top = entry[1].get("max") if entry and len(entry) > 1 and isinstance(entry[1], dict) else None
            prompt[node]["inputs"][name] = seed % (int(top) + 1) if top else seed
    for key, (low, high), kind in (("steps", STEPS_RANGE, int), ("cfg", CFG_RANGE, float)):
        value = params.get(key)
        if gen.get(key) and isinstance(value, (int, float)) and not isinstance(value, bool):
            _set(prompt, named, gen[key], kind(min(max(value, low), high)))
    if gen.get("size"):
        s = gen["size"]
        width, height = _size(params.get("aspect", "1:1"), s["side"], s.get("multiple", 16))
        _set(prompt, named, s["width"], width)
        _set(prompt, named, s["height"], height)
    if gen.get("seconds"):
        s = gen["seconds"]
        seconds = max(1, min(int(params.get("seconds") or s["default"]), s["max"]))
        _set(prompt, named, s["into"], seconds * s["fps"] + 1 if "fps" in s else seconds)
    for sel, value in gen.get("fixed", {}).items():
        _set(prompt, named, [sel], value)
    for kind, name in (("image", image_name), ("audio", audio_name)):
        if gen.get(kind):
            if name:
                _attach(prompt, named, gen[f"{kind}_into"], kind, name)
            elif gen[kind] == "required":
                raise ValueError("Add a photo to use this." if kind == "image" else "Add a voice sample to use this.")
            else:
                _detach(prompt, named, gen[f"{kind}_into"])
    outputs = [k for k, v in prompt.items() if v["class_type"] in SAVE_NODES]
    if not outputs:
        raise ValueError(f"{gen['workflow']} has no save node")
    for k in outputs:
        prompt[k]["inputs"]["filename_prefix"] = f"{SUBFOLDER}/{job_id}"
    keep = _reachable(prompt, outputs)  # previews, comparisons and unused loaders aren't run
    return {k: v for k, v in prompt.items() if k in keep}


def available(gen, info):
    """Whether a generator's workflow and models are on this PC (checked against ComfyUI's model lists)."""
    path = WORKFLOWS / gen["workflow"]
    if not path.exists():
        return False
    if info is None:
        return True
    try:
        prompt, _ = to_api(json.loads(path.read_text()), info)
    except Exception:
        return False
    for node in prompt.values():
        spec = info.get(node["class_type"], {}).get("input", {})
        for section in ("required", "optional"):
            for name, entry in (spec.get(section) or {}).items():
                value = node["inputs"].get(name)
                if not (name.endswith("_name") and isinstance(value, str)):
                    continue
                options = entry[0] if isinstance(entry[0], list) else (entry[1] or {}).get("options") if len(entry) > 1 else None
                if isinstance(options, list) and options and value not in options:
                    return False
    return True


_DEFAULTS = {}


def _defaults(gen, info):
    """The workflow's own steps, CFG and negative prompt, which the phone shows as the starting values."""
    path = WORKFLOWS / gen["workflow"]
    key = (gen["id"], path.stat().st_mtime if path.exists() else 0)
    if key not in _DEFAULTS:
        found = {}
        try:
            prompt, named = to_api(json.loads(path.read_text()), info)
            for name in ("steps", "cfg", "negative"):
                for sel in gen.get(name, []):
                    for node, field in _targets(prompt, named, sel):
                        value = prompt[node]["inputs"].get(field)
                        if isinstance(value, (int, float, str)) and not isinstance(value, bool):
                            found.setdefault(name, round(value, 2) if isinstance(value, float) else value)
        except Exception:
            pass
        _DEFAULTS[key] = found
    return _DEFAULTS[key]


def generators():
    info = COMFY.known_info()
    out = []
    for g in GENERATORS:
        if not available(g, info):
            continue
        item = {k: g[k] for k in ("id", "name", "kind", "mode", "note")}
        item["image"] = g.get("image", "none")
        item["audio"] = g.get("audio", "none")
        item["prompt"] = bool(g.get("prompt"))
        item["negative"] = bool(g.get("negative"))
        item["lyrics"] = bool(g.get("lyrics"))
        item["aspects"] = list(ASPECTS) if g.get("size") else []
        item["seed"] = bool(g.get("seed"))
        defaults = _defaults(g, info)
        if "steps" in defaults:
            item["steps"] = {"default": defaults["steps"], "min": STEPS_RANGE[0], "max": STEPS_RANGE[1]}
        if "cfg" in defaults:
            item["cfg"] = {"default": defaults["cfg"], "min": CFG_RANGE[0], "max": CFG_RANGE[1]}
        if "negative" in defaults:
            item["negative_default"] = defaults["negative"]
        if g.get("seconds"):
            item["seconds"] = {"default": g["seconds"]["default"], "max": g["seconds"]["max"]}
        if g.get("language") and info:
            node_type = "FL_ChatterboxMultilingualTTS"
            entry = info.get(node_type, {}).get("input", {}).get("required", {}).get("language")
            if entry and isinstance(entry[0], list):
                item["languages"] = entry[0]
        out.append(item)
    return out


# --- Jobs ----------------------------------------------------------------------------------------

PHASES = [("Loader", "Loading models"), ("TextGenerate", "Improving your prompt"), ("TextEncode", "Reading your prompt"),
          ("CLIPTextEncode", "Reading your prompt"), ("KSampler", "Generating"), ("SamplerCustom", "Generating"),
          ("VAEDecode", "Finishing"), ("CreateVideo", "Making the video"), ("Save", "Saving"),
          ("Chatterbox", "Speaking"), ("RemoveBackground", "Cutting out"), ("SeedVR2", "Upscaling")]


HEAVY = ("Sampler", "TTS", "Chatterbox", "BiRefNet", "SeedVR", "AceStep", "Upscale")


def _weight(class_type):
    """Rough share of a run's time a node takes."""
    if "Select" in class_type:  # KSamplerSelect only picks a sampler
        return 0.1
    if any(h in class_type for h in HEAVY):
        return 8.0
    if "Loader" in class_type or class_type.startswith("Load") or "Decode" in class_type or "Encode" in class_type:
        return 1.0
    return 0.1


def _phase(class_type):
    if "Select" in class_type:
        return "Working"
    for key, label in PHASES:
        if key in class_type:
            return label
    return "Working"


class Job:
    def __init__(self, gen, params, ip):
        self.id = secrets.token_urlsafe(12)
        self.gen, self.params, self.ip = gen, params, ip
        self.created = time.time()
        self.state, self.phase, self.step, self.steps, self.progress = "queued", "Waiting for your other creation", 0, 0, 0.0
        # Chosen now, so the phone can show it and use it again for a variation of the same picture.
        seed = params.get("seed")
        self.seed = seed if isinstance(seed, int) and not isinstance(seed, bool) and 0 <= seed <= SEED_MAX \
            else random.randrange(SEED_MAX + 1)
        self.params = {**params, "seed": self.seed}
        self.error, self.files, self.preview, self.prompt_id = None, [], None, None
        self.cond = threading.Condition()
        self.version = 0
        self.cancel_requested = False
        self.dir = TEMP_DIR / self.id
        self.inputs = []  # files placed in ComfyUI's input folder for this job
        self.on_change = None

    def update(self, **kw):
        with self.cond:
            for k, v in kw.items():
                setattr(self, k, v)
            self.version += 1
            self.cond.notify_all()
        if self.on_change and ("state" in kw or "phase" in kw):
            self.on_change(self)

    def snapshot(self, preview=False):
        snap = {"id": self.id, "generator": self.gen["id"], "kind": self.gen["kind"], "state": self.state,
                "phase": self.phase, "step": self.step, "steps": self.steps, "progress": round(self.progress, 3),
                "files": [{k: f[k] for k in ("index", "name", "mime", "size", "kind")} for f in self.files]}
        if self.gen.get("seed"):
            snap["seed"] = self.seed
        if self.error:
            snap["error"] = self.error
        if preview and self.preview:
            snap["preview"] = base64.b64encode(self.preview).decode()
        return snap


class Jobs:
    def __init__(self):
        self.lock = threading.Lock()
        self.jobs = {}
        self.queue = []
        self.wake = threading.Condition(self.lock)
        TEMP_DIR.mkdir(parents=True, exist_ok=True)
        os.chmod(CACHE_DIR, 0o700)
        os.chmod(TEMP_DIR, 0o700)
        self._wipe_leftovers()
        threading.Thread(target=self._worker, daemon=True).start()
        threading.Thread(target=self._janitor, daemon=True).start()

    def _wipe_leftovers(self):
        # A job an earlier gateway left running in our own ComfyUI has nobody waiting for it: stop it before it
        # saves anything. (A ComfyUI started by hand runs outside the unit and is left alone.)
        if _unit_active() and comfy_running():
            try:
                _comfy("/queue", {"clear": True})
                _comfy("/interrupt", {})
            except Exception:
                pass
        # Anything from a previous run was never delivered: it goes.
        for p in TEMP_DIR.iterdir():
            shutil.rmtree(p, ignore_errors=True)
        for kind in ("input", "output"):
            folder = COMFY_DIR / kind / SUBFOLDER  # kept itself: the gateway's sandbox grants exactly this folder
            for p in folder.glob("*"):
                shutil.rmtree(p, ignore_errors=True) if p.is_dir() else p.unlink(missing_ok=True)

    def submit(self, gen_id, params, image_bytes, audio_bytes, ip):
        gen = next((g for g in GENERATORS if g["id"] == gen_id), None)
        if gen is None:
            raise ValueError("That generator isn't available on your PC.")
        if gen.get("image") == "required" and not image_bytes:
            raise ValueError("Add a photo to use this.")
        job = Job(gen, params, ip)
        job.dir.mkdir(mode=0o700)
        job.image, job.audio = image_bytes, audio_bytes
        with self.lock:
            self.jobs[job.id] = job
            self.queue.append(job)
            self.wake.notify()
        _log(f"job {job.id} queued ({gen['id']}) from {ip}")
        return job

    def get(self, job_id):
        with self.lock:
            return self.jobs.get(job_id)

    def cancel(self, job_id):
        job = self.get(job_id)
        if job is None:
            return False
        job.cancel_requested = True
        _log(f"job {job.id} cancelled")
        with self.lock:
            if job in self.queue:
                self.queue.remove(job)
                job.update(state="cancelled", phase="Cancelled")
        if job.prompt_id and job.state not in TERMINAL:
            try:
                _comfy("/queue", {"delete": [job.prompt_id]})
                _comfy("/interrupt", {"prompt_id": job.prompt_id})
            except Exception:
                pass
        self.forget(job)
        return True

    def forget(self, job):
        with self.lock:
            self.jobs.pop(job.id, None)
        shutil.rmtree(job.dir, ignore_errors=True)

    def delivered(self, job, index):
        f = next((f for f in job.files if f["index"] == index), None)
        if f is None:
            return
        f["delivered"] = True
        if all(x.get("delivered") for x in job.files):
            _log(f"job {job.id} delivered; temp files deleted")
            self.forget(job)

    def _janitor(self):
        while True:
            time.sleep(60)
            now = time.time()
            with self.lock:
                stale = [j for j in self.jobs.values() if j.state in TERMINAL and now - j.created > UNDELIVERED_TTL]
            for job in stale:
                _log(f"job {job.id} never collected; deleted")
                self.forget(job)

    def _worker(self):
        while True:
            with self.lock:
                while not self.queue:
                    self.wake.wait()
                job = self.queue.pop(0)
            COMFY.busy += 1
            try:
                self._run(job)
            except Exception as e:
                message = str(e) if isinstance(e, (ValueError, RuntimeError)) else f"{e.__class__.__name__}: {e}"
                job.update(state="error", phase="Failed", error=message)
                _log(f"job {job.id} failed: {e.__class__.__name__}")
            finally:
                COMFY.busy -= 1
                COMFY.last_used = time.time()
                for p in job.inputs:
                    Path(p).unlink(missing_ok=True)
                # Whatever wasn't collected (cancelled, failed half-way) mustn't stay in ComfyUI's output.
                for p in (COMFY_DIR / "output" / SUBFOLDER).glob(f"{job.id}*"):
                    p.unlink(missing_ok=True)
                job.image = job.audio = None

    def _place_input(self, job, data, suffix):
        folder = COMFY_DIR / "input" / SUBFOLDER
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / f"{job.id}{suffix}"
        path.write_bytes(data)
        job.inputs.append(path)
        return f"{SUBFOLDER}/{path.name}"

    def _run(self, job):
        if job.cancel_requested:
            return
        job.update(state="starting", phase="Getting your PC ready")
        COMFY.ensure(lambda text: job.update(phase=text))
        free_ollama()
        image = self._place_input(job, job.image, _suffix(job.image, ".png")) if job.image else None
        audio = self._place_input(job, job.audio, _suffix(job.audio, ".wav")) if job.audio else None
        prompt = build(job.gen, job.params, job.id, image, audio)
        client_id = secrets.token_hex(8)
        ws = _WebSocket(f"/ws?clientId={client_id}")
        try:
            try:
                queued = _comfy("/prompt", {"prompt": prompt, "client_id": client_id})
            except urllib.error.HTTPError as e:
                raise RuntimeError(_comfy_error(e)) from None
            job.prompt_id = queued["prompt_id"]
            COMFY.dirty = True
            job.update(state="running", phase="Waiting for ComfyUI")
            try:
                self._follow(job, ws, prompt)
            except (ConnectionError, OSError) as e:
                if job.cancel_requested:
                    return
                raise RuntimeError("ComfyUI stopped while making this (it may have run out of memory). "
                                   "Tap Retry to try again.") from e
        finally:
            ws.close()
        if job.state == "cancelled" or job.cancel_requested:
            self._clear_history(job)
            return
        self._collect(job)

    def _follow(self, job, ws, prompt):
        # The bar moves by how long each node usually takes, so it doesn't race through loaders and then
        # sit still while the sampler does the real work.
        weights = {n: _weight(v.get("class_type", "")) for n, v in prompt.items()}
        total = sum(weights.values()) or 1.0
        done_nodes, current, last_preview = set(), None, 0.0

        def finished():
            return sum(weights.get(n, 0) for n in done_nodes if n != current) / total

        ws.sock.settimeout(30)
        while True:
            try:
                kind, data = ws.recv()
            except socket.timeout:
                if job.cancel_requested:
                    return
                continue
            if kind == 2:
                image = _preview(data)
                if image and time.time() - last_preview > 1.0:
                    last_preview = time.time()
                    job.update(preview=image)
                continue
            msg = json.loads(data)
            d = msg.get("data") or {}
            if d.get("prompt_id") not in (None, job.prompt_id):
                continue
            t = msg.get("type")
            if t == "executing" and d.get("node") is None and d.get("prompt_id") == job.prompt_id:
                return
            if t == "executing" and d.get("node"):
                current = str(d["node"])
                done_nodes.add(current)
                job.update(phase=_phase(prompt.get(current, {}).get("class_type", "")), step=0, steps=0,
                           progress=min(0.99, finished()))
            elif t == "execution_cached":
                done_nodes.update(str(n) for n in d.get("nodes", []))
            elif t == "progress":
                value, maximum = d.get("value", 0), d.get("max", 0)
                part = weights.get(current, 0) * (value / maximum if maximum else 0) / total
                job.update(step=value, steps=maximum, progress=min(0.99, finished() + part))
            elif t == "execution_success":
                return
            elif t == "execution_error":
                raise RuntimeError(f"ComfyUI: {d.get('exception_message', 'generation failed').strip()[:300]}")
            elif t == "execution_interrupted":
                job.update(state="cancelled", phase="Cancelled")
                return

    def _collect(self, job):
        history = _comfy(f"/history/{job.prompt_id}").get(job.prompt_id, {})
        files = []
        for out in history.get("outputs", {}).values():
            for items in out.values():
                if not isinstance(items, list):
                    continue
                for item in items:
                    if not (isinstance(item, dict) and item.get("filename")):
                        continue
                    base = COMFY_DIR / ("temp" if item.get("type") == "temp" else "output")
                    src = (base / item.get("subfolder", "") / item["filename"]).resolve()
                    if base.resolve() not in src.parents or not src.exists():
                        continue
                    index = len(files)
                    dest = job.dir / f"{index}{src.suffix.lower()}"
                    shutil.move(str(src), dest)
                    files.append({"index": index, "name": f"{job.gen['id']}-{job.id[:6]}-{index + 1}{src.suffix.lower()}",
                                  "mime": _mime(src.suffix.lower()), "size": dest.stat().st_size,
                                  "kind": _kind(src.suffix.lower()), "path": dest})
        self._clear_history(job)
        if not files:
            raise RuntimeError("ComfyUI finished but made no file.")
        job.update(state="done", phase="Done", progress=1.0, files=files, preview=None)
        _log(f"job {job.id} done: {len(files)} file(s)")

    def _clear_history(self, job):
        try:
            _comfy("/history", {"delete": [job.prompt_id]})
        except Exception:
            pass


def _comfy_error(e):
    try:
        body = json.loads(e.read())
        errors = body.get("node_errors") or {}
        for node in errors.values():
            for err in node.get("errors", []):
                return f"ComfyUI rejected the workflow: {err.get('message')} ({node.get('class_type')})"
        return f"ComfyUI rejected the workflow: {body.get('error', {}).get('message', e)}"
    except Exception:
        return f"ComfyUI rejected the workflow ({e.code})"


def _preview(data):
    if len(data) < 8:
        return None
    event = struct.unpack(">I", data[:4])[0]
    if event == 1:  # PREVIEW_IMAGE: type, then the image
        return data[8:]
    if event == 4:  # PREVIEW_IMAGE_WITH_METADATA: metadata length, metadata, image
        n = struct.unpack(">I", data[4:8])[0]
        return data[8 + n:]
    return None


def _suffix(data, default):
    if data[4:8] == b"ftyp":
        return ".m4a"
    sig = {b"\x89PNG": ".png", b"\xff\xd8\xff": ".jpg", b"RIFF": ".wav", b"ID3": ".mp3", b"fLaC": ".flac", b"OggS": ".ogg"}
    for magic, suffix in sig.items():
        if data.startswith(magic):
            return ".webp" if magic == b"RIFF" and data[8:12] == b"WEBP" else suffix
    return default


MIMES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp",
         ".mp4": "video/mp4", ".webm": "video/webm", ".gif": "image/gif",
         ".flac": "audio/flac", ".mp3": "audio/mpeg", ".wav": "audio/wav", ".opus": "audio/ogg", ".ogg": "audio/ogg"}


def _mime(suffix):
    return MIMES.get(suffix, "application/octet-stream")


def _kind(suffix):
    return _mime(suffix).split("/")[0] if _mime(suffix) != "image/gif" else "video"


def free_ollama(partial_only=False):
    """Unloads Ollama's models so ComfyUI gets the whole GPU (or only those not fully on it)."""
    try:
        with urllib.request.urlopen(OLLAMA_URL + "/api/ps", timeout=3) as r:
            models = [m["name"] for m in json.load(r).get("models", [])
                      if not partial_only or m.get("size_vram", 0) < m.get("size", 0)]
        for m in models:
            req = urllib.request.Request(OLLAMA_URL + "/api/generate", json.dumps({"model": m, "keep_alive": 0}).encode(),
                                         {"Content-Type": "application/json"})
            urllib.request.urlopen(req, timeout=30).read()
    except Exception:
        pass


def save(kind, name, stream, length):
    """Writes a file the user saved on the phone into SAVE_DIR; returns the path relative to it."""
    if kind not in SAVE_FOLDERS:
        raise ValueError("Unknown kind")
    if length <= 0 or length > MAX_SAVE:
        raise ValueError("File too large")
    stem = re.sub(r"[^A-Za-z0-9._ -]", "_", Path(name).name).strip(" .") or "cubicai"
    folder = SAVE_DIR / SAVE_FOLDERS[kind]
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / stem
    n = 1
    while path.exists():
        path = folder / f"{Path(stem).stem} ({n}){Path(stem).suffix}"
        n += 1
    left = length
    with open(path, "xb") as f:
        while left > 0:
            chunk = stream.read(min(left, 1 << 20))
            if not chunk:
                f.close()
                path.unlink(missing_ok=True)
                raise ValueError("Upload cut short")
            f.write(chunk)
            left -= len(chunk)
    return str(path.relative_to(SAVE_DIR))


JOBS = None


def jobs():
    global JOBS
    if JOBS is None:
        JOBS = Jobs()
    return JOBS
