"""Install, launch and talk to the test games (copies of the "Manhatten Testing" setup under tmp/ingame/wN).

Only games whose game dir is one of those copies are ever started or stopped here. Other Minecraft instances (in particular "26.1.1 AFK") are
never touched: processes are found by their --gameDir, and only ones whose game dir is the testing profile count.

Standard library only (Python 3.9+).
"""
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import uuid

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
APPDATA = os.environ.get("APPDATA", os.path.expanduser(r"~\AppData\Roaming"))
MODRINTH = os.path.join(APPDATA, "ModrinthApp")
META = os.path.join(MODRINTH, "meta")
# The user's own "Manhatten Testing" profile (spelled this way) is never used by the harness any more: every game
# runs in its own copy under tmp/ingame/wN (own mods, saves, config, logs), so the profile stays untouched while the
# user plays in it. A run without MANHATTAN_PROFILE uses tmp/ingame/w1.
USER_PROFILE = os.path.join(MODRINTH, "profiles", "Manhatten Testing")
PROFILE = os.environ.get("MANHATTAN_PROFILE") or os.path.join(REPO, "tmp", "ingame", "w1")
if os.path.normcase(os.path.abspath(PROFILE)) == os.path.normcase(os.path.abspath(USER_PROFILE)):
    raise RuntimeError("the harness never runs in the user's 'Manhatten Testing' profile; use a copy (tmp/ingame/wN)")
WORKER = True   # every game is a copy: the lighter settings apply to all of them
AFK_MODS = os.path.join(MODRINTH, "profiles", "26.1.1 AFK", "mods")
VERSION = "26.1.1-0.20.1"   # Modrinth's id for Minecraft 26.1.1 + Fabric Loader 0.20.1
FABRIC_API = "fabric-api-0.145.4+26.1.1.jar"
# bridge ports: 25700 + worker number (25701 for a single game). 25599..25603 belong to the other harness users.
PORT_BASE = int(os.environ.get("MANHATTAN_PORT_BASE", "25700"))
PORT = int(os.environ.get("MANHATTAN_BRIDGE_PORT", str(PORT_BASE + 1)))
USERNAME = "ManhattanTest"
WORLD = "manhattan-test"

# Never installed into the testing profile (the old separate addons are part of baritonequeue now).
FORBIDDEN = ("survivalkit", "buildtools", "loadchest", "litematica-printer")

# Litematica + malilib for the schematic tests: copied (read only) from the profile the user builds in, if it is there.
LITEMATICA_SRC = os.path.join(MODRINTH, "profiles", "Bestes_projekt_1.26.0", "mods")
LITEMATICA_JARS = ("litematica-fabric-", "malilib-fabric-")

# Settings that make an unattended client behave (no pause when unfocused, no sound, no first-start screens).
OPTIONS = {
    "pauseOnLostFocus": "false",
    "onboardAccessibility": "false",
    "soundCategory_master": "0.0",
    "renderDistance": "6",
    "simulationDistance": "5",
    "maxFps": "60",
    "enableVsync": "false",
    "narrator": "0",
    "tutorialStep": "none",
    "joinedFirstServer": "true",
    "skipMultiplayerWarning": "true",
    "fullscreen": "false",
}


def log(*a):
    print("[ingame]", *a, flush=True)


