#!/usr/bin/env python3
"""CubicAI Server: desktop control panel for the CubicAI gateway (status, on/off, pairing, remote access, activity)."""

import glob
import hashlib
import json
import shutil
import socket
import ssl
import subprocess
import sys
import threading
import time
import http.client
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.extend(glob.glob(str(HERE / ".venv/lib/python3*/site-packages")))  # for qrcode

import gi  # noqa: E402

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, GLib, Gtk  # noqa: E402

import cubicai_server  # noqa: E402

SERVICE = "cubicai-server"
UNIT_SRC = HERE / "cubicai-server.service"
UNIT_DST = Path("~/.config/systemd/user/cubicai-server.service").expanduser()
SEARX_CONTAINER = "cubicai-searxng"


def run(*cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=20).stdout.strip()
    except Exception:
        return ""


def ago(t):
    s = int(time.time() - t)
    if s < 60:
        return "just now"
    if s < 3600:
        return f"{s // 60} min ago"
    if s < 86400:
        return f"{s // 3600} h ago"
    return f"{s // 86400} d ago"


def duration(s):
    s = int(s)
    if s < 60:
        return f"{s}s"
    if s < 3600:
        return f"{s // 60}m {s % 60}s"
    return f"{s // 3600}h {s % 3600 // 60}m"


_names = {}


def device_name(ip):
    """Reverse-DNS name from the router (e.g. 'Pixel-8.lan'), cached; falls back to the IP."""
    if ip in ("127.0.0.1", "::1"):
        return "This PC (or emulator)"
    if ip not in _names:
        try:
            socket.setdefaulttimeout(1)
            _names[ip] = socket.gethostbyaddr(ip)[0].split(".")[0]
        except Exception:
            _names[ip] = None
        finally:
            socket.setdefaulttimeout(None)
    return f"{_names[ip]} ({ip})" if _names[ip] else ip


def fetch_status():
    """Everything the window shows, gathered off the UI thread."""
    data = {
        "unit_installed": UNIT_DST.exists(),
        "active": run("systemctl", "--user", "is-active", SERVICE) == "active",
        "enabled": run("systemctl", "--user", "is-enabled", SERVICE) == "enabled",
        "searx": run("docker", "inspect", "-f", "{{.State.Running}}", SEARX_CONTAINER) == "true",
        "ollama": None,
        "loaded": [],
        "gateway": None,
        "tailscale": cubicai_server.tailscale_ip(),
        "remote": cubicai_server.remote_enabled(),
    }
    try:
        with urllib.request.urlopen(cubicai_server.OLLAMA_URL + "/api/version", timeout=3) as r:
            data["ollama"] = json.load(r).get("version")
        with urllib.request.urlopen(cubicai_server.OLLAMA_URL + "/api/ps", timeout=3) as r:
            data["loaded"] = json.load(r).get("models", [])
    except Exception:
        pass
    if data["active"]:
        data["gateway"] = gateway_status()
    for item in (data["gateway"] or {}).get("devices", []):
        item["name"] = device_name(item["ip"])
    return data


def gateway_status():
    """Reads /v1/cubicai/status from the local gateway, checking it's our certificate."""
    try:
        _, _, token, fp = cubicai_server.identity()
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        conn = http.client.HTTPSConnection("127.0.0.1", cubicai_server.DEFAULT_PORT, context=ctx, timeout=3)
        conn.connect()
        if hashlib.sha256(conn.sock.getpeercert(binary_form=True)).hexdigest() != fp:
            return None
        conn.request("GET", "/v1/cubicai/status", headers={"Authorization": f"Bearer {token}"})
        resp = conn.getresponse()
        return json.loads(resp.read()) if resp.status == 200 else None
    except Exception:
        return None


