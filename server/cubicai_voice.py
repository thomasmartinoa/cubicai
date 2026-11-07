"""Voice calls: the PC is the call's ears and mouth, the phone its brain.

The phone streams its microphone here (16 kHz PCM over a WebSocket). Silero VAD hears when the user starts and
stops talking, Parakeet turns the words into text, and the phone gets them as `partial`/`final` events. The phone
then writes the reply with its usual chat pipeline (behaviour, history, the create tool) and sends the words back
as they stream in (`say`); Kokoro speaks them sentence by sentence, so the voice starts before the reply is done.
Everything runs on the CPU (ONNX), so the chat model keeps the GPU.

Nothing that is said is logged or stored. Models live in ~/.local/share/cubicai-server/voice (`voice-setup`).
"""

import importlib.util
import json
import os
import queue
import re
import struct
import threading
import time
from collections import deque
from pathlib import Path

VOICE_DIR = Path(os.environ.get("CUBICAI_VOICE_DIR", "~/.local/share/cubicai-server/voice")).expanduser()
ASR_DIR = VOICE_DIR / "parakeet-tdt-0.6b-v3"
VAD_FILE = VOICE_DIR / "silero_vad.onnx"
TTS_MODEL = VOICE_DIR / "kokoro-v1.0.onnx"
TTS_VOICES = VOICE_DIR / "voices-v1.0.bin"
ESPEAK_LIB = os.environ.get("CUBICAI_ESPEAK_LIB", "/usr/lib/libespeak-ng.so.1")
ESPEAK_DATA = os.environ.get("CUBICAI_ESPEAK_DATA", "/usr/share/espeak-ng-data")
IDLE_UNLOAD = int(os.environ.get("CUBICAI_VOICE_IDLE", "900"))  # seconds without a call before the models leave RAM

IN_RATE = 16000
OUT_RATE = 24000
FRAME = 512  # Silero's frame at 16 kHz: 32 ms
FRAME_MS = FRAME * 1000 // IN_RATE
PREROLL_MS = 320  # kept from before speech is detected, so the first word isn't clipped
MAX_UTTERANCE_S = 30
PARTIAL_EVERY_MS = 900
DEBUG_LEVELS = os.environ.get("CUBICAI_VOICE_DEBUG") == "1"
SEND_CHUNK = OUT_RATE // 5  # samples of voice per binary frame (200 ms), so a cancel takes effect quickly

# Downloads for `voice-setup` (file name → URL). Parakeet comes from Hugging Face (int8, CPU-friendly).
ASR_REPO = "istupakov/parakeet-tdt-0.6b-v3-onnx"
ASR_FILES = ["encoder-model.int8.onnx", "decoder_joint-model.int8.onnx", "vocab.txt", "config.json"]
VAD_REPO, VAD_NAME = "istupakov/silero-vad-onnx", "silero_vad.onnx"
TTS_URLS = {
    TTS_MODEL.name: "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx",
    TTS_VOICES.name: "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin",
}