def mod_version():
    with open(os.path.join(REPO, "gradle.properties"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("mod_version="):
                return line.split("=", 1)[1].strip()
    raise RuntimeError("mod_version missing in gradle.properties")


# ------------------------------------------------------------------ processes

def _java_processes():
    """(pid, command line) of every java/javaw process (read only)."""
    ps = ("Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'java*.exe' } | "
          "ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }")
    out = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True,
                         encoding="utf-8", errors="replace").stdout
    res = []
    for line in out.splitlines():
        if "\t" in line:
            pid, cl = line.split("\t", 1)
            res.append((int(pid), cl))
    return res


def testing_pids():
    """PIDs of Minecraft instances whose game dir is this harness game's dir (and nothing else)."""
    return testing_pids_in(PROFILE)


def testing_pids_in(profile):
    """PIDs of Minecraft instances whose game dir is exactly `profile`."""
    want = os.path.normcase(os.path.normpath(profile)).replace("/", "\\")
    # the path may be quoted or not (it has a space); it must end there: '"', ' --next-arg' or the end
    pat = re.compile(r'--gamedir\s+"?' + re.escape(want) + r'\\?(?:"|\s+--|\s*$)')
    return [pid for pid, cl in _java_processes() if pat.search(os.path.normcase(cl))]


# ------------------------------------------------------------------ install

def install():
    """Puts exactly the test stack into the profile's mods folder."""
    ver = mod_version()
    mods = os.path.join(PROFILE, "mods")
    os.makedirs(mods, exist_ok=True)
    wanted = {
        FABRIC_API: _fabric_api_jar(),
        "baritone-api-fabric-1.18.0.jar": os.path.join(AFK_MODS, "baritone-api-fabric-1.18.0.jar"),
        "baritoneui-1.10.1.jar": os.path.join(AFK_MODS, "baritoneui-1.10.1.jar"),
        "baritonequeue-%s.jar" % ver: os.path.join(REPO, "build", "libs", "baritonequeue-%s.jar" % ver),
        "manhattan-testbridge-%s.jar" % ver: os.path.join(REPO, "build", "libs", "manhattan-testbridge-%s.jar" % ver),
    }
    if os.path.isdir(LITEMATICA_SRC):
        for f in sorted(os.listdir(LITEMATICA_SRC)):
            if f.lower().startswith(LITEMATICA_JARS) and f.endswith(".jar"):
                wanted[f] = os.path.join(LITEMATICA_SRC, f)
    for name, src in wanted.items():
        if not src or not os.path.isfile(src):
            raise RuntimeError("missing %s (looked for %s). Build first: build.bat" % (name, src))
    for f in os.listdir(mods):
        low = f.lower()
        stale = (low.startswith(("baritonequeue-", "manhattan-testbridge-", "fabric-api-", "litematica-fabric-", "malilib-fabric-")) and f not in wanted) \
            or low.startswith(FORBIDDEN)
        if stale and low.endswith(".jar"):
            os.remove(os.path.join(mods, f))
            log("removed from testing mods:", f)
    for name, src in wanted.items():
        dst = os.path.join(mods, name)
        if not os.path.exists(dst) or _sha1(dst) != _sha1(src):
            shutil.copyfile(src, dst)
            log("installed:", name)
    _write_options()
    _reset_mod_config()


def _reset_mod_config():
    """The mod's own settings (want-list, bed/supply places, safe areas, chest index ...) start from the defaults for every
    game: they live in config/ and would otherwise carry from one test run into the next (a `want("_shovel", 2)` of an
    earlier canal test made a later dump test keep two shovels). Litematica's and the other mods' files stay."""
    cfg = os.path.join(PROFILE, "config")
    for f in ("baritonequeue.json", "buildtools.json", "loadchest.json", "survivalkit.json"):
        try:
            os.remove(os.path.join(cfg, f))
        except FileNotFoundError:
            pass
    shutil.rmtree(os.path.join(cfg, "manhattan-chests"), ignore_errors=True)


def _fabric_api_jar():
    """Fabric API 0.145.4+26.1.1: the copy Gradle downloaded from maven.fabricmc.net for building this mod."""
    local = os.path.join(PROFILE, "mods", FABRIC_API)
    base = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "modules-2", "files-2.1",
                        "net.fabricmc.fabric-api", "fabric-api", "0.145.4+26.1.1")
    if os.path.isdir(base):
        for root, _, files in os.walk(base):
            if FABRIC_API in files:
                return os.path.join(root, FABRIC_API)
    return local if os.path.isfile(local) else None