class Window(Adw.ApplicationWindow):
    def __init__(self, app, page="status"):
        super().__init__(application=app, title="CubicAI Server", default_width=560, default_height=820)
        self.updating = False
        self.fetching = False
        self.ip = cubicai_server.lan_ip()
        self.url = f"https://{self.ip}:{cubicai_server.DEFAULT_PORT}"

        self.toasts = Adw.ToastOverlay()
        stack = Adw.ViewStack()
        stack.add_titled_with_icon(self.build_status(), "status", "Status", "network-server-symbolic")
        stack.add_titled_with_icon(self.build_pair(), "pair", "Pair", "phone-symbolic")
        stack.add_titled_with_icon(self.build_activity(), "activity", "Activity", "view-list-symbolic")

        stack.set_visible_child_name(page)
        header = Adw.HeaderBar()
        header.set_title_widget(Adw.ViewSwitcher(stack=stack, policy=Adw.ViewSwitcherPolicy.WIDE))
        view = Adw.ToolbarView()
        view.add_top_bar(header)
        view.add_top_bar(self.banner)
        view.set_content(stack)
        self.toasts.set_child(view)
        self.set_content(self.toasts)

        self.refresh()
        GLib.timeout_add_seconds(2, self.refresh)

    # --- Status page ----------------------------------------------------------------------------

    def build_status(self):
        page = Adw.PreferencesPage()

        self.banner = Adw.Banner(title="The CubicAI service isn't installed yet", button_label="Install")
        self.banner.connect("button-clicked", lambda *_: self.background(self.install_service, "Service installed and started"))

        gw = Adw.PreferencesGroup(title="Gateway", description="Lets your phone use Ollama over home Wi-Fi (and Tailscale if you allow it), encrypted and token-protected.")
        self.server_row = Adw.ActionRow(title="Server")
        self.server_switch = Gtk.Switch(valign=Gtk.Align.CENTER, sensitive=False)  # enabled once real state is known
        self.server_switch.connect("notify::active", self.on_server_toggled)
        self.server_row.add_suffix(self.server_switch)
        self.server_row.set_activatable_widget(self.server_switch)
        gw.add(self.server_row)

        self.autostart_row = Adw.SwitchRow(title="Start when I log in", sensitive=False)
        self.autostart_row.connect("notify::active", self.on_autostart_toggled)
        gw.add(self.autostart_row)

        self.stats_row = Adw.ActionRow(title="Requests", subtitle="—")
        gw.add(self.stats_row)
        page.add(gw)

        ol = Adw.PreferencesGroup(title="Ollama")
        self.ollama_row = Adw.ActionRow(title="Ollama", subtitle="—")
        ol.add(self.ollama_row)
        self.loaded_list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE, css_classes=["boxed-list"])
        loaded_label = Gtk.Label(label="Loaded in memory", xalign=0, css_classes=["heading"], margin_top=12, margin_bottom=6)
        ol.add(loaded_label)
        ol.add(self.loaded_list)
        page.add(ol)

        sx = Adw.PreferencesGroup(title="Private web search", description="SearXNG in Docker, only reachable from this PC.")
        self.searx_row = Adw.ActionRow(title="Search engine")
        self.searx_switch = Gtk.Switch(valign=Gtk.Align.CENTER, sensitive=False)
        self.searx_switch.connect("notify::active", self.on_searx_toggled)
        self.searx_row.add_suffix(self.searx_switch)
        self.searx_row.set_activatable_widget(self.searx_switch)
        sx.add(self.searx_row)
        page.add(sx)

        cx = Adw.PreferencesGroup(
            title="Memory per chat",
            description="How much of a conversation a model can see at once. Longer remembers more but uses more GPU memory "
                        "and can slow replies. Models whose Modelfile sets num_ctx (like qwen-64k) keep their own.",
        )
        self.ctx_choices = [8192, 16384, 32768, 65536]
        self.ctx_row = Adw.ComboRow(title="Context length", model=Gtk.StringList.new(["8K tokens", "16K tokens (default)", "32K tokens", "64K tokens"]))
        current = int(cubicai_server.load_settings().get("num_ctx", cubicai_server.DEFAULT_CTX))
        self.ctx_row.set_selected(self.ctx_choices.index(current) if current in self.ctx_choices else 1)
        self.ctx_row.connect("notify::selected", self.on_ctx_changed)
        cx.add(self.ctx_row)
        page.add(cx)
        return page

    def on_ctx_changed(self, row, _pspec):
        n = self.ctx_choices[row.get_selected()]
        cubicai_server.save_settings(num_ctx=n)
        self.toast(f"Context set to {n // 1024}K tokens; applies from the next message")

    # --- Pair page ------------------------------------------------------------------------------

    def build_pair(self):
        page = Adw.PreferencesPage()
        group = Adw.PreferencesGroup()

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14, halign=Gtk.Align.CENTER, margin_top=6)
        frame = Gtk.Frame(css_classes=["card"])
        self.qr = Gtk.DrawingArea(content_width=300, content_height=300)
        self.qr.set_draw_func(self.draw_qr)
        frame.set_child(self.qr)
        box.append(frame)
        box.append(Gtk.Label(label="On your phone: CubicAI → Settings → Local PC → Scan pairing QR code",
                             wrap=True, justify=Gtk.Justification.CENTER, css_classes=["dim-label"]))
        group.add(box)
        page.add(group)

        details = Adw.PreferencesGroup(title="Details")
        self.address_row = Adw.ActionRow(title="Phone connects to", subtitle=self.url, subtitle_selectable=True)
        details.add(self.address_row)
        copy_row = Adw.ActionRow(title="Pairing link", subtitle="Contains your secret token. Don't share it.")
        copy_btn = Gtk.Button(label="Copy", valign=Gtk.Align.CENTER)
        copy_btn.connect("clicked", self.copy_link)
        copy_row.add_suffix(copy_btn)
        details.add(copy_row)
        page.add(details)

        remote = Adw.PreferencesGroup(
            title="Away from home",
            description="Off: only devices on your home network can connect. On: your paired phone can also connect "
                        "through Tailscale (encrypted, private to your Tailscale account). The phone picks up the Tailscale "
                        "address the next time it connects at home; no re-pairing needed.",
        )
        self.remote_row = Adw.SwitchRow(title="Remote access through Tailscale", sensitive=False)
        self.remote_row.connect("notify::active", self.on_remote_toggled)
        remote.add(self.remote_row)
        page.add(remote)

        sec = Adw.PreferencesGroup(title="Security")
        revoke = Adw.ActionRow(title="Unpair all phones", subtitle="Creates a new token; every phone must scan the new QR code.")
        revoke_btn = Gtk.Button(label="Unpair", valign=Gtk.Align.CENTER, css_classes=["destructive-action"])
        revoke_btn.connect("clicked", self.confirm_revoke)
        revoke.add_suffix(revoke_btn)
        sec.add(revoke)
        page.add(sec)

        subnet = ".".join(self.ip.split(".")[:3]) + ".0/24"
        self.fw_cmd = f"sudo ufw allow from {subnet} to any port {cubicai_server.DEFAULT_PORT} proto tcp"
        help_group = Adw.PreferencesGroup(
            title="Phone can't connect?",
            description="The phone must be on the same Wi-Fi. If the PC firewall blocks it, run this once in a terminal:",
        )
        fw = Adw.ActionRow(title=self.fw_cmd, title_selectable=True, css_classes=["monospace"])
        fw_btn = Gtk.Button(icon_name="edit-copy-symbolic", valign=Gtk.Align.CENTER, tooltip_text="Copy")
        fw_btn.connect("clicked", lambda *_: self.copy(self.fw_cmd, "Command copied"))
        fw.add_suffix(fw_btn)
        help_group.add(fw)
        page.add(help_group)
        return page

    def pairing(self):
        _, _, token, fp = cubicai_server.identity()
        return cubicai_server.pairing_link(self.url, token, fp, cubicai_server.remote_url())

    def draw_qr(self, area, cr, width, height):
        try:
            import qrcode
            qr = qrcode.QRCode(border=2, error_correction=qrcode.constants.ERROR_CORRECT_M)
            qr.add_data(self.pairing())
            qr.make(fit=True)
            matrix = qr.get_matrix()
        except Exception:
            return
        n = len(matrix)
        size = min(width, height)
        cell = size / n
        ox, oy = (width - size) / 2, (height - size) / 2
        cr.set_source_rgb(1, 1, 1)
        cr.rectangle(0, 0, width, height)
        cr.fill()
        cr.set_source_rgb(0, 0, 0)
        for y, row in enumerate(matrix):
            for x, dark in enumerate(row):
                if dark:
                    cr.rectangle(ox + x * cell, oy + y * cell, cell + 0.5, cell + 0.5)
        cr.fill()

    # --- Activity page --------------------------------------------------------------------------

    def build_activity(self):
        page = Adw.PreferencesPage()
        page.set_description("Shows who used the gateway and how. Message content is never recorded.")

        def section(title):
            group = Adw.PreferencesGroup(title=title)
            lst = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE, css_classes=["boxed-list"])
            group.add(lst)
            page.add(group)
            return lst

        self.now_list = section("Right now")
        self.recent_list = section("Recent")
        self.devices_list = section("Devices")
        return page

    # --- Refresh --------------------------------------------------------------------------------

    def refresh(self):
        if not self.fetching:
            self.fetching = True

            def work():
                data = fetch_status()
                GLib.idle_add(self.apply, data)

            threading.Thread(target=work, daemon=True).start()
        return True

    def apply(self, d):
        self.fetching = False
        self.updating = True
        try:
            self.banner.set_revealed(not d["unit_installed"])
            self.server_switch.set_active(d["active"])
            self.server_switch.set_sensitive(d["unit_installed"])
            self.autostart_row.set_active(d["enabled"])
            self.autostart_row.set_sensitive(d["unit_installed"])
            self.searx_switch.set_active(d["searx"])
            self.searx_switch.set_sensitive(True)
            self.remote_row.set_active(d["remote"])
            self.remote_row.set_sensitive(bool(d["tailscale"]) or d["remote"])
        finally:
            self.updating = False

        gw = d["gateway"]
        if d["active"]:
            self.server_row.set_subtitle(f"Running · {self.url}")
        else:
            self.server_row.set_subtitle("Stopped. Your phone can't reach local models")
        self.stats_row.set_subtitle(
            f"{gw['served']} served · {gw['blocked']} blocked · up {duration(gw['uptime'])}" if gw else "—"
        )
        self.ollama_row.set_subtitle(f"Running · version {d['ollama']}" if d["ollama"] else "Not reachable. Is the ollama service running?")
        self.searx_row.set_subtitle("Running" if d["searx"] else "Stopped. Web search from the app won't work")
        if not d["tailscale"]:
            self.remote_row.set_subtitle("Tailscale isn't connected on this PC")
        elif d["remote"]:
            self.remote_row.set_subtitle(f"On: phone can reach https://{d['tailscale']}:{cubicai_server.DEFAULT_PORT}")
        else:
            self.remote_row.set_subtitle(f"Off: Tailscale connections ({d['tailscale']}) are refused")
        self.address_row.set_subtitle(self.url + (f"\n{cubicai_server.remote_url()} (Tailscale)" if d["remote"] and d["tailscale"] else ""))

        self.loaded_list.remove_all()
        if not d["loaded"]:
            self.loaded_list.append(Adw.ActionRow(title="Nothing loaded", subtitle="Models load when the phone asks for them"))
        for m in d["loaded"]:
            vram = m.get("size_vram", 0) / 1e9
            total = m.get("size", 0) / 1e9
            where = "fully on GPU" if total and vram >= total * 0.99 else f"{vram:.1f} GB on GPU, rest on CPU"
            row = Adw.ActionRow(title=m["name"], subtitle=f"{total:.1f} GB · {where}")
            btn = Gtk.Button(label="Unload", valign=Gtk.Align.CENTER)
            btn.connect("clicked", lambda _b, name=m["name"]: self.background(lambda: self.unload(name), f"Unloaded {name}"))
            row.add_suffix(btn)
            self.loaded_list.append(row)

        self.fill_activity(gw)
        return False

    def fill_activity(self, gw):
        self.now_list.remove_all()
        self.recent_list.remove_all()
        self.devices_list.remove_all()
        if not gw:
            for lst in (self.now_list, self.recent_list, self.devices_list):
                lst.append(Adw.ActionRow(title="Server is off"))
            return

        if not gw["active"]:
            self.now_list.append(Adw.ActionRow(title="Idle"))
        for a in gw["active"]:
            row = Adw.ActionRow(title=a["model"], subtitle=f"{device_name(a['ip'])} · {a['phase']} · {duration(time.time() - a['started'])}")
            row.add_prefix(Adw.Spinner())
            self.now_list.append(row)

        if not gw["recent"]:
            self.recent_list.append(Adw.ActionRow(title="No requests yet"))
        icons = {"ok": "emblem-ok-symbolic", "stopped": "media-playback-stop-symbolic",
                 "error": "dialog-error-symbolic", "blocked": "dialog-warning-symbolic"}
        for r in gw["recent"][:40]:
            when = time.strftime("%H:%M", time.localtime(r["started"]))
            if r["outcome"] == "blocked":
                row = Adw.ActionRow(title="Blocked request (wrong or missing token)", subtitle=f"{when} · {device_name(r['ip'])}")
                row.add_css_class("error")
            else:
                bits = [when, device_name(r["ip"]), duration(r["ended"] - r["started"])]
                if r.get("tokens"):
                    bits.append(f"{r['tokens']} tokens · {r['tps']} tok/s")
                if r.get("search"):
                    bits.append("web search")
                if r["outcome"] != "ok":
                    bits.append(r["outcome"])
                row = Adw.ActionRow(title=r["model"], subtitle=" · ".join(bits))
            row.add_prefix(Gtk.Image(icon_name=icons.get(r["outcome"], "dialog-information-symbolic")))
            self.recent_list.append(row)

        if not gw["devices"]:
            self.devices_list.append(Adw.ActionRow(title="No devices yet"))
        for dev in gw["devices"]:
            self.devices_list.append(Adw.ActionRow(title=dev.get("name") or dev["ip"], subtitle=f"Last request {ago(dev['last_seen'])}"))

    # --- Actions --------------------------------------------------------------------------------

    def background(self, fn, done_message):
        def work():
            try:
                fn()
                msg = done_message
            except Exception as e:
                msg = f"Failed: {e}"
            GLib.idle_add(self.toast, msg)
            GLib.idle_add(self.refresh)

        threading.Thread(target=work, daemon=True).start()

    def toast(self, msg):
        self.toasts.add_toast(Adw.Toast(title=msg))
        return False

    def on_server_toggled(self, switch, _pspec):
        if self.updating:
            return
        on = switch.get_active()
        self.background(lambda: run("systemctl", "--user", "start" if on else "stop", SERVICE),
                        "Server started" if on else "Server stopped")

    def on_autostart_toggled(self, row, _pspec):
        if self.updating:
            return
        on = row.get_active()
        self.background(lambda: run("systemctl", "--user", "enable" if on else "disable", SERVICE),
                        "Will start when you log in" if on else "Won't start automatically")

    def on_remote_toggled(self, row, _pspec):
        if self.updating:
            return
        on = row.get_active()
        cubicai_server.save_settings(remote=on)  # the running server picks this up on the next connection
        self.qr.queue_draw()
        self.toast("Remote access on. The phone learns the address next time it connects at home" if on
                   else "Remote access off. Tailscale connections are refused, even ones already open")

    def on_searx_toggled(self, switch, _pspec):
        if self.updating:
            return
        on = switch.get_active()
        self.background(lambda: run("docker", "start" if on else "stop", SEARX_CONTAINER),
                        "Web search started" if on else "Web search stopped")

    def install_service(self):
        UNIT_DST.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(UNIT_SRC, UNIT_DST)
        run("systemctl", "--user", "daemon-reload")
        run("systemctl", "--user", "enable", "--now", SERVICE)

    def unload(self, name):
        req = urllib.request.Request(cubicai_server.OLLAMA_URL + "/api/generate",
                                     data=json.dumps({"model": name, "keep_alive": 0}).encode(),
                                     headers={"Content-Type": "application/json"})
        urllib.request.urlopen(req, timeout=30).read()

    def copy(self, text, message):
        Gdk.Display.get_default().get_clipboard().set(text)
        self.toast(message)

    def copy_link(self, *_):
        self.copy(self.pairing(), "Pairing link copied. Keep it private")

    def confirm_revoke(self, *_):
        dialog = Adw.AlertDialog(
            heading="Unpair all phones?",
            body="Every paired phone loses access immediately. Scan the new QR code to pair again.",
        )
        dialog.add_response("cancel", "Cancel")
        dialog.add_response("revoke", "Unpair all")
        dialog.set_response_appearance("revoke", Adw.ResponseAppearance.DESTRUCTIVE)
        dialog.connect("response", self.on_revoke_response)
        dialog.present(self)

    def on_revoke_response(self, _dialog, response):
        if response != "revoke":
            return

        def revoke():
            cubicai_server.rotate_token()
            run("systemctl", "--user", "try-restart", SERVICE)
            GLib.idle_add(self.qr.queue_draw)

        self.background(revoke, "All phones unpaired. Scan the new QR code")


def keep_opaque():
    """On Hyprland, ask for this window to be fully opaque so the QR code scans reliably.
    It's a runtime rule (no config files touched) that lasts until Hyprland reloads its config."""
    import os
    if os.environ.get("HYPRLAND_INSTANCE_SIGNATURE") and shutil.which("hyprctl"):
        rule = ('hl.window_rule({ name = "cubicai-server-opaque", match = { class = "^dev.martin.cubicai.Server$" }, '
                'opaque = true, no_blur = true })')
        run("hyprctl", "eval", rule)


class App(Adw.Application):
    def __init__(self, page):
        super().__init__(application_id="dev.martin.cubicai.Server")
        self.page = page

    def do_activate(self):
        keep_opaque()
        win = self.props.active_window or Window(self, self.page)
        win.present()


if __name__ == "__main__":
    # `cubicai_control.py pair` or `activity` opens straight on that tab.
    page = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] in ("status", "pair", "activity") else "status"
    App(page).run(sys.argv[:1])