# The voices the phone offers: Kokoro's best, with a word on how each sounds. The first is the default.
VOICES = [
    {"id": "af_heart", "name": "Heart", "about": "Warm and upbeat", "gender": "woman", "accent": "American"},
    {"id": "af_bella", "name": "Bella", "about": "Bright and expressive", "gender": "woman", "accent": "American"},
    {"id": "af_nicole", "name": "Nicole", "about": "Soft and close, almost a whisper", "gender": "woman", "accent": "American"},
    {"id": "af_aoede", "name": "Aoede", "about": "Calm and soothing", "gender": "woman", "accent": "American"},
    {"id": "af_kore", "name": "Kore", "about": "Clear and confident", "gender": "woman", "accent": "American"},
    {"id": "af_sarah", "name": "Sarah", "about": "Friendly, everyday", "gender": "woman", "accent": "American"},
    {"id": "af_sky", "name": "Sky", "about": "Light and young", "gender": "woman", "accent": "American"},
    {"id": "bf_emma", "name": "Emma", "about": "Composed and polished", "gender": "woman", "accent": "British"},
    {"id": "bf_isabella", "name": "Isabella", "about": "Warm, a little posh", "gender": "woman", "accent": "British"},
    {"id": "am_michael", "name": "Michael", "about": "Calm and steady", "gender": "man", "accent": "American"},
    {"id": "am_fenrir", "name": "Fenrir", "about": "Deep and rough", "gender": "man", "accent": "American"},
    {"id": "am_puck", "name": "Puck", "about": "Playful and quick", "gender": "man", "accent": "American"},
    {"id": "am_eric", "name": "Eric", "about": "Smooth and laid-back", "gender": "man", "accent": "American"},
    {"id": "am_onyx", "name": "Onyx", "about": "Low and smooth", "gender": "man", "accent": "American"},
    {"id": "bm_george", "name": "George", "about": "Classic narrator", "gender": "man", "accent": "British"},
    {"id": "bm_fable", "name": "Fable", "about": "Storyteller", "gender": "man", "accent": "British"},
    {"id": "bm_lewis", "name": "Lewis", "about": "Deep and serious", "gender": "man", "accent": "British"},
    {"id": "ef_dora", "name": "Dora", "about": "Speaks Spanish", "gender": "woman", "accent": "Spanish"},
    {"id": "ff_siwis", "name": "Siwis", "about": "Speaks French", "gender": "woman", "accent": "French"},
    {"id": "if_sara", "name": "Sara", "about": "Speaks Italian", "gender": "woman", "accent": "Italian"},
    {"id": "pf_dora", "name": "Dora (PT)", "about": "Speaks Portuguese", "gender": "woman", "accent": "Brazilian"},
    {"id": "hf_alpha", "name": "Alpha", "about": "Speaks Hindi", "gender": "woman", "accent": "Indian"},
]
VOICE_IDS = {v["id"] for v in VOICES}
LANG_OF = {"a": "en-us", "b": "en-gb", "e": "es", "f": "fr-fr", "i": "it", "p": "pt-br", "h": "hi"}
PREVIEW_LINES = {
    "a": "Hey, it's {name}. This is how I sound, so, what do you want to talk about?",
    "b": "Hello, I'm {name}. This is how I sound. What shall we talk about?",
    "e": "Hola, soy {name}. Así suena mi voz. ¿De qué quieres hablar?",
    "f": "Salut, c'est {name}. Voilà ma voix. De quoi veux-tu parler ?",
    "i": "Ciao, sono {name}. Questa è la mia voce. Di cosa vuoi parlare?",
    "p": "Oi, eu sou a {name}. Essa é a minha voz. Sobre o que você quer falar?",
    "h": "नमस्ते, मैं {name} हूँ। मेरी आवाज़ ऐसी है। आप किस बारे में बात करना चाहेंगे?",
}


def log(msg):
    print(f"[voice] {msg}", flush=True)


def missing():
    """What's still needed for calls, or None when everything is here."""
    for mod, pkg in (("numpy", "numpy"), ("onnxruntime", "onnxruntime"), ("onnx_asr", "onnx-asr"), ("kokoro_onnx", "kokoro-onnx")):
        if importlib.util.find_spec(mod) is None:
            return f"the Python package {pkg} (run: server/.venv/bin/pip install onnx-asr kokoro-onnx)"
    files = [ASR_DIR / f for f in ASR_FILES] + [VAD_FILE, TTS_MODEL, TTS_VOICES]
    if any(not f.is_file() for f in files):
        return "the voice models (run: cubicai_server.py voice-setup)"
    if not Path(ESPEAK_LIB).exists():
        return "espeak-ng (install it with your package manager)"
    return None


def setup():
    """Downloads the speech models into VOICE_DIR. Run once, outside the sandboxed service."""
    import shutil
    import urllib.request
    from huggingface_hub import hf_hub_download
    VOICE_DIR.mkdir(parents=True, exist_ok=True)
    ASR_DIR.mkdir(exist_ok=True)
    for name in ASR_FILES:
        if not (ASR_DIR / name).is_file():
            print(f"Parakeet: {name}…", flush=True)
            # Copied out of the HF cache: onnxruntime refuses model files that are symlinks into another folder.
            shutil.copyfile(hf_hub_download(ASR_REPO, name), ASR_DIR / name)
    if not VAD_FILE.is_file():
        print("Silero VAD…", flush=True)
        shutil.copyfile(hf_hub_download(VAD_REPO, VAD_NAME), VAD_FILE)
    for name, url in TTS_URLS.items():
        target = VOICE_DIR / name
        if not target.is_file():
            print(f"Kokoro: {name}…", flush=True)
            tmp = target.with_suffix(".part")
            with urllib.request.urlopen(url, timeout=60) as r, open(tmp, "wb") as f:
                shutil.copyfileobj(r, f, 1 << 20)
            tmp.rename(target)
    left = missing()
    print("Voice calls are ready." if left is None else f"Still missing: {left}")