def _sha1(p):
    h = hashlib.sha1()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _write_options():
    opts = dict(OPTIONS)
    if WORKER:   # several clients at once: keep the GPU and CPU load low
        opts.update({"renderDistance": "4", "simulationDistance": "5", "maxFps": "20", "graphicsMode": "0"})
    path = os.path.join(PROFILE, "options.txt")
    lines = []
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            lines = f.read().splitlines()
    seen = set()
    for i, line in enumerate(lines):
        k = line.split(":", 1)[0]
        if k in opts:
            lines[i] = "%s:%s" % (k, opts[k])
            seen.add(k)
    lines += ["%s:%s" % (k, v) for k, v in opts.items() if k not in seen]
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


# ------------------------------------------------------------------ launch

def _rules_allow(rules):
    if not rules:
        return True
    allowed = False
    for r in rules:
        os_ = r.get("os")
        if os_ and os_.get("name") not in (None, "windows"):
            continue
        if r.get("features"):
            continue
        allowed = r.get("action") == "allow"
    return allowed


def _lib_path(lib):
    art = lib.get("downloads", {}).get("artifact")
    if art and art.get("path"):
        return art["path"]
    parts = lib["name"].split(":")
    group, name, ver = parts[0], parts[1], parts[2]
    cls = "-" + parts[3] if len(parts) > 3 else ""
    return "%s/%s/%s/%s-%s%s.jar" % (group.replace(".", "/"), name, ver, name, ver, cls)


def _java():
    for cand in (os.path.join(META, "java_versions"),):
        if os.path.isdir(cand):
            for d in sorted(os.listdir(cand), reverse=True):
                if d.startswith("zulu25"):
                    p = os.path.join(cand, d, "bin", "javaw.exe")
                    if os.path.isfile(p):
                        return p
    tools = os.path.join(REPO, "tools")
    for d in sorted(os.listdir(tools)) if os.path.isdir(tools) else []:
        p = os.path.join(tools, d, "bin", "javaw.exe")
        if d.startswith("jdk-25") and os.path.isfile(p):
            return p
    raise RuntimeError("no Java 25 found (Modrinth's meta/java_versions/zulu25* or tools/jdk-25*)")


def _offline_uuid(name):
    md5 = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    md5[6] = (md5[6] & 0x0F) | 0x30
    md5[8] = (md5[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(md5)))


def launch():
    """Starts the testing profile in offline mode (no Microsoft login; single-player only). Returns the Popen."""
    with open(os.path.join(META, "versions", VERSION, VERSION + ".json"), encoding="utf-8") as f:
        v = json.load(f)
    cp = []
    for lib in v["libraries"]:
        if not lib.get("include_in_classpath", True) or not _rules_allow(lib.get("rules")):
            continue
        p = os.path.join(META, "libraries", *_lib_path(lib).split("/"))
        if os.path.isfile(p):
            cp.append(p)
        else:
            log("library missing, skipped:", lib["name"])
    cp.append(os.path.join(META, "versions", VERSION, VERSION + ".jar"))

    # own copy of the natives, so nothing is shared with a running AFK instance
    natives = os.path.join(PROFILE, ".natives")
    src_nat = os.path.join(META, "natives", VERSION)
    if not os.path.isdir(natives):
        shutil.copytree(src_nat, natives)

    log_cfg = os.path.join(META, "log_configs", v["logging"]["client"]["file"]["id"])
    args = [_java(), "-Xms512M", "-Xmx" + os.environ.get("MANHATTAN_XMX", "4G"),
            "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED",
            "-Djava.library.path=" + natives, "-Djna.tmpdir=" + natives,
            "-Dorg.lwjgl.system.SharedLibraryExtractPath=" + natives, "-Dio.netty.native.workdir=" + natives,
            "-Dminecraft.launcher.brand=manhattan-tests", "-Dminecraft.launcher.version=1",
            "-Dmanhattan.testbridge.port=%d" % PORT, "-Dmanhattan.tests=true",
            "-DFabricMcEmu= net.minecraft.client.main.Main "]
    if os.path.isfile(log_cfg):
        args.append("-Dlog4j.configurationFile=" + log_cfg)
    args += ["-cp", os.pathsep.join(cp), v["mainClass"],
             "--username", USERNAME, "--version", VERSION, "--gameDir", PROFILE,
             "--assetsDir", os.path.join(META, "assets"), "--assetIndex", v["assetIndex"]["id"],
             "--uuid", _offline_uuid(USERNAME), "--accessToken", "0", "--clientId", "0", "--xuid", "0",
             "--userType", "legacy", "--versionType", "release", "--width", "854" if WORKER else "1280", "--height", "480" if WORKER else "720"]
    os.makedirs(os.path.join(PROFILE, "logs"), exist_ok=True)
    out = open(os.path.join(PROFILE, "logs", "testbridge-stdout.txt"), "w", encoding="utf-8")
    flags = 0x00000200 | 0x00000008   # CREATE_NEW_PROCESS_GROUP | DETACHED_PROCESS
    # the window is shown without being activated (SW_SHOWNOACTIVATE): no focus, no mouse grab, whatever else the user is doing
    si = subprocess.STARTUPINFO()
    si.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    si.wShowWindow = 4
    proc = subprocess.Popen(args, cwd=PROFILE, stdout=out, stderr=subprocess.STDOUT, creationflags=flags, startupinfo=si)
    log("launched Minecraft (pid %d) in game dir %s" % (proc.pid, PROFILE))
    return proc


# ------------------------------------------------------------------ bridge

class BridgeError(Exception):
    pass


class Bridge:
    def __init__(self, port=PORT):
        self.port = port
        self.sock = None
        self.buf = b""

    def connect(self, timeout=2.0):
        self.close()
        self.sock = socket.create_connection(("127.0.0.1", self.port), timeout=timeout)
        self.sock.settimeout(60)
        return self

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
        self.sock = None
        self.buf = b""

    def raw(self, line):
        if not self.sock:
            self.connect()
        self.sock.sendall((line.replace("\n", " ") + "\n").encode("utf-8"))
        while b"\n" not in self.buf:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise BridgeError("bridge closed the connection")
            self.buf += chunk
        reply, self.buf = self.buf.split(b"\n", 1)
        return json.loads(reply.decode("utf-8"))

    def __call__(self, line):
        r = self.raw(line)
        if not r.get("ok"):
            raise BridgeError("%s -> %s" % (line, r.get("error")))
        return r

    def cmd(self, command):
        """A chat command as the player, e.g. cmd('/time set day')."""
        return self("chat " + (command if command.startswith("/") else "/" + command))

    def wait_ready(self, timeout=240, proc=None):
        end = time.time() + timeout
        while time.time() < end:
            if proc is not None and proc.poll() is not None:
                raise BridgeError("Minecraft exited (code %s) before the bridge came up; see %s"
                                  % (proc.returncode, os.path.join(PROFILE, "logs", "latest.log")))
            try:
                self.connect()
                self("ping")
                return
            except (OSError, BridgeError):
                time.sleep(1)
        raise BridgeError("the bridge did not answer within %ds" % timeout)

    def wait(self, pred, timeout, step=0.5, what="condition"):
        end = time.time() + timeout
        last = None
        while time.time() < end:
            last = pred()
            if last:
                return last
            time.sleep(step)
        raise BridgeError("timed out after %ds waiting for %s" % (timeout, what))


def stop_testing_instance(bridge=None, timeout=60):
    """Closes a running testing instance: politely through the bridge, never by killing other profiles."""
    pids = testing_pids()
    if not pids:
        return
    log("a testing instance is running (pid %s); closing it" % pids)
    try:
        b = bridge or Bridge().connect()
        b("quit")
    except (OSError, BridgeError) as e:
        log("bridge quit failed:", e)
    end = time.time() + timeout
    while time.time() < end and testing_pids():
        time.sleep(1)
    left = testing_pids()
    if left:
        for pid in left:   # only processes whose --gameDir is the testing profile
            subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)
        log("force-closed testing instance", left)