# --- Models (loaded on the first call, dropped after a while without one) ----------------------------

class Engines:
    def __init__(self):
        self.lock = threading.Lock()
        self.tts_lock = threading.Lock()
        self.asr_lock = threading.Lock()
        self.loaded = False
        self.calls = 0
        self.last_used = time.time()
        self.janitor = None

    def load(self):
        with self.lock:
            self.last_used = time.time()
            if self.loaded:
                return
            import numpy as np
            import onnxruntime as ort
            import onnx_asr
            from kokoro_onnx import EspeakConfig, Kokoro
            ort.disable_telemetry_events()  # nothing about the calls leaves this PC
            t = time.time()
            opts = ort.SessionOptions()
            opts.intra_op_num_threads = 1  # VAD frames are tiny; more threads only add overhead
            opts.inter_op_num_threads = 1
            self.vad = ort.InferenceSession(str(VAD_FILE), opts, providers=["CPUExecutionProvider"])
            # Listening and speaking share the CPU: threads that spin-wait after a run would slow the other one.
            quiet = ort.SessionOptions()
            quiet.add_session_config_entry("session.intra_op.allow_spinning", "0")
            self.asr = onnx_asr.load_model("nemo-parakeet-tdt-0.6b-v3", str(ASR_DIR), quantization="int8",
                                           sess_options=quiet, providers=["CPUExecutionProvider"])
            self.tts = Kokoro.from_session(
                ort.InferenceSession(str(TTS_MODEL), quiet, providers=["CPUExecutionProvider"]), str(TTS_VOICES),
                espeak_config=EspeakConfig(lib_path=ESPEAK_LIB, data_path=ESPEAK_DATA))
            # A first run of each, so the first words of a call aren't slow.
            self.asr.recognize(np.zeros(IN_RATE, dtype=np.float32), sample_rate=IN_RATE)
            self.tts.create("Hi.", voice=VOICES[0]["id"], speed=1.0, lang="en-us")
            self.loaded = True
            log(f"speech models loaded in {time.time() - t:.1f}s")
            if self.janitor is None:
                self.janitor = threading.Thread(target=self._unload_when_idle, daemon=True)
                self.janitor.start()

    def _unload_when_idle(self):
        while True:
            time.sleep(60)
            with self.lock:
                if self.loaded and self.calls == 0 and time.time() - self.last_used > IDLE_UNLOAD:
                    self.vad = self.asr = self.tts = None
                    self.loaded = False
                    log("speech models unloaded (no calls)")

    def transcribe(self, audio):
        with self.asr_lock:
            self.last_used = time.time()
            return (self.asr.recognize(audio, sample_rate=IN_RATE) or "").strip()

    def speak(self, text, voice, speed):
        """Float32 samples at 24 kHz."""
        with self.tts_lock:
            self.last_used = time.time()
            samples, _ = self.tts.create(text, voice=voice, speed=speed, lang=LANG_OF.get(voice[0], "en-us"))
            return samples


ENGINES = Engines()
_previews = {}


def voices():
    return VOICES


def preview(voice, speed):
    """A short sample of a voice as 16-bit PCM at 24 kHz (cached)."""
    import numpy as np
    v = next((v for v in VOICES if v["id"] == voice), None)
    if v is None:
        raise ValueError("Unknown voice")
    speed = min(max(speed, 0.7), 1.4)
    key = (voice, round(speed, 2))
    if key not in _previews:
        ENGINES.load()
        line = PREVIEW_LINES.get(voice[0], PREVIEW_LINES["a"]).format(name=v["name"])
        _previews[key] = pcm16(ENGINES.speak(line, voice, speed), np)
        if len(_previews) > 40:
            _previews.pop(next(iter(_previews)))
    return _previews[key]


def pcm16(samples, np):
    return (np.clip(samples, -1.0, 1.0) * 32767).astype("<i2").tobytes()


# --- Speech for the voice: what a TTS can say ---------------------------------------------------------

_EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿️‍\U0001F3FB-\U0001F3FF]")
_LINK = re.compile(r"\[([^\]]+)\]\([^)]+\)")
_URL = re.compile(r"https?://\S+")
_ACTION = re.compile(r"\*[^*\n]*\*")  # *smiles*, **bold** is unwrapped below before this runs
_BULLET = re.compile(r"(?m)^\s*([-*•]|#+)\s+")
_MARKS = re.compile(r"[`#>|~_]")


_CITE = re.compile(r"\s*\[\d+(?:\s*[,\u2013-]\s*\d+)*\]")  # source markers like [1] or [2, 3]


def speakable(text):
    """The words of a reply as they should be said: no stage directions, emoji, Markdown or URLs."""
    text = re.sub(r"\*\*([^*]+)\*\*", r"\1", text)
    text = _ACTION.sub(" ", text)
    text = _LINK.sub(r"\1", text)
    text = _CITE.sub("", text)
    text = _URL.sub(" a link ", text)
    text = _BULLET.sub("", text)
    text = _EMOJI.sub("", text)
    text = _MARKS.sub("", text).replace("*", "")
    text = re.sub(r"\s+", " ", text).strip()
    return text if re.search(r"\w", text) else ""


class Segmenter:
    """Cuts streamed reply text into sentences for the voice, never inside *an action* or a code block."""

    END = re.compile(r"[.!?…]+[\"')\]]*\s+|\n+")
    SOFT = re.compile(r"[,;:—–]\s+")

    def __init__(self):
        self.buf = ""
        self.first = True

    def _open_part(self):
        """Where the text stops being safe to cut: an unclosed ``` or *."""
        limit = len(self.buf)
        if self.buf.count("```") % 2:
            limit = min(limit, self.buf.rfind("```"))
        stars = [m.start() for m in re.finditer(r"(?<!\*)\*(?!\*)", self.buf[:limit])]
        if len(stars) % 2:
            limit = min(limit, stars[-1])
        return limit

    def feed(self, text):
        self.buf += text
        out = []
        while True:
            safe = self._open_part()
            cut = None
            for m in self.END.finditer(self.buf, 0, safe):
                # The first sentence goes out as soon as it's there (the voice starts sooner); later ones
                # wait for a bit of length so the voice keeps its flow.
                if m.end() >= (2 if self.first else 24):
                    cut = m.end()
                    break
            if cut is None and safe > (60 if self.first else 200):
                soft = [m.end() for m in self.SOFT.finditer(self.buf, 0, safe)]
                if soft and soft[-1] > 20:
                    cut = soft[-1]
                else:
                    space = self.buf.rfind(" ", 0, safe)
                    cut = space + 1 if space > 40 else None
            if cut is None:
                return out
            part, self.buf = self.buf[:cut], self.buf[cut:]
            part = re.sub(r"```[\s\S]*?```", " ", part)
            if speakable(part):
                out.append(part)
                self.first = False

    def flush(self):
        part, self.buf = re.sub(r"```[\s\S]*?(```|$)", " ", self.buf), ""
        return [part] if speakable(part) else []


# --- One call ---------------------------------------------------------------------------------------

class Closed(Exception):
    pass


class Call:
    """Runs one call on one WebSocket until the phone hangs up."""

    def __init__(self, ws, on_phase=lambda text: None):
        import numpy as np
        self.np = np
        self.ws = ws
        self.on_phase = on_phase
        self.voice = VOICES[0]["id"]
        self.speed = 1.0
        self.end_ms = 600  # silence that ends what the user is saying
        self.playing = False  # the phone is playing our voice: be surer it's the user before saying they talk
        self.alive = True
        # Listening
        self.vad_state = np.zeros((2, 1, 128), dtype=np.float32)
        self.vad_ctx = np.zeros(64, dtype=np.float32)
        self.pending = b""
        self.preroll = deque(maxlen=PREROLL_MS // FRAME_MS)
        self.speaking = False
        self.voiced = 0
        self.silence = 0
        self.utterance = []
        self.utterance_id = 0
        self.last_partial = 0
        self.asr_jobs = queue.Queue()
        self.transcribing = False
        # Speaking
        self.epoch = 0  # bumped by a cancel; queued speech from before it is dropped
        self.segmenters = {}
        self.tts_jobs = queue.Queue()

    # The phone's messages.

    def run(self):
        ENGINES.calls += 1
        try:
            self.on_phase("Waking up the voice")
            self.ws.send_text(json.dumps({"type": "status", "text": "Waking up the voice on your PC…"}))
            ENGINES.load()
            threading.Thread(target=self._asr_worker, daemon=True).start()
            threading.Thread(target=self._tts_worker, daemon=True).start()
            self.ws.send_text(json.dumps({"type": "ready"}))
            self.on_phase("On a call")
            while True:
                op, data = self.ws.recv()
                if op == 2:
                    self._audio(data)
                elif op == 1:
                    self._command(json.loads(data))
        except (Closed, ConnectionError, OSError):
            pass
        finally:
            self.alive = False
            self.asr_jobs.put(None)
            self.tts_jobs.put(None)
            ENGINES.calls -= 1
            ENGINES.last_used = time.time()

    def _command(self, msg):
        kind = msg.get("type")
        if kind in ("start", "config"):
            if msg.get("voice") in VOICE_IDS:
                self.voice = msg["voice"]
            if msg.get("speed"):
                self.speed = min(max(float(msg["speed"]), 0.7), 1.4)
            if msg.get("patience"):
                self.end_ms = min(max(int(msg["patience"]), 300), 2500)
        elif kind == "playing":
            self.playing = bool(msg.get("on"))
        elif kind == "say":
            turn = int(msg.get("turn", 0))
            seg = self.segmenters.setdefault(turn, Segmenter())
            for part in seg.feed(str(msg.get("text", ""))):
                self.tts_jobs.put((self.epoch, turn, part))
        elif kind == "say_end":
            turn = int(msg.get("turn", 0))
            seg = self.segmenters.pop(turn, None)
            for part in (seg.flush() if seg else []):
                self.tts_jobs.put((self.epoch, turn, part))
            self.tts_jobs.put((self.epoch, turn, None))  # marks the end of the turn
        elif kind == "cancel":
            self.epoch += 1
            self.segmenters.clear()

    # Hearing the user.

    def _audio(self, data):
        np = self.np
        data = self.pending + data
        usable = len(data) - len(data) % (FRAME * 2)
        self.pending = data[usable:]
        samples = np.frombuffer(data[:usable], dtype="<i2").astype(np.float32) / 32768.0
        for i in range(0, len(samples), FRAME):
            self._frame(samples[i:i + FRAME])

    def _frame(self, frame):
        np = self.np
        x = np.concatenate([self.vad_ctx, frame])[None, :]
        out, self.vad_state = ENGINES.vad.run(None, {"input": x, "state": self.vad_state, "sr": np.array(IN_RATE, dtype=np.int64)})
        self.vad_ctx = frame[-64:]
        p = float(out[0][0])
        if DEBUG_LEVELS:
            # Loudness and speech likelihood once a second (never what was said), to tune the microphone path.
            self._dbg = getattr(self, "_dbg", [0, 0.0, 0.0])
            self._dbg[0] += 1
            self._dbg[1] = max(self._dbg[1], float(np.sqrt(np.mean(frame * frame))))
            self._dbg[2] = max(self._dbg[2], p)
            if self._dbg[0] * FRAME_MS >= 1000:
                print(f"[voice] level rms={self._dbg[1]:.4f} speech={self._dbg[2]:.2f} playing={self.playing}", flush=True)
                self._dbg = [0, 0.0, 0.0]
        if not self.speaking:
            self.preroll.append(frame)
            start_p, start_ms = (0.7, 380) if self.playing else (0.5, 220)
            if p > start_p:
                self.voiced += FRAME_MS
            elif p < 0.3:
                self.voiced = max(0, self.voiced - 2 * FRAME_MS)
            if self.voiced >= start_ms:
                self.speaking = True
                self.silence = 0
                self.utterance = list(self.preroll)
                self.utterance_id += 1
                self.last_partial = 0
                self._send({"type": "speech", "on": True, "id": self.utterance_id})
            return
        self.utterance.append(frame)
        self.silence = self.silence + FRAME_MS if p < 0.35 else 0
        length_ms = len(self.utterance) * FRAME_MS
        if self.silence >= self.end_ms or length_ms >= MAX_UTTERANCE_S * 1000:
            audio = np.concatenate(self.utterance[: max(1, len(self.utterance) - (self.silence // FRAME_MS) + 6)])
            self.asr_jobs.put(("final", self.utterance_id, audio))
            self.speaking = False
            self.voiced = 0
            self.preroll.clear()
            self._send({"type": "speech", "on": False, "id": self.utterance_id})
        elif self.silence == 0 and length_ms - self.last_partial >= PARTIAL_EVERY_MS and self.asr_jobs.empty() \
                and not self.transcribing and length_ms < 12000:
            # Captions while the user talks, only when the transcriber is free and they're not pausing (it's
            # likely they're done, and the final transcript must not wait behind a partial one).
            self.last_partial = length_ms
            self.asr_jobs.put(("partial", self.utterance_id, np.concatenate(self.utterance)))

    def _asr_worker(self):
        while True:
            job = self.asr_jobs.get()
            if job is None or not self.alive:
                return
            kind, uid, audio = job
            if kind == "partial" and (uid != self.utterance_id or not self.speaking):
                continue  # already finished; the final transcript is on its way
            self.transcribing = True
            try:
                text = ENGINES.transcribe(audio)
            except Exception as e:
                log(f"transcription failed: {e.__class__.__name__}")
                text = ""
            finally:
                self.transcribing = False
            self._send({"type": kind, "id": uid, "text": text})

    # Speaking the reply.

    def _tts_worker(self):
        np = self.np
        seq = 0
        while True:
            job = self.tts_jobs.get()
            if job is None or not self.alive:
                return
            epoch, turn, text = job
            if epoch != self.epoch:
                continue
            if text is None:
                self._send({"type": "turn_end", "turn": turn})
                continue
            words = speakable(text)
            try:
                samples = ENGINES.speak(words, self.voice, self.speed)
            except Exception as e:
                log(f"speech failed: {e.__class__.__name__}")
                continue
            if epoch != self.epoch:
                continue
            seq += 1
            pcm = pcm16(samples, np)
            self._send({"type": "seg", "turn": turn, "seq": seq, "text": words, "samples": len(samples)})
            for i in range(0, len(pcm), SEND_CHUNK * 2):
                if epoch != self.epoch or not self.alive:
                    break
                self.ws.send_binary(pcm[i:i + SEND_CHUNK * 2])

    def _send(self, obj):
        try:
            self.ws.send_text(json.dumps(obj))
        except OSError:
            self.alive = False


# --- A small WebSocket (RFC 6455), server side --------------------------------------------------------

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_MESSAGE = 1 << 20


def unmask(data, mask):
    if not data:
        return b""
    import numpy as np
    return (np.frombuffer(data, dtype=np.uint8) ^ np.resize(np.frombuffer(mask, dtype=np.uint8), len(data))).tobytes()


class WebSocket:
    def __init__(self, rfile, wfile):
        self.r, self.w = rfile, wfile
        self.lock = threading.Lock()
        self.closed = False

    def _read(self, n):
        data = self.r.read(n)
        if data is None or len(data) < n:
            raise Closed()
        return data

    def recv(self):
        """The next text (1) or binary (2) message; answers pings, raises Closed when the phone hangs up."""
        message, opcode = b"", None
        while True:
            b1, b2 = self._read(2)
            fin, op = b1 & 0x80, b1 & 0x0F
            length = b2 & 0x7F
            if length == 126:
                length = struct.unpack(">H", self._read(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self._read(8))[0]
            if length > MAX_MESSAGE or not b2 & 0x80:  # clients must mask
                raise Closed()
            mask = self._read(4)
            payload = unmask(self._read(length), mask)
            if op == 8:
                self._send(8, payload[:2])
                raise Closed()
            if op == 9:
                self._send(10, payload)
                continue
            if op == 10:
                continue
            if op in (1, 2):
                opcode, message = op, payload
            elif op == 0 and opcode is not None:
                message += payload
                if len(message) > MAX_MESSAGE:
                    raise Closed()
            if fin and opcode is not None:
                return opcode, (message.decode("utf-8") if opcode == 1 else message)

    def _send(self, op, data):
        header = bytearray([0x80 | op])
        n = len(data)
        if n < 126:
            header.append(n)
        elif n < 1 << 16:
            header += bytes([126]) + struct.pack(">H", n)
        else:
            header += bytes([127]) + struct.pack(">Q", n)
        with self.lock:
            if self.closed and op != 8:
                raise ConnectionError("closed")
            self.w.write(bytes(header) + data)
            if op == 8:
                self.closed = True

    def send_text(self, text):
        self._send(1, text.encode("utf-8"))

    def send_binary(self, data):
        self._send(2, data)
