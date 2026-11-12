"""In-game smoke tests for Manhattan on the "Manhatten Testing" profile.

  python tests/ingame/run_tests.py                 install, launch, run every test (parallel games), close them
  python tests/ingame/run_tests.py fill_water eat  only these tests
  python tests/ingame/run_tests.py -j 1            one game (still a copy, tmp/ingame/w1)
  python tests/ingame/run_tests.py --keep-open     leave the game running afterwards (drive it with bridge.py)
  python tests/ingame/run_tests.py --attach        use the copy game (tmp/ingame/w1) that is already running

Every game is a copy of the testing setup under tmp/ingame/wN (own mods, saves, logs, bridge port 25700+N).
The user's "Manhatten Testing" profile is never started, installed into or saved to.
Exit code 0 = every test passed. Never touches any other Minecraft instance.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import litematic  # noqa: E402
import mc  # noqa: E402
from mc import BridgeError, log  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
SCEN = os.path.join(HERE, "scenarios")

# the flat test world: bedrock -64, dirt -63..-62, grass -61, the player stands at -60
HOME = (100, -60, 100)
POOL = [(x, y, z) for y in (-62, -61) for x in (103, 104, 105) for z in (99, 100, 101)]


class Fail(Exception):
    pass


class Ctx:
    def __init__(self, b):
        self.b = b
        self.mark = 0

    def cmd(self, *commands):
        for c in commands:
            self.b.cmd(c)
        time.sleep(0.3)

    def start_log(self):
        self.mark = self.b("chatlog 0")["next"]

    def lines(self):
        return self.b("chatlog %d" % self.mark)["lines"]

    def wait_chat(self, text, timeout):
        return self.b.wait(lambda: next((l for l in self.lines() if text in l), None), timeout, what="chat '%s'" % text)

    def run(self, scenario):
        self.b("runfile " + os.path.join(SCEN, scenario))

    def state(self):
        return self.b("state")

    def block(self, p):
        return self.b("block %d %d %d" % p)["block"]

    def count(self, item):
        return self.b("count " + item)["count"]

    def script_done(self, timeout):
        self.b.wait(lambda: not self.b("script")["running"], timeout, what="the script to end")


def reset_area(t, gamemode="survival"):
    """Clean ground around HOME, nothing else alive, daylight that stays, the player there with an empty inventory."""
    t.cmd("/gamemode " + gamemode, "/effect clear @s", "/clear @s",
          "/time set noon", "/weather clear",
          "/kill @e[type=!player]",
          "/fill 92 -63 92 112 -62 112 dirt", "/fill 92 -61 92 112 -61 112 grass_block",
          "/fill 92 -60 92 112 -50 112 air",
          "/tp @s %d %d %d 0 0" % HOME)
    time.sleep(1)


# ------------------------------------------------------------------ tests

def test_smoke(t):
    """The script language runs, and the editor opens."""
    t.run("smoke.txt")
    t.wait_chat("TEST smoke n=3 names=2", 15)
    t.script_done(10)
    t.b("editor")
    scr = t.b.wait(lambda: t.b("screen")["screen"], 5, what="the editor")
    if not scr.endswith("QueueScreen"):
        raise Fail("editor did not open, screen is %s" % scr)
    t.b("screen close")
    return "script printed, editor opened"


def test_imgui_spike(t):
    """F9 opens the Dear ImGui spike window showing Runner state and a Stop button; keys do not leak while focused; resize works."""
    t.b("screen close")
    t.b("imgui close")
    t.run("imgui-spike.txt")
    t.wait_chat("TEST imgui started", 15)
    time.sleep(1.5)   # let wait 1 complete so actionsDone >= 1 and wait 30 is active

    # Press F9 (GLFW_KEY_F9 = 298) to open the ImGui spike window
    t.b("imgui key 298 1 0")
    t.b("imgui key 298 0 0")
    st = t.b.wait(lambda: (lambda s: s if s["spikeOpen"] and s["initialized"] and s["framesRendered"] >= 3 else None)(t.b("imgui")),
                  10, step=0.2, what="ImGui spike window to initialize and render")
    if not st["runnerRunning"] or st["runnerLine"] < 2 or st["runnerActions"] < 1:
        raise Fail("ImGui RunnerView state wrong while script runs: %s" % st)
    if not st["focused"]:
        raise Fail("ImGui window should have focus when opened: %s" % st)

    # While ImGui has focus, pressing J (74) or K (75) must not open QueueScreen or CaptureScreen
    t.b("imgui key 74 1 0")
    t.b("imgui key 74 0 0")
    t.b("imgui key 75 1 0")
    t.b("imgui key 75 0 0")
    time.sleep(0.3)
    scr = t.b("screen")["screen"]
    if scr != "":
        raise Fail("keys leaked into the game while ImGui had focus (opened %s)" % scr)

    # Window resize while ImGui is rendering must not crash and must update display size
    w0, h0 = st["displayW"], st["displayH"]
    t.b("imgui resize 960 540")
    t.b.wait(lambda: (lambda s: s if s["displayW"] == 960 and s["displayH"] == 540 else None)(t.b("imgui")),
             10, step=0.2, what="ImGui display size 960x540 after resize")
    t.b("imgui resize %d %d" % (w0, h0))
    st = t.b.wait(lambda: (lambda s: s if s["displayW"] == w0 and s["displayH"] == h0 else None)(t.b("imgui")),
                  10, step=0.2, what="ImGui display size restored")

    # Click the Stop button inside the ImGui window to stop the running script
    sx, sy = st["stopX"] + 15, st["stopY"] + 10
    t.b("imgui move %.1f %.1f" % (sx, sy))
    time.sleep(0.15)
    t.b("imgui press %.1f %.1f 0" % (sx, sy))
    time.sleep(0.15)
    t.b("imgui release %.1f %.1f 0" % (sx, sy))
    t.script_done(10)
    t.wait_chat("Script stopped", 5)

    # Press F9 again to close the ImGui spike window
    t.b("imgui key 298 1 0")
    t.b("imgui key 298 0 0")
    t.b.wait(lambda: not t.b("imgui")["spikeOpen"], 5, step=0.2, what="ImGui spike window to close")
    return "ImGui rendered (%d frames), Runner state shown, focus blocked J/K leak, resize 960x540 ok, Stop button halted script" % st["framesRendered"]


def test_viewport(t):
    """Stage 1 Game Viewport: top-right quarter blit, mouse remapping for chests & Pick block, keyboard focus gating, resize/GUI scale/F8."""
    reset_area(t)
    t.b("screen close")
    t.b("imgui close")
    t.b("imgui fullscreen false")
    t.b("chat /queue ui docked")
    t.wait_chat("UI mode set to 'docked'.", 5)

    # 1. Verify top-right quarter Game Viewport geometry
    st = t.b.wait(lambda: (lambda s: s if s["dockedActive"] and s["framesRendered"] > 0 else None)(t.b("imgui")),
                  10, step=0.2, what="docked viewport to render")
    dw, dh = st["displayW"], st["displayH"]
    exp_x = float(dw // 2)
    exp_y = 30.0
    exp_w = float(dw - int(exp_x))
    exp_h = float((dh - 30) // 2)
    if (st["vpX"], st["vpY"], st["vpW"], st["vpH"]) != (exp_x, exp_y, exp_w, exp_h):
        raise Fail("unexpected viewport rect: got (%s,%s,%s,%s), expected (%s,%s,%s,%s)"
                   % (st["vpX"], st["vpY"], st["vpW"], st["vpH"], exp_x, exp_y, exp_w, exp_h))

    # 2. Verify keyboard focus gating: clicking outside viewport blocks keys; clicking inside viewport focuses game
    t.b("imgui click 80 100 0")
    st = t.b("imgui")
    if st["gameFocused"]:
        raise Fail("clicking outside Game Viewport did not unfocus the game view: %s" % st)
    # Press E (69) and J (74) while gameFocused is False -> must NOT open InventoryScreen or QueueScreen
    t.b("imgui key 69 1 0")
    t.b("imgui key 69 0 0")
    t.b("imgui key 74 1 0")
    t.b("imgui key 74 0 0")
    time.sleep(0.25)
    if t.state()["screen"] != "":
        raise Fail("keyboard input leaked to game while Game Viewport was unfocused: screen=%s" % t.state()["screen"])

    # Click inside the Top-Right Game Viewport -> gameFocused becomes True
    vcx = st["vpX"] + st["vpW"] * 0.5
    vcy = st["vpY"] + st["vpH"] * 0.5
    t.b("imgui click %.1f %.1f 0" % (vcx, vcy))
    st = t.b("imgui")
    if not st["gameFocused"]:
        raise Fail("clicking inside Game Viewport did not focus the game view: %s" % st)

    # Press E (69) while gameFocused is True -> opens InventoryScreen
    t.b("imgui key 69 1 0")
    t.b("imgui key 69 0 0")
    t.b.wait(lambda: t.state()["screen"].endswith("InventoryScreen"), 5, step=0.2, what="InventoryScreen to open")
    t.b("screen close")
    t.b.wait(lambda: t.state()["screen"] == "", 5, step=0.2, what="InventoryScreen to close")

    # 3. Walking at the reduced viewport size
    t.cmd("/tp @s 100.5 -60 100.5 0 0")
    time.sleep(0.3)
    z0 = t.state()["z"]
    t.b("imgui key 87 1 0")   # hold W
    time.sleep(0.6)
    t.b("imgui key 87 0 0")   # release W
    time.sleep(0.2)
    z1 = t.state()["z"]
    if z1 - z0 < 0.8:
        raise Fail("walking forward in docked viewport did not move player: z0=%.2f z1=%.2f" % (z0, z1))

    # 4. Mining with Baritone at the reduced viewport size
    t.cmd("/tp @s 100.5 -60 100.5 0 0", "/setblock 102 -60 100 coal_ore", "/give @s diamond_pickaxe 1")
    time.sleep(0.3)
    t.run("viewport-mine.txt")
    t.wait_chat("TEST viewport mine done", 30)
    t.script_done(10)
    if "air" not in t.block((102, -60, 100)):
        raise Fail("Baritone mining in docked viewport failed: block=%s" % t.block((102, -60, 100)))

    # 5. Pick block (both middle-click in world and QueueScreen's Pick block button) inside the reduced viewport
    t.cmd("/clear @s", "/setblock 100 -60 102 gold_block", "/item replace entity @s inventory.0 with minecraft:gold_block 1",
          "/tp @s 100.5 -60 100.5 0 25")
    time.sleep(0.4)
    t.b("imgui click %.1f %.1f 2" % (vcx, vcy))   # middle-click (button 2) inside Game Viewport
    t.b.wait(lambda: t.b("held")["item"] == "minecraft:gold_block", 5, step=0.2, what="middle-click Pick block to select gold_block")

    # Also test QueueScreen's Pick block button via remapped viewport coordinates (at guiScale=1 so QueueScreen fits vertically in 480p)
    t.b("imgui guiscale 1")
    t.b("chat /queue clear")
    t.wait_chat("Script cleared.", 5)
    t.b("editor")
    t.b.wait(lambda: t.state()["screen"].endswith("QueueScreen"), 5, step=0.2, what="QueueScreen to open")
    st = t.b("imgui")
    gw, gh = st["guiScaledW"], st["guiScaledH"]
    w0 = min(gw - 24, 460)
    left = (gw - w0) // 2
    box_y = 40 + 40 + 6
    box_h = max(60, gh - box_y - 58 - 4 - 16 - 26 - 26 - 6)
    panel_y = box_y + box_h + 4
    py = panel_y + 58 + 16
    by = py + 26
    bw = (w0 - 20) // 6
    pick_gx = left + 4 * (bw + 4) + bw * 0.5
    pick_gy = by + 10
    t.b("imgui clickgui %.1f %.1f 0" % (pick_gx, pick_gy))
    t.b.wait(lambda: t.b("imgui")["picking"] and t.state()["screen"] == "", 5, step=0.2, what="Pick block mode from QueueScreen")
    t.b("imgui key 74 1 0")   # press J while aiming at (100, -60, 102)
    t.b("imgui key 74 0 0")
    t.b.wait(lambda: t.state()["screen"].endswith("QueueScreen"), 5, step=0.2, what="QueueScreen to reopen after Pick block")
    t.b("screen close")
    t.b("imgui guiscale 0")

    # 6. Opening a chest and clicking slots inside ContainerScreen via remapped Game Viewport coordinates
    t.cmd("/clear @s", "/setblock 100 -60 102 chest",
          "/item replace block 100 -60 102 container.0 with minecraft:diamond 16",
          "/tp @s 100.5 -60 100.5 0 25")
    time.sleep(0.4)
    t.b("imgui click %.1f %.1f 1" % (vcx, vcy))   # right-click chest inside Game Viewport
    t.b.wait(lambda: t.state()["screen"].endswith("ContainerScreen"), 5, step=0.2, what="ContainerScreen to open")
    time.sleep(0.25)
    st = t.b("imgui")
    gw, gh = st["guiScaledW"], st["guiScaledH"]
    c_left = (gw - 176) // 2
    c_top = (gh - 168) // 2
    # Click slot 0 of the chest (c_left + 16, c_top + 26), then click player hotbar slot 0 (c_left + 16, c_top + 150)
    t.b("imgui clickgui %.1f %.1f 0" % (c_left + 16, c_top + 26))
    time.sleep(0.2)
    t.b("imgui clickgui %.1f %.1f 0" % (c_left + 16, c_top + 150))
    time.sleep(0.2)
    t.b("screen close")
    t.b.wait(lambda: t.count("diamond") == 16, 5, step=0.2, what="16 diamonds transferred from chest via remapped viewport clicks")

    # 7. Window resize, GUI scale, and F8 fullscreen toggle
    t.b("imgui resize 1024 600")
    t.b.wait(lambda: t.b("imgui")["displayW"] == 1024 and t.b("imgui")["displayH"] == 600, 5, step=0.2, what="window resize 1024x600")
    st = t.b("imgui")
    if st["vpX"] != 512.0 or st["vpW"] != 512.0:
        raise Fail("viewport did not update on 1024x600 resize: %s" % st)

    t.b("imgui guiscale 1")
    t.b.wait(lambda: t.b("imgui")["guiScale"] == 1, 5, step=0.2, what="guiScale=1")
    t.b("imgui guiscale 0")

    t.b("imgui resize %d %d" % (dw, dh))
    t.b.wait(lambda: t.b("imgui")["displayW"] == dw and t.b("imgui")["displayH"] == dh, 5, step=0.2, what="window resize restore")

    # Press F8 (297) -> fullscreenGame = True, panels hidden, game fills window
    t.b("imgui key 297 1 0")
    t.b("imgui key 297 0 0")
    st = t.b.wait(lambda: (lambda s: s if s["fullscreenGame"] else None)(t.b("imgui")), 5, step=0.2, what="F8 fullscreenGame on")
    if st["dockedActive"] or (st["vpX"], st["vpY"], st["vpW"], st["vpH"]) != (0.0, 0.0, float(dw), float(dh)):
        raise Fail("F8 fullscreen did not expand viewport to full window: %s" % st)

    # Press F8 (297) again -> fullscreenGame = False, panels restored, game back in top-right quarter
    t.b("imgui key 297 1 0")
    t.b("imgui key 297 0 0")
    st = t.b.wait(lambda: (lambda s: s if not s["fullscreenGame"] and s["dockedActive"] else None)(t.b("imgui")),
                  5, step=0.2, what="F8 fullscreenGame off")
    if (st["vpX"], st["vpY"], st["vpW"], st["vpH"]) != (exp_x, exp_y, exp_w, exp_h):
        raise Fail("F8 restore did not return viewport to top-right quarter: %s" % st)

    t.b("chat /queue ui vanilla")
    t.wait_chat("UI mode set to 'vanilla'.", 5)
    return "top-right viewport %.0fx%.0f, focus gating ok, walking/Baritone mining/Pick block/chest slot click ok, resize/GUI scale/F8 ok" % (exp_w, exp_h)


def test_docked_shell(t):
    """Stage 2 docked shell: 2x2 DockSpace layout, Reset layout button + ini persistence, Top Bar status/Skip/Stop/F8,
    and ProgressModel segmented step bar + telemetry while a script runs."""
    reset_area(t)
    t.b("imgui close")
    t.b("imgui fullscreen off")
    t.b("imgui guiscale 0")
    t.b("chat /queue ui docked")
    t.wait_chat("UI mode set to 'docked'.", 5)
    time.sleep(0.3)

    st = t.b("imgui")
    if not st["dockedActive"] or st["layoutLabel"] != "2x2 Docked":
        raise Fail("docked shell not active with '2x2 Docked' label: %s" % st)

    # 1. Click 'Reset layout' in the Top Bar and check layoutResets increments & ini is saved
    resets0 = st["layoutResets"]
    if st["resetW"] <= 0 or st["resetH"] <= 0:
        raise Fail("Top Bar Reset layout button rect not recorded: %s" % st)
    rx = st["resetX"] + st["resetW"] / 2.0
    ry = st["resetY"] + st["resetH"] / 2.0
    t.b("imgui click %.1f %.1f 0" % (rx, ry))
    st = t.b.wait(lambda: (lambda s: s if s["layoutResets"] > resets0 else None)(t.b("imgui")),
                  5, step=0.2, what="Reset layout button click to rebuild 2x2 DockSpace")
    ini_path = os.path.join(os.environ.get("MANHATTAN_PROFILE", mc.PROFILE), "config", "manhattan-imgui.ini")
    if not os.path.exists(ini_path):
        raise Fail("manhattan-imgui.ini was not saved after layout build: %s" % ini_path)

    # 2. Run a multi-step script with an 'on' handler and verify ProgressModel segments + telemetry
    t.run("docked-shell.txt")
    t.wait_chat("TEST docked_shell started", 10)
    st = t.b.wait(lambda: (lambda s: s if s["runnerRunning"] and s["segRunning"] == 1 and s["segDone"] >= 1 else None)(t.b("imgui")),
                  10, step=0.2, what="ProgressModel running step segment")
    if st["totalSteps"] < 3 or st["currentStep"] < 2:
        raise Fail("unexpected step counts in ProgressModel: %s" % st)
    if st["interruptCount"] < 1:
        raise Fail("expected at least 1 active interrupt ('food < 14'): %s" % st)
    if "100" not in st["posText"]:
        raise Fail("expected player position near 100 in posText: %s" % st)

    # 3. Click Top Bar Stop button and verify the script stops cleanly
    if st["stopW"] <= 0 or st["stopH"] <= 0:
        raise Fail("Top Bar Stop button rect not recorded: %s" % st)
    sx = st["stopX"] + st["stopW"] / 2.0
    sy = st["stopY"] + st["stopH"] / 2.0
    t.b("imgui click %.1f %.1f 0" % (sx, sy))
    t.b.wait(lambda: not t.b("script")["running"], 10, step=0.2, what="Top Bar Stop button to stop script")
    st2 = t.b("imgui")
    if st2["runnerRunning"]:
        raise Fail("RunnerView still reports running after Top Bar Stop click: %s" % st2)

    t.b("chat /queue ui vanilla")
    t.wait_chat("UI mode set to 'vanilla'.", 5)
    return "2x2 Docked layout + Reset layout ok, ProgressModel (%d/%d steps, done=%d, running=%d, int=%d) ok, Top Bar Stop ok" % (
        st["currentStep"], st["totalSteps"], st["segDone"], st["segRunning"], st["interruptCount"])


def test_panels(t):
    """Stage 3 panels: Script Editor (file tabs, Syntax, Suggest Tab completion, ScriptCheck Test, Pick block, Run),
    Presets (insert/share), Chests (ChestIndexView search/sort/category/low-stock + Show in world / Copy pos / Go there),
    Areas & Capture (Fetch looked-at, Aim in world), Log (LogBuffer filters All/Errors/Baritone, Copy, Clear), and Assistant stub."""
    reset_area(t)
    t.b("imgui close")
    t.b("imgui fullscreen off")
    t.b("imgui guiscale 0")
    t.b("chat /queue ui docked")
    t.wait_chat("UI mode set to 'docked'.", 5)
    time.sleep(0.3)

    # 1. Script Editor: file tabs, Suggest completion, ScriptCheck error hint, Pick block, Presets insert, Run button
    p = t.b("panels addtab panel-test.txt // header")
    if p["tabCount"] < 2 or p["activeTab"] != "panel-test.txt":
        raise Fail("Script Editor did not create/switch to 'panel-test.txt' tab: %s" % p)

    p = t.b("panels setscript fill_wa")
    if p["suggestionCount"] < 1 or p["topSuggestion"] != "fill_water(":
        raise Fail("Suggest completion did not offer 'fill_water(' for 'fill_wa': %s" % p)
    p = t.b("panels complete")
    if p["scriptText"] != "fill_water(":
        raise Fail("Tab completion did not replace 'fill_wa' with 'fill_water(': %s" % p)

    # ScriptCheck Test button click
    t.b("panels setscript unknown_fn_xyz()")
    time.sleep(0.2)
    p = t.b("panels")
    if p["testW"] > 0 and p["testH"] > 0:
        t.b("imgui click %.1f %.1f 0" % (p["testX"] + p["testW"] / 2.0, p["testY"] + p["testH"] / 2.0))
        p = t.b.wait(lambda: (lambda s: s if s["errorCount"] >= 1 else None)(t.b("panels")), 5, step=0.2, what="ScriptCheck Test button error")
    else:
        p = t.b("panels check")
    if p["errorCount"] != 1:
        raise Fail("expected 1 ScriptCheck error for unknown_fn_xyz(): %s" % p)

    # Pick block in ImGui Script Editor
    t.cmd("/setblock 100 -60 102 emerald_block", "/tp @s 100.5 -60 100.5 0 25")
    time.sleep(0.3)
    t.b("panels setscript ")
    p = t.b("panels pick")
    if "pos(100, -60, 102)" not in p["scriptText"]:
        raise Fail("ImGui Pick block did not insert pos(100, -60, 102): %s" % p)

    # Presets: insert preset into active script editor
    p = t.b("panels presetinsert Wait for the morning")
    if p["lastInsertedPreset"] != "Wait for the morning" or "wait until day" not in p["scriptText"]:
        raise Fail("Preset insert did not add 'wait until day': %s" % p)

    # Run button in Script Editor + running line highlight
    t.b("panels setscript print(\"TEST panel editor run\")\\nwait 20")
    time.sleep(0.2)
    p = t.b("panels")
    if p["runW"] > 0 and p["runH"] > 0:
        t.b("imgui click %.1f %.1f 0" % (p["runX"] + p["runW"] / 2.0, p["runY"] + p["runH"] / 2.0))
    else:
        t.b("chat /queue run")
    t.wait_chat("TEST panel editor run", 10)
    t.b.wait(lambda: t.b("imgui")["runnerRunning"] and t.b("imgui")["runnerLine"] == 2, 5, step=0.2, what="running line highlight on line 2")
    t.b("stopscript")
    t.b.wait(lambda: not t.b("script")["running"], 5, step=0.2, what="script to stop")
    t.b("panels selecttab 0")

    # 2. Chests panel: ChestIndexView search, sort, category filter, low-stock filter, and detail pane actions
    t.b("panels clearchests")
    t.b("panels indexchest 102 -60 100 dirt:128,diamond_pickaxe:1,bread:8")
    p = t.b("panels chestsort COUNT false")
    if p["chestRowCount"] != 3 or p["firstChestItem"] != "dirt" or p["firstChestCount"] != 128:
        raise Fail("ChestIndexView sort by COUNT desc failed: %s" % p)

    p = t.b("panels chestfilter - TOOLS false")
    if p["chestRowCount"] != 1 or p["firstChestItem"] != "diamond_pickaxe":
        raise Fail("ChestIndexView TOOLS category filter failed: %s" % p)

    p = t.b("panels chestfilter - ALL true")
    if p["chestRowCount"] != 2:
        raise Fail("ChestIndexView lowStockOnly filter expected 2 rows (pickaxe, bread): %s" % p)
    t.b("panels chestfilter - ALL false")

    p = t.b("panels chestshow 102 -60 100")
    if p["highlightedChest"] != "102,-60,100":
        raise Fail("ChestIndexView Show in world did not set highlightedChest: %s" % p)

    p = t.b("panels chestcopy 102 -60 100")
    if p["lastCopiedPos"] != "pos(102, -60, 100)":
        raise Fail("ChestIndexView Copy pos did not copy pos(102, -60, 100): %s" % p)

    t.cmd("/setblock 106 -60 100 chest", "/tp @s 100.5 -60 100.5 0 0")
    time.sleep(0.3)
    t.b("panels chestgoto 106 -60 100")
    t.b.wait(lambda: t.state()["x"] > 103.0, 15, step=0.2, what="Baritone #goto from Chests detail pane")
    t.cmd("/setblock 106 -60 100 air", "/tp @s 100.5 -60 100.5 0 25")

    # 3. Areas & Capture panels: Fetch looked-at and Aim in world
    t.cmd("/setblock 100 -60 102 chest")
    time.sleep(0.3)
    p0 = t.b("panels capturerole dump")
    entries0 = p0["captureEntryCount"]
    p1 = t.b("panels capturefetch")
    if p1["captureEntryCount"] <= entries0:
        raise Fail("Capture Fetch looked-at did not add an entry to the capture sheet: %s -> %s" % (entries0, p1))

    p2 = t.b("panels captureaim")
    if not p2["captureAiming"]:
        raise Fail("Capture Aim in world did not enter aiming mode: %s" % p2)
    # Focus game viewport and press J (74) to capture the looked-at chest and end aiming without opening vanilla CaptureScreen
    st_im = t.b("imgui")
    t.b("imgui click %.1f %.1f 0" % (st_im["vpX"] + st_im["vpW"] / 2.0, st_im["vpY"] + st_im["vpH"] / 2.0))
    time.sleep(0.15)
    t.b("imgui key 74 1 0")
    t.b("imgui key 74 0 0")
    p3 = t.b.wait(lambda: (lambda s: s if not s["captureAiming"] else None)(t.b("panels")), 5, step=0.2, what="J key to finish Capture aiming in docked mode")
    if t.state()["screen"] != "":
        raise Fail("docked Capture aiming opened a vanilla screen instead of staying in docked UI: %s" % t.state()["screen"])

    # 4. Log panel: All / Errors / Baritone filters, multi-line selection, Copy, Clear
    p_log = t.b("panels logfilter ALL")
    if p_log["logTotal"] < 3 or p_log["logErrors"] < 1 or p_log["logBaritone"] < 1:
        raise Fail("expected LogBuffer to have INFO, ERROR, and BARITONE entries: %s" % p_log)
    p_sel = t.b("panels logselect 0,1")
    if p_sel.get("selectedLogCount") != 2:
        raise Fail("expected selectedLogCount == 2 after logselect 0,1: %s" % p_sel)
    p_cp = t.b("panels logcopyselected")
    if not p_cp.get("lastCopiedLog") or "\n" not in p_cp.get("lastCopiedLog", ""):
        raise Fail("expected logcopyselected to copy 2 selected lines: %s" % p_cp)
    p_err = t.b("panels logfilter ERRORS")
    if p_err["logFiltered"] != p_err["logErrors"]:
        raise Fail("LogBuffer ERRORS filter count mismatch: %s" % p_err)
    p_bar = t.b("panels logfilter BARITONE")
    if p_bar["logFiltered"] != p_bar["logBaritone"]:
        raise Fail("LogBuffer BARITONE filter count mismatch: %s" % p_bar)
    t.b("panels logcopy")
    p_clr = t.b("panels logclear")
    if p_clr["logTotal"] != 0:
        raise Fail("LogBuffer clear did not empty the ring buffer: %s" % p_clr)
    t.b("panels logfilter ALL")

    t.b("chat /queue ui vanilla")
    t.wait_chat("UI mode set to 'vanilla'.", 5)
    return "Editor (tabs/Syntax/Suggest/Test/Pick/Run) ok, Presets ok, Chests (search/sort/filter/Show/Copy/Go) ok, Areas/Capture ok, Log select/filters/Clear ok"




def test_fill_water(t):
    """fill_water("dirt") fills a 3x3, 2-deep pool, never a cell above water."""
    reset_area(t)
    t.cmd("/fill 103 -62 99 105 -61 101 water", "/give @s dirt 64")
    time.sleep(1)
    wet = [p for p in POOL if "water" in t.block(p)]
    if len(wet) != len(POOL):
        raise Fail("pool setup failed: %d of %d cells hold water" % (len(wet), len(POOL)))
    dirt0 = t.count("dirt")
    t.run("fill-water.txt")
    violations, end = [], time.time() + 180
    done_at = None
    while time.time() < end:
        cells = {p: t.block(p) for p in POOL}
        for (x, y, z), bl in cells.items():
            if y == -61 and "water" not in bl and "water" in cells[(x, -62, z)]:
                v = "(%d %d %d) filled while (%d -62 %d) below was still wet" % (x, y, z, x, z)
                if v not in violations:
                    violations.append(v)
        if not t.b("script")["running"]:
            done_at = time.time()
            break
        time.sleep(0.5)
    if done_at is None:
        t.b("stopscript")
        raise Fail("fill_water did not finish within 180 s; still wet: %s"
                   % [p for p in POOL if "water" in t.block(p)])
    still = [p for p in POOL if "water" in t.block(p)]
    msg = next((l for l in t.lines() if "TEST fill_water" in l), None)
    if still:
        raise Fail("cells still wet: %s (%s)" % (still, msg))
    if violations:
        raise Fail("not bottom-up: " + "; ".join(violations))
    if not msg or "left=0" not in msg:
        raise Fail("script did not report left=0: %s" % msg)
    used = dirt0 - t.count("dirt")
    if used < 1:
        raise Fail("no dirt was used from the inventory")
    return "18 cells filled bottom-up, %d dirt used" % used


def test_eat(t):
    """on food < 14 ... eat() fires and eats bread."""
    reset_area(t)
    t.cmd("/give @s bread 8")
    t.run("eat.txt")
    t.wait_chat("TEST eat armed", 10)
    t.cmd("/effect give @s minecraft:hunger 60 50 true")
    t.b.wait(lambda: t.state()["food"] < 14, 60, what="food < 14")
    t.cmd("/effect clear @s minecraft:hunger")
    line = t.wait_chat("TEST ate", 40)
    t.b("stopscript")
    bread = t.count("bread")
    if bread >= 8:
        raise Fail("the handler ran but no bread was eaten (%s)" % line)
    return "%s, %d bread left" % (line.split("TEST ", 1)[1], bread)


def test_defend(t):
    """on mobs > 0 ... defend() kills a zombie."""
    reset_area(t)
    t.cmd("/give @s diamond_sword 1", "/effect give @s minecraft:resistance 120 4 true", "/time set midnight")
    t.run("defend.txt")
    t.wait_chat("TEST defend armed", 10)
    t.cmd("/summon minecraft:zombie 104 -60 100 {PersistenceRequired:1b}")
    t.b.wait(lambda: t.b("entities zombie 20")["count"] > 0, 5, what="the zombie to appear")
    line = t.wait_chat("TEST defended", 60)
    t.b.wait(lambda: t.b("entities zombie 20")["count"] == 0, 10, what="the zombie to die")
    t.b("stopscript")
    t.cmd("/time set noon")
    return "zombie killed (%s)" % line.split("TEST ", 1)[1]


def test_clear_resume(t):
    """clear_area() is interrupted by eat() and carries on at the same stop instead of starting over."""
    reset_area(t)
    t.cmd("/fill 102 -60 92 108 -59 112 dirt", "/give @s diamond_shovel 1", "/give @s bread 8")
    t.run("clear-resume.txt")
    t.wait_chat("TEST clear armed", 10)
    t.wait_chat("Clearing the area from the top", 10)
    time.sleep(4)   # let it break a few blocks first
    t.cmd("/effect give @s minecraft:hunger 60 50 true")
    t.b.wait(lambda: t.state()["food"] < 14, 60, what="food < 14")
    t.cmd("/effect clear @s minecraft:hunger")
    t.wait_chat("TEST ate", 40)
    line = t.wait_chat("TEST cleared", 240)
    lines = t.lines()
    starts = sum("Clearing the area from the top" in l for l in lines)
    resumed = [l for l in lines if "Carrying on clearing" in l]
    if not resumed:
        raise Fail("no 'Carrying on clearing' after eating: it did not resume (%s)" % line)
    if starts != 1:
        raise Fail("clear_area started from the top %d times (should be once)" % starts)
    solid = [(x, y, z) for x in range(102, 109) for y in (-60, -59) for z in range(92, 113)
             if "air" not in t.block((x, y, z))]
    if solid:
        raise Fail("%d blocks left in the strip, e.g. %s (%s)" % (len(solid), solid[:5], line))
    if "left=0" not in line:
        raise Fail("clear_area did not return 0: " + line)
    return "strip cleared, resumed once eating was done (%s)" % resumed[0].split("] ", 1)[-1][:80]


def test_dump(t):
    """dump() keeps only what the want-list asks for (the better copy) and good food; junk and the hotbar go."""
    reset_area(t)
    t.cmd("/setblock 102 -60 100 chest",
          "/give @s cobblestone 64",                       # lands in the hotbar
          "/give @s diamond_shovel[damage=1000] 1", "/give @s diamond_shovel 1",
          "/give @s iron_shovel 1", "/give @s bow 1", "/give @s leather_chestplate 1",
          "/give @s rotten_flesh 10", "/give @s bread 8", "/give @s golden_apple 1")
    t.run("dump.txt")
    t.wait_chat("TEST dumped", 60)
    expect = {"diamond_shovel": 1, "iron_shovel": 0, "bow": 0, "leather_chestplate": 0, "rotten_flesh": 0,
              "cobblestone": 0, "bread": 8, "golden_apple": 1}
    got = {k: t.count(k) for k in expect}
    wrong = {k: (got[k], v) for k, v in expect.items() if got[k] != v}
    if wrong:
        raise Fail("inventory after dump (have, expected): %s" % wrong)
    return "kept 1 diamond shovel, bread and the golden apple; junk and the hotbar's cobblestone went into the chest"


# ------------------------------------------------------------------ schematic builder (Litematica placement + Baritone)

SCHEM_ORIGIN = (108, -60, 108)
SCHEM_SIZE = (5, 3, 5)


def schem_model():
    """The test schematic: stone floor, cobblestone walls 2 high, stairs and slabs at the corners, a wall torch,
    one redstone dust inside. Everything else inside is air."""
    b = {}
    for x in range(5):
        for z in range(5):
            b[(x, 0, z)] = "minecraft:stone"
            ring = x in (0, 4) or z in (0, 4)
            corner = x in (0, 4) and z in (0, 4)
            if ring and not corner:
                b[(x, 1, z)] = "minecraft:cobblestone"
                b[(x, 2, z)] = "minecraft:cobblestone"
            if corner:
                face = {(0, 0): "north", (4, 0): "east", (0, 4): "south", (4, 4): "west"}[(x, z)]
                half = "top" if (x + z) % 8 == 0 else "bottom"   # (0,0) and (4,4) upside down
                b[(x, 1, z)] = "minecraft:oak_stairs[facing=%s,half=%s,shape=straight,waterlogged=false]" % (face, half)
                b[(x, 2, z)] = "minecraft:oak_slab[type=%s,waterlogged=false]" % ("top" if (x, z) == (0, 0) else "bottom")
    b[(3, 2, 0)] = "minecraft:oak_log[axis=x]"
    b[(2, 2, 1)] = "minecraft:wall_torch[facing=south]"
    b[(2, 1, 2)] = "minecraft:redstone_wire[east=none,north=none,power=0,south=none,west=none]"
    return b


def prepare_schem():
    """The schematic file and Litematica's placement file for the testing world (before the game starts)."""
    sch = os.path.join(mc.PROFILE, "schematics", "manhattan-test-house.litematic")
    litematic.write(sch, "manhattan-test-house", SCHEM_SIZE, schem_model())
    cfgdir = os.path.join(mc.PROFILE, "config", "litematica")
    os.makedirs(cfgdir, exist_ok=True)
    with open(os.path.join(cfgdir, "litematica_%s_dim_minecraft_overworld.json" % mc.WORLD), "w", encoding="utf-8") as f:
        f.write(litematic.placements_json([("test house", sch, SCHEM_ORIGIN, "NONE", "NONE", True)]))


def schem_supplies(t):
    t.cmd("/give @s stone 64", "/give @s cobblestone 64", "/give @s oak_stairs 8", "/give @s oak_slab 8",
          "/give @s torch 8", "/give @s redstone 8", "/give @s dirt 64", "/give @s oak_log 8", "/give @s diamond_pickaxe 1")


def schem_wrong(t, ignore=()):
    """Positions whose block differs from the model: [(pos, wanted, got)]. Air for every position the model leaves empty."""
    return model_diff(t, SCHEM_ORIGIN, SCHEM_SIZE, schem_model(), ignore=ignore)


def model_diff(t, origin, size, model, region=None, ignore=()):
    """Blocks of the region (default: the box of the model) that differ from it: [(pos, wanted, got)].
    Inside the box the model's block is wanted (facing, half, type and axis are compared too), outside it air."""
    sx, sy, sz = size
    if region is None:
        region = ((origin[0], origin[1], origin[2]), (origin[0] + sx - 1, origin[1] + sy - 1, origin[2] + sz - 1))
    (x1, y1, z1), (x2, y2, z2) = region
    bad = []
    for x in range(x1, x2 + 1):
        for y in range(y1, y2 + 1):
            for z in range(z1, z2 + 1):
                pos = (x, y, z)
                if pos in ignore:
                    continue
                lx, ly, lz = x - origin[0], y - origin[1], z - origin[2]
                inside = 0 <= lx < sx and 0 <= ly < sy and 0 <= lz < sz
                want_full = model.get((lx, ly, lz), "minecraft:air") if inside else "minecraft:air"
                want = want_full.split("[")[0]
                got = t.block(pos).split("|")[0]
                if got != want:
                    bad.append((pos, want.replace("minecraft:", ""), got.replace("minecraft:", "")))
                elif "[" in want_full:
                    props = dict(kv.split("=") for kv in want_full.split("[")[1][:-1].split(","))
                    st = t.b("blockstate %d %d %d" % pos)["state"]
                    have = dict(kv.split("=") for kv in st.split("[")[1][:-1].split(",")) if "[" in st else {}
                    for k, v in props.items():
                        if k in ("shape", "waterlogged", "power", "north", "east", "south", "west"):
                            continue
                        if have.get(k) != v:
                            bad.append((pos, "%s %s=%s" % (want.replace("minecraft:", ""), k, v), "%s=%s" % (k, have.get(k))))
    return bad


def schem_run(t, scenario, timeout=240):
    t.run(scenario)
    t.b.wait(lambda: not t.b("script")["running"], timeout, step=1, what="the schematic build to end")
    return next((l for l in t.lines() if "TEST schem" in l), "(no TEST line)")


def schem_report(t):
    t.start_log()
    t.cmd("/schem check 1 fix")
    time.sleep(1)
    return [l.split("] ", 1)[-1] for l in t.lines() if "Check " in l or "wants" in l]


def test_schem_list(t):
    """/schem list finds the placement from Litematica (live or its config file), /schem materials and check run."""
    reset_area(t)
    schem_supplies(t)
    t.start_log()
    t.cmd("/schem list")
    time.sleep(1)
    lines = t.lines()
    if not any("test house" in l for l in lines):
        raise Fail("/schem list did not show the placement: %s" % lines)
    t.cmd("/schem materials 1 add")
    time.sleep(1)
    mat = " | ".join(l.split("] ", 1)[-1] for l in t.lines() if "x" in l and "Materials" not in l)
    for need in ("stone x25", "cobblestone x23", "oak_stairs x4", "oak_slab x4", "torch x1", "redstone x1"):
        if need not in mat:
            raise Fail("material list lacks '%s': %s" % (need, mat))
    return "listed; materials %s" % mat[:150]


def test_schem_add(t):
    """add: empty spots are filled, a wrong block that is already there is left alone, schematic air is ignored."""
    reset_area(t)
    schem_supplies(t)
    wrong_at = (109, -60, 109)
    t.cmd("/setblock %d %d %d diamond_block" % wrong_at, "/setblock 109 -59 110 dirt")   # a wrong floor block, a block inside the air
    line = schem_run(t, "schem-add.txt")
    bad = schem_wrong(t, ignore=[wrong_at, (109, -59, 110)])
    keep = t.block(wrong_at), t.block((109, -59, 110))
    report = schem_report(t)
    if bad:
        raise Fail("add mode: %d positions differ, e.g. %s (%s) %s" % (len(bad), bad[:6], line, report))
    if "diamond_block" not in keep[0] or "dirt" not in keep[1]:
        raise Fail("add mode touched existing blocks: %s" % (keep,))
    return "built; foreign blocks left alone (%s); %s" % (line.split("TEST ", 1)[-1], "; ".join(report[:2]))


def test_schem_fix(t):
    """fix: a wrong block is broken and replaced; schematic air is still ignored."""
    reset_area(t)
    schem_supplies(t)
    t.cmd("/setblock 109 -60 109 diamond_block", "/setblock 109 -59 110 dirt")
    line = schem_run(t, "schem-fix.txt")
    bad = schem_wrong(t, ignore=[(109, -59, 110)])
    report = schem_report(t)
    if bad:
        raise Fail("fix mode: %d positions differ, e.g. %s (%s) %s" % (len(bad), bad[:6], line, report))
    if "dirt" not in t.block((109, -59, 110)):
        raise Fail("fix mode dug schematic air (that is exact mode's job)")
    return "wrong block replaced; air left (%s); %s" % (line.split("TEST ", 1)[-1], "; ".join(report[:2]))


def test_schem_exact(t):
    """exact: built into a hill, schematic air is dug out first (top-down), then everything is built."""
    reset_area(t)
    schem_supplies(t)
    t.cmd("/fill 106 -60 106 114 -57 114 dirt")
    line = schem_run(t, "schem-exact.txt")
    bad = schem_wrong(t)
    report = schem_report(t)
    if bad:
        raise Fail("exact mode: %d positions differ, e.g. %s (%s) %s" % (len(bad), bad[:6], line, report))
    outside = t.block((106, -59, 106))
    if "dirt" not in outside:
        raise Fail("exact mode dug outside the schematic: (106 -59 106) is %s" % outside)
    return "dug into the hill and built (%s); %s" % (line.split("TEST ", 1)[-1], "; ".join(report[:2]))

def test_schem_fix_wrong(t):
    """fix: wrong blocks sitting exactly where the schematic wants redstone, a stair, a wall torch, a log and a slab are
    broken and the right block is placed (also a stair that stands there with the wrong facing)."""
    reset_area(t)
    schem_supplies(t)
    t.cmd("/setblock 110 -59 110 dirt",                                   # redstone dust spot
          "/setblock 108 -59 108 cobblestone",                           # stair spot, other block
          "/setblock 112 -59 108 oak_stairs[facing=south,half=top]",     # stair spot, same block, wrong facing and half
          "/setblock 110 -58 109 cobblestone",                           # wall torch spot
          "/setblock 111 -58 108 stone",                                 # log spot
          "/setblock 112 -58 108 oak_slab[type=top]")                    # slab spot, wrong type
    line = schem_run(t, "schem-fix.txt")
    bad = schem_wrong(t)
    report = schem_report(t)
    if bad:
        raise Fail("fix mode left %d positions wrong, e.g. %s (%s) %s" % (len(bad), bad[:6], line, report))
    return "six wrong blocks on redstone/stair/torch/log/slab spots all replaced (%s); %s" % (line.split("TEST ", 1)[-1], "; ".join(report[:1]))



# ------------------------------------------------------------------ canal features: one small world of its own per test

def write_placements(name, placements):
    """placements: [(label, size, origin, model)]: the schematic files and Litematica's placement file for world `name`."""
    cfgdir = os.path.join(mc.PROFILE, "config", "litematica")
    schdir = os.path.join(mc.PROFILE, "schematics")
    os.makedirs(cfgdir, exist_ok=True)
    os.makedirs(schdir, exist_ok=True)
    items = []
    for label, size, origin, model in placements:
        sch = os.path.join(schdir, "%s-%s.litematic" % (name, label.replace(" ", "-")))
        litematic.write(sch, label, size, model)
        items.append((label, sch, origin, "NONE", "NONE", True))
    with open(os.path.join(cfgdir, "litematica_%s_dim_minecraft_overworld.json" % name), "w", encoding="utf-8") as f:
        f.write(litematic.placements_json(items))


def give_kit(t):
    """What the real run carries from the tools chest: a diamond pickaxe, shovel and axe with Efficiency V, and food."""
    eff = '[enchantments={"minecraft:efficiency":5}]'
    t.cmd("/give @s diamond_pickaxe%s 1" % eff, "/give @s diamond_shovel%s 1" % eff,
          "/give @s diamond_axe%s 1" % eff, "/give @s bread 32")
    time.sleep(1)
    got = {k: t.count(k) for k in ("diamond_pickaxe", "diamond_shovel", "diamond_axe")}
    if any(v != 1 for v in got.values()):
        raise Fail("the tool kit did not reach the inventory: %s" % got)


RUN_ID = int(time.time())


def new_world(t, name, placements=(), kit=True):
    """Leaves the current world, creates (flat, creative cheats, then survival) a world of its own and puts the player
    at (100, -60, 100). The Litematica placements are written before the world loads. kit: the tools of the real run."""
    name = "%s-%d" % (name, RUN_ID)     # a world of this run only: no terrain or inventory left over from earlier runs
    if placements:
        write_placements(name, placements)
    fresh_world(t, name)
    wait_server(t)
    t.cmd("/clear @s", "/tp @s 100 -60 100 0 0")
    t.cmd("/buildtools protect clear")     # safe areas live in the game's config: none may leak in from an earlier test
    if kit:
        give_kit(t)
    time.sleep(2)


def fill(t, box, block):
    t.cmd("/fill %d %d %d %d %d %d %s" % (box[0], box[1], box[2], box[3], box[4], box[5], block))


def chest_with(t, p, items, kind="chest"):
    """A container at p holding [(item, count)] in slots 0, 1, ... (/item replace block, needs cheats)."""
    t.cmd("/setblock %d %d %d minecraft:%s" % (p[0], p[1], p[2], kind))
    for slot, (item, n) in enumerate(items):
        t.cmd("/item replace block %d %d %d container.%d with minecraft:%s %d" % (p[0], p[1], p[2], slot, item, n))


def layers(t, boxes):
    """Per height (lowest first): how many blocks in the boxes are solid, and how many hold water; summed over the boxes."""
    return t.b("layers " + " ".join(str(v) for b in boxes for v in b))


def wait_marker_or_end(t, text, timeout):
    """Waits until a chat line holds `text` (returns it) or the script has ended without it (returns None at once,
    so a script that stopped with PROBLEM or 'stopped' fails right away instead of at the timeout)."""
    end, seen_running, started = time.time() + timeout, False, time.time()
    while time.time() < end:
        line = next((l for l in t.lines() if text in l), None)
        if line:
            return line
        running = t.b("script")["running"]
        seen_running = seen_running or running
        if not running and (seen_running or time.time() - started > 3):
            time.sleep(1)   # the marker and the end can come in the same moment: look at the chat once more
            return next((l for l in t.lines() if text in l), None)
        time.sleep(0.3)
    raise BridgeError("timed out after %ds waiting for chat '%s'" % (timeout, text))


def run_scenario(t, scen, marker, timeout=240):
    """Runs a scenario and waits for its marker line, or for the script to end without it (then the test fails at once,
    with the script's own PROBLEM / stopped lines)."""
    t.run(scen)
    line = wait_marker_or_end(t, marker, timeout)
    if line is None:
        why = [l for l in t.lines() if "PROBLEM" in l or "stopped" in l.lower() or "Fill stopped" in l or "Stopped" in l]
        raise Fail("the script ended without '%s': %s" % (marker, (why or t.lines()[-3:])[-1][:200]))
    t.script_done(60)
    return line


def wet_in(t, boxes):
    return sum(layers(t, boxes)["water"])


def scaffold_left(t, region):
    """Positions of the region that hold a block (not air)."""
    left = []
    (x1, y1, z1), (x2, y2, z2) = region
    for x in range(x1, x2 + 1):
        for y in range(y1, y2 + 1):
            for z in range(z1, z2 + 1):
                if "air" not in t.block((x, y, z)):
                    left.append((x, y, z))
    return left


def test_canal_layers(t, scen="canal-layers.txt", topdown=True):
    """clear_area(2) (top down, 2 layers per pass) on trench_a: 8 boxes (2 x 3 x 4 each) dug INTO a dirt plateau,
    like the real trench. All of it is dug, layers top-down, the plateau between the boxes stays."""
    new_world(t, scen[:-4])
    fill(t, (96, -61, 95, 134, -58, 105), "dirt")                                  # the plateau, 4 high
    t.cmd("/tp @s 98 -57 97 0 0")                                                  # on top of it, beside the trench
    # 8 adjacent boxes like the real trench_a: 4 deep and 3 deep in turn (2 x 3 each)
    boxes = [(100 + 2 * i, -61 if i % 2 == 0 else -60, 99, 101 + 2 * i, -58, 101) for i in range(8)]
    # not in the area, must stay: the plateau on both sides and ends, and the layer under the 3-deep boxes
    gaps = [(98, -61, 97, 117, -58, 98), (98, -61, 102, 117, -58, 103), (98, -61, 99, 99, -58, 101), (116, -61, 99, 117, -58, 101)]
    gaps += [(102 + 4 * i, -61, 99, 103 + 4 * i, -61, 101) for i in range(4)]
    gap_before = layers(t, gaps)["solid"]
    t.run(scen)
    t.wait_chat("TEST layers start", 30)
    init, samples, violations = None, 0, []
    end = time.time() + 240
    while True:
        solid = layers(t, boxes)["solid"]          # index 0 = y -61 ... 3 = y -58 (y -61 only in the 4-deep boxes)
        if init is None:
            init = list(solid)
        samples += 1
        lower_started = solid[0] < init[0] or solid[1] < init[1]      # y -61 / -60 touched
        if lower_started and (solid[2] or solid[3]):                  # while y -59 / -58 still solid
            violations.append(list(solid))
        if not t.b("script")["running"]:
            break
        if time.time() > end:
            t.b("stopscript")
            raise Fail("#sel ca did not finish in 240 s; solid per height now %s" % solid)
        time.sleep(0.25)
    if not any("TEST layers done" in l for l in t.lines()):
        raise Fail("the script did not reach 'TEST layers done' (%s)" % t.lines()[-4:])
    left = layers(t, boxes)["solid"]
    gap_after = layers(t, gaps)["solid"]
    if sum(left):
        raise Fail("blocks left in trench_a per height: %s" % left)
    if gap_after != gap_before:
        raise Fail("blocks outside trench_a were dug: %s -> %s" % (gap_before, gap_after))
    if violations and topdown:
        raise Fail("not top-down: a lower layer was dug while the top was still solid, e.g. %s (%d of %d samples)"
                   % (violations[0], len(violations), samples))
    return "trench_a (8 adjacent boxes, 168 dirt dug into a plateau) dug top-down by clear_area(2) in %d samples; plateau around and below untouched" % samples


def test_canal_pillar_scaffold(t):
    """PILLAR test, not a canal dig: #sel ca on a stone patch floating in the air; Baritone builds dirt up to it, and
    collect_scaffold() takes the dirt back down."""
    new_world(t, "canal-pillar-scaffold")
    fill(t, (103, -56, 103, 105, -56, 105), "stone")
    t.cmd("/give @s dirt 64")
    time.sleep(1)
    line = run_scenario(t, "canal-pillar-scaffold.txt", "TEST scaffold done", 240)
    if not line:
        raise Fail("the script did not finish")
    stone_left = layers(t, [(103, -56, 103, 105, -56, 105)])["solid"]
    if sum(stone_left):
        raise Fail("the floating stone is not all dug: %s" % stone_left)
    left = scaffold_left(t, ((100, -60, 100), (108, -57, 108)))
    if left:
        raise Fail("%d scaffold blocks left after collect_scaffold(), e.g. %s" % (len(left), left[:6]))
    dirt = t.count("dirt")
    if dirt != 64:
        raise Fail("dirt after collect_scaffold(): %d, expected 64 (not all of the scaffold was picked up)" % dirt)
    return "patch dug; no scaffold left, 64 dirt back in the inventory"


SCAFF_SCHEM = {(x, 0, z): "minecraft:dirt" for x in range(3) for z in range(3)}
SCAFF_SCHEM.update({(x, 1, z): "minecraft:stone" for x in range(3) for z in range(3) if x in (0, 2) or z in (0, 2)})


def test_canal_scaffold_schem(t):
    """collect_scaffold() after schem_build: the scaffolding goes, the schematic's own dirt stays."""
    origin = (104, -57, 104)
    new_world(t, "canal-scaffold-schem", [("scaffold house", (3, 2, 3), origin, SCAFF_SCHEM)])
    t.cmd("/give @s dirt 64", "/give @s stone 16")
    time.sleep(1)
    line = run_scenario(t, "canal-scaffold-schem.txt", "TEST scaffold done", 300)
    if not line:
        raise Fail("the script did not finish")
    column = [(y, t.block((104, y, 104)).split("|")[0].replace("minecraft:", "")) for y in (-61, -60, -59, -58, -57, -56)]
    bad = model_diff(t, origin, (3, 2, 3), SCAFF_SCHEM, region=((100, -60, 100), (110, -55, 110)))
    if bad:
        raise Fail("%d blocks differ from the schematic (and air elsewhere), e.g. %s; column x104 z104 y-61..-56: %s (%s)"
                   % (len(bad), bad[:6], column, line))
    dirt = t.count("dirt")
    if dirt != 64 - 9:
        raise Fail("dirt in the inventory: %d, expected %d (9 stay in the schematic)" % (dirt, 64 - 9))
    return "schematic built on scaffolding; scaffolding gone, schematic dirt stays"


def test_canal_water_pond(t):
    """fill_water("dirt", chest) on a 5x2x5 pond: 50 cells, 4 dirt in the inventory, the rest fetched from the chest."""
    new_world(t, "canal-water-pond")
    fill(t, (104, -62, 100, 108, -61, 104), "water")
    chest_with(t, (110, -60, 102), [("dirt", 64)])
    t.cmd("/give @s dirt 4")
    time.sleep(1)
    line = run_scenario(t, "canal-water-pond.txt", "TEST pond", 300)
    if not line:
        raise Fail("the script did not finish")
    wet = wet_in(t, [(104, -62, 100, 108, -61, 104)])
    if wet:
        raise Fail("%d wet cells left" % wet)
    if "left=0" not in line:
        raise Fail("fill_water did not return 0: %s" % line)
    dirt = t.count("dirt")
    if dirt != 4 + 64 - 50:
        raise Fail("dirt in the inventory %d, expected %d (50 cells; 46 fetched from the chest)" % (dirt, 4 + 64 - 50))
    return "50 cells dry, 46 dirt fetched from the chest"


def test_canal_water_river(t):
    """fill_water("dirt") on a flowing river (level 3) with dry edges: no wet cell left in the selection."""
    new_world(t, "canal-water-river")
    fill(t, (100, -61, 99, 120, -61, 103), "air")
    fill(t, (100, -61, 101, 120, -61, 101), "minecraft:water[level=3]")
    t.cmd("/setblock 100 -61 101 minecraft:water")      # the source: flowing water without one dries up
    t.cmd("/give @s dirt 64")
    time.sleep(3)
    before = wet_in(t, [(100, -61, 99, 120, -61, 103)])
    if before == 0:
        raise Fail("river setup failed: no water in the selection")
    line = run_scenario(t, "canal-water-river.txt", "TEST river", 300)
    if not line:
        raise Fail("the script did not finish")
    wet = wet_in(t, [(100, -61, 99, 120, -61, 103)])
    if wet:
        raise Fail("%d wet cells left (%d before)" % (wet, before))
    if "left=0" not in line:
        raise Fail("fill_water did not return 0: %s" % line)
    return "%d wet cells of the river dried with dirt" % before


def flood_world(t, name):
    """A trench 9 long, 3 wide, 2 deep (x 100..108, z 100..102, y -62..-61) open at both ends: air continues west and east."""
    new_world(t, name)
    fill(t, (100, -62, 100, 108, -61, 102), "air")
    fill(t, (90, -61, 100, 99, -61, 102), "air")
    fill(t, (109, -61, 100, 118, -61, 102), "air")


def flood_check(t, pond=None):
    """All 54 trench cells are water sources, no water anywhere else around (but a pond we made), the open ends are closed."""
    trench = (100, -62, 100, 108, -61, 102)
    n = layers(t, [trench])["water"]
    around = sum(layers(t, [(80, -66, 90, 130, -54, 110)])["water"])
    extra = sum(layers(t, [pond])["water"]) if pond else 0
    if around != sum(n) + extra:
        raise Fail("water outside the trench (and the pond): %d blocks" % (around - sum(n) - extra))
    if sum(n) != 54:
        raise Fail("%d water blocks in the trench, expected 54 (%s)" % (sum(n), n))
    not_source = []
    for x in range(100, 109):
        for y in (-62, -61):
            for z in range(100, 103):
                st = t.b("blockstate %d %d %d" % (x, y, z))["state"]
                if "level=0" not in st:
                    not_source.append(((x, y, z), st))
    if not_source:
        raise Fail("%d trench cells are not sources, e.g. %s" % (len(not_source), not_source[:3]))
    ends = [t.block(p).split("|")[0] for p in ((99, -61, 101), (99, -62, 101), (109, -61, 101), (109, -62, 101))]
    if "minecraft:air" in ends:
        raise Fail("an open end is not sealed: the cells beyond it are %s" % ends)
    return ends


def test_canal_flood_bucket(t):
    """flood() with ONE full water bucket and no water within reach. One bucket can make one source and nothing can
    refill it (a refill needs a second source), so the honest result is a clear stop after that single source. The
    50+ other cells cannot be filled this way: this test pins that limit down, it is not a fill that works."""
    flood_world(t, "canal-flood-bucket")
    t.cmd("/give @s water_bucket 1", "/give @s dirt 64")
    time.sleep(1)
    t.run("canal-flood.txt")
    t.wait_chat("TEST flood start", 30)
    t.b.wait(lambda: not t.b("script")["running"], 240, what="flood() to stop")
    msg = [l for l in t.lines() if "No water source" in l]
    if not msg:
        raise Fail("no clear message about the missing water source: %s" % t.lines()[-6:])
    sources = sum(1 for x in range(100, 109) for y in (-62, -61) for z in range(100, 103)
                  if "level=0" in t.b("blockstate %d %d %d" % (x, y, z))["state"])
    if sources != 1:
        raise Fail("%d water sources in the trench, expected exactly the one the bucket makes" % sources)
    return "stopped after the one source the bucket allows: %s" % msg[-1].split("] ", 1)[-1][:80]


def test_canal_flood_pond(t):
    """flood() with only empty buckets and a pond 8 blocks away: it fetches the water itself."""
    flood_world(t, "canal-flood-pond")
    fill(t, (112, -62, 96, 115, -61, 98), "water")
    t.cmd("/give @s bucket 16", "/give @s dirt 64")
    time.sleep(1)
    line = run_scenario(t, "canal-flood.txt", "TEST flood done", 600)
    if not line:
        raise Fail("the script did not finish")
    ends = flood_check(t, pond=(112, -62, 96, 115, -61, 98))
    return "54 sources from 16 buckets filled at the pond; ends sealed (%s)" % ", ".join(ends)


def test_canal_flood_none(t):
    """flood() with only empty buckets and no water within reach: it stops with a message, it does not hang."""
    flood_world(t, "canal-flood-none")
    t.cmd("/give @s bucket 16", "/give @s dirt 64")
    time.sleep(1)
    t.run("canal-flood.txt")
    t.wait_chat("TEST flood start", 30)
    t.b.wait(lambda: not t.b("script")["running"], 120, what="flood() to give up")
    if any("TEST flood done" in l for l in t.lines()):
        raise Fail("flood() reported done without any water")
    msg = [l for l in t.lines() if "water" in l.lower()]
    if not msg:
        raise Fail("no message about the missing water in the chat: %s" % t.lines()[-6:])
    wet = sum(layers(t, [(100, -62, 100, 108, -61, 102)])["water"])
    if wet:
        raise Fail("water was placed though there is none: %d" % wet)
    return "stopped with a message: %s" % msg[-1].split("] ", 1)[-1][:90]


TWO_A = ((104, -60, 104), (5, 3, 5))
TWO_B = ((110, -60, 104), (3, 2, 3))
TWO_B_MODEL = {(x, 0, z): "minecraft:spruce_planks" for x in range(3) for z in range(3)}
TWO_B_MODEL[(1, 1, 1)] = "minecraft:emerald_block"        # no item for it anywhere


def test_canal_schem_two(t):
    """Two enabled placements, skip mode: items that are only in the main inventory are used, the item with none is left out."""
    new_world(t, "canal-schem-two", [("house", TWO_A[1], TWO_A[0], schem_model()),
                                    ("shelf", TWO_B[1], TWO_B[0], TWO_B_MODEL)])
    t.cmd("/give @s cobblestone 576")          # nine stacks: the whole hotbar
    t.cmd("/give @s spruce_planks 9", "/give @s stone 25", "/give @s oak_stairs 4", "/give @s oak_slab 4",
          "/give @s torch 1", "/give @s redstone 1", "/give @s oak_log 1")
    time.sleep(1)
    if t.count("spruce_planks") != 9:
        raise Fail("setup: spruce_planks in the inventory %d" % t.count("spruce_planks"))
    line = run_scenario(t, "canal-schem-two.txt", "TEST schem2", 420)
    if not line:
        raise Fail("the script did not finish")
    bad_a = model_diff(t, TWO_A[0], TWO_A[1], schem_model())
    bad_b = model_diff(t, TWO_B[0], TWO_B[1], TWO_B_MODEL, ignore=[(111, -59, 105)])
    if bad_a:
        raise Fail("house: %d blocks differ, e.g. %s (%s)" % (len(bad_a), bad_a[:4], line))
    if bad_b:
        raise Fail("shelf: %d blocks differ, e.g. %s (%s)" % (len(bad_b), bad_b[:4], line))
    if t.block((111, -59, 105)).split("|")[0] != "minecraft:air":
        raise Fail("the emerald block (no item) was placed: %s" % t.block((111, -59, 105)))
    if "left=1" not in line:
        raise Fail("expected 1 block left out (the emerald block): %s" % line)
    report = [l.split("] ", 1)[-1] for l in t.lines() if "emerald" in l]
    if not report:
        raise Fail("the emerald block is not reported in the chat")
    return "both placements built; 1 block left out and reported: %s" % report[-1][:80]


def test_canal_schem_needed(t):
    """schem_needed gives the right map; take_upto takes from a barrel, a shulker box and a chest; after the build the map is empty."""
    new_world(t, "canal-schem-needed", [("house", (5, 3, 5), (104, -60, 104), schem_model())])
    chest_with(t, (98, -60, 98), [("oak_stairs", 8), ("oak_slab", 8), ("oak_log", 2), ("torch", 4), ("redstone", 2)])
    chest_with(t, (97, -60, 98), [("cobblestone", 30)], kind="black_shulker_box")
    chest_with(t, (96, -60, 98), [("stone", 30)], kind="barrel")
    time.sleep(1)
    line = run_scenario(t, "canal-schem-needed.txt", "TEST after build", 240)
    if not line:
        raise Fail("the script did not finish")
    checks = {"stone": 25, "cobblestone": 23, "oak_stairs": 4, "oak_slab": 4, "oak_log": 1, "torch": 1, "redstone": 1}
    taken = next((l for l in t.lines() if "TEST taken" in l), "")   # counted in the script, before the build used them
    keys = {"stone": "stone", "cobblestone": "cobble", "oak_stairs": "stairs", "oak_slab": "slab", "oak_log": "log",
            "torch": "torch", "redstone": "redstone"}
    got = {k: int(re.search(r"%s=(\d+)" % keys[k], taken).group(1)) if re.search(r"%s=(\d+)" % keys[k], taken) else -1
           for k in checks}
    wrong = {k: (got[k], v) for k, v in checks.items() if got[k] != v}
    if wrong:
        raise Fail("taken (have, expected): %s" % wrong)
    need = next((l for l in t.lines() if "TEST needed" in l), "")
    if "ok=true n=7" not in need:
        raise Fail("schem_needed map wrong: %s" % need)
    after = next((l for l in t.lines() if "TEST after build" in l), "")
    if "after build=0" not in after:
        raise Fail("schem_needed is not empty after the build: %s" % after)
    return "map has the 7 items; take_upto from barrel, shulker box and chest; empty after the build"


def test_canal_survival_dump(t):
    """A full inventory triggers dump(storage) and the task goes on; keep("dirt", 256) stays with us; food stays."""
    new_world(t, "canal-survival-dump")
    chest_with(t, (96, -60, 96), [])
    fill(t, (102, -61, 100, 104, -61, 102), "dirt")
    # kit: pickaxe, shovel, axe (3 slots) and 32 bread; here 288 more bread (320 = 5 slots), 300 dirt (5), 1472 cobblestone (23)
    t.cmd("/give @s cobblestone 1472", "/give @s dirt 300", "/give @s bread 288")
    time.sleep(1)
    if t.count("bread") != 320:
        raise Fail("setup: bread %d" % t.count("bread"))
    t.run("canal-survival-dump.txt")
    t.wait_chat("TEST cleared", 300)
    t.script_done(60)
    line = next((l for l in t.lines() if "TEST cleared" in l), "")
    if t.count("cobblestone") != 0:
        raise Fail("cobblestone still carried: %d (%s)" % (t.count("cobblestone"), line))
    if t.count("diamond_pickaxe") != 1 or t.count("diamond_shovel") != 1:
        raise Fail("the tools were dumped: pickaxe %d, shovel %d" % (t.count("diamond_pickaxe"), t.count("diamond_shovel")))
    if t.count("bread") != 320:
        raise Fail("food was dumped: bread %d" % t.count("bread"))
    if "left=0" not in line:
        raise Fail("clear_area did not finish: %s" % line)
    dirt = t.count("dirt")
    if dirt < 256:
        raise Fail("dirt %d: the 256 that dump() must keep were not all kept" % dirt)
    dumped = [l for l in t.lines() if "dump" in l.lower()]
    return "full -> dump(storage); %d dirt with us (256 kept, pickup of the dug blocks: %d), food kept, task went on" % (dirt, dirt - 256)


def test_canal_survival_restock(t):
    """want("bucket", 4) and want("diamond_pickaxe", 1): restock() fetches them from the supply chest."""
    new_world(t, "canal-survival-restock", kit=False)     # no pickaxe of our own: the chest's one must come
    chest_with(t, (96, -60, 97), [("bucket", 8), ("diamond_pickaxe", 1)])
    time.sleep(1)
    line = run_scenario(t, "canal-survival-restock.txt", "TEST restocked", 240)
    if not line:
        raise Fail("the script did not finish")
    b, p = t.count("bucket"), t.count("diamond_pickaxe")
    if b < 4 or p < 1:
        raise Fail("buckets %d (want 4), pickaxes %d (want 1)" % (b, p))
    return "restocked: %d buckets, %d diamond pickaxe" % (b, p)


TOOLS_BOX = ((110, -61, 100), (112, -60, 102))     # stone at y -61, dirt at y -60 (run_tests.py fills them)


def test_canal_tools_schem(t):
    """The schematic digger breaks stone with the pickaxe and dirt with the shovel (the held item at each break)."""
    origin = (110, -61, 100)
    new_world(t, "canal-tools-schem", [("empty box", (3, 2, 3), origin, {})])
    fill(t, (110, -61, 100, 112, -61, 102), "stone")
    fill(t, (110, -60, 100, 112, -60, 102), "dirt")
    time.sleep(1)
    cells = [(x, y, z) for x in range(110, 113) for y in (-61, -60) for z in range(100, 103)]
    kind = {p: ("stone" if p[1] == -61 else "dirt") for p in cells}
    held_at_break = {"stone": [], "dirt": []}
    t.run("canal-tools-schem.txt")
    end, gone = time.time() + 300, set()
    while True:
        for p in cells:
            if p in gone:
                continue
            if "air" in t.block(p):
                gone.add(p)
                held_at_break[kind[p]].append(t.b("held")["item"])
        if len(gone) == len(cells) or not t.b("script")["running"] or time.time() > end:
            break
        time.sleep(0.1)
    t.script_done(60)
    line = next((l for l in t.lines() if "TEST tools" in l), None)
    if len(gone) != len(cells):
        raise Fail("%d of %d blocks not dug (%s)" % (len(cells) - len(gone), len(cells), line))
    bad = [(k, h) for k in held_at_break for h in held_at_break[k]
           if ("pickaxe" if k == "stone" else "shovel") not in h]
    if bad:
        raise Fail("broke the wrong tool: %s" % bad[:5])
    return "stone broken with %s, dirt with %s" % (sorted(set(held_at_break["stone"])), sorted(set(held_at_break["dirt"])))


def flood_timed(t, name, items, timeout=600):
    """flood() on the open trench with these items; returns the seconds it took to finish."""
    flood_world(t, name)
    t.cmd(*["/give @s %s" % it for it in items])
    time.sleep(1)
    t0 = time.time()
    line = run_scenario(t, "canal-flood.txt", "TEST flood done", timeout)
    return time.time() - t0, line


def test_canal_flood_two_water(t):
    """(a) 1 empty bucket + 2 water buckets, no water near: two sources next to each other make a third one between
    them (it regenerates), so the refills come from the trench itself. Must complete: 54 sources, ends sealed."""
    secs, line = flood_timed(t, "canal-flood-two-water", ["bucket 1", "water_bucket 2", "dirt 64"])
    if not line:
        raise Fail("flood() did not finish in 600 s")
    ends = flood_check(t)
    return "54 sources in %.0f s; ends sealed (%s)" % (secs, ", ".join(ends))


def test_canal_flood_stack(t):
    """(b) 16 empty buckets in one stack + 2 water buckets, no water near: the same fill, the buckets refilled in batches.
    Must complete, and clearly faster than (a) (the time is reported)."""
    secs, line = flood_timed(t, "canal-flood-stack", ["bucket 16", "water_bucket 2", "dirt 64"])
    if not line:
        raise Fail("flood() did not finish in 600 s")
    ends = flood_check(t)
    return "54 sources in %.0f s with 16 buckets in one stack; ends sealed (%s)" % (secs, ", ".join(ends))


def test_canal_flood_wide(t):
    """flood() on a trench 11 wide like the real canal (x 100..108, z 100..110, y -62..-61), closed, 16 empty buckets + 2 water buckets.
    The middle of the top layer has no block to click against and is out of reach from the edges: flood() must place water on temporary blocks and take
    them away again. Must complete: all 198 cells sources, no water outside, no block left in the trench."""
    new_world(t, "canal-flood-wide")
    fill(t, (100, -62, 100, 108, -61, 110), "air")
    t.cmd("/give @s bucket 16", "/give @s water_bucket 2", "/give @s dirt 64")
    time.sleep(1)
    t0 = time.time()
    line = run_scenario(t, "canal-flood-wide.txt", "TEST flood done", 900)
    secs = time.time() - t0
    trench = (100, -62, 100, 108, -61, 110)
    n = layers(t, [trench])["water"]
    around = sum(layers(t, [(80, -66, 85, 130, -54, 120)])["water"])
    if around != sum(n):
        wet = [(x, y, z, t.block((x, y, z)).split("|")[0]) for x in range(97, 112) for y in range(-63, -57) for z in range(97, 114)
               if not (100 <= x <= 108 and -62 <= y <= -61 and 100 <= z <= 110) and "water" in t.block((x, y, z))]
        raise Fail("water outside the trench: %d blocks, at %s" % (around - sum(n), wet[:6]))
    not_source, solid = [], []
    for x in range(100, 109):
        for y in (-62, -61):
            for z in range(100, 111):
                st = t.b("blockstate %d %d %d" % (x, y, z))["state"]
                if "water" not in st:
                    solid.append(((x, y, z), st.split("|")[0]))
                elif "level=0" not in st:
                    not_source.append((x, y, z))
    if solid:
        raise Fail("%d trench cells hold a block (temporary blocks left?), e.g. %s" % (len(solid), solid[:3]))
    if not_source:
        raise Fail("%d trench cells are not sources, e.g. %s (%s)" % (len(not_source), not_source[:3], line))
    above = sum(layers(t, [(95, -60, 95, 113, -40, 115)])["solid"])
    if above:
        raise Fail("%d blocks above the trench: Baritone pillared or bridged while filling" % above)
    stilts = [l for l in t.lines() if "temporary blocks" in l]
    return "198 sources in %.0f s, nothing outside%s" % (secs, ", temporary blocks used" if stilts else "")


SMOKE_WALL = {(x, 0, 0): "minecraft:stone_bricks" for x in range(12)}   # a row of 12 along the trench side


def test_canal_smoke(t):
    """The whole canal script (scripts/canal.txt from "survival" on) on a small copy of the canal:
    a dirt plateau; trench_a (12 x 3 x 6) dug in 2-layer passes; a water pocket under it plugged with dirt from the
    storage chest; trench_canal (12 x 2 x 2) dug below it; two placements (a row of stone bricks along each side of
    trench_a) built with bricks from a shulker box; trench_canal filled with water from 16 buckets + 2 water buckets."""
    new_world(t, "canal-smoke", [("wall north", (12, 1, 1), (100, -60, 100), SMOKE_WALL),
                                 ("wall south", (12, 1, 1), (100, -60, 105), SMOKE_WALL)], kit=False)
    fill(t, (88, -61, 88, 132, -58, 120), "dirt")                               # the plateau, 4 high
    fill(t, (104, -61, 102, 106, -61, 103), "water")                           # a water pocket under trench_a
    t.cmd("/tp @s 95 -57 95 0 0")
    eff = '{"minecraft:efficiency":5}'
    chest_with(t, (92, -57, 92), [("dirt", 64)] * 4)                            # storage
    chest_with(t, (94, -57, 92), [("diamond_pickaxe[enchantments=%s]" % eff, 1), ("diamond_shovel[enchantments=%s]" % eff, 1),
                                  ("diamond_pickaxe[enchantments=%s]" % eff, 1), ("diamond_shovel[enchantments=%s]" % eff, 1),
                                  ("bucket", 16), ("water_bucket", 1), ("water_bucket", 1), ("bread", 32)])   # tools
    chest_with(t, (96, -57, 92), [])                                           # tool return
    t.cmd("/setblock 98 -57 92 minecraft:red_bed[facing=north,part=foot]", "/setblock 98 -57 91 minecraft:red_bed[facing=north,part=head]")
    chest_with(t, (100, -57, 92), [("stone_bricks", 64)], kind="shulker_box")   # build supplies
    time.sleep(1)
    t0 = time.time()
    line = run_scenario(t, "canal-smoke.txt", "TEST canal done", 1200)
    secs = time.time() - t0
    problems = []
    # 1. trench_a dug (but the two brick rows), 3. trench_canal: water sources only
    for x in range(100, 112):
        for y in (-60, -59, -58):
            for z in range(100, 106):
                b = t.block((x, y, z)).split("|")[0]
                want = "minecraft:stone_bricks" if (y == -60 and z in (100, 105)) else "minecraft:air"
                if b != want:
                    problems.append(("trench_a", (x, y, z), b))
        for y in (-62, -61):
            for z in (102, 103):
                st = t.b("blockstate %d %d %d" % (x, y, z))["state"]
                if "water" not in st or "level=0" not in st:
                    problems.append(("canal", (x, y, z), st.split("|")[0]))
    # the rest of the pocket (outside trench_canal) is plugged; no water anywhere but in trench_canal
    canal = sum(layers(t, [(100, -62, 102, 111, -61, 103)])["water"])
    around = sum(layers(t, [(80, -66, 80, 140, -54, 130)])["water"])
    if around != canal:
        wet = [(x, y, z) for x in range(96, 117) for y in range(-63, -56) for z in range(96, 111)
               if not (100 <= x <= 111 and -62 <= y <= -61 and 102 <= z <= 103)
               and "water" in t.block((x, y, z))]
        problems.append(("water outside trench_canal", around - canal, wet[:6]))
    if problems:
        raise Fail("%d problems, e.g. %s (%s)" % (len(problems), problems[:6], line))
    steps = [l.split("] ", 1)[-1] for l in t.lines() if "/5:" in l]
    return "the whole canal script in %.0f s: %d steps run, trench dug, bricks built, 48 water sources" % (secs, len(steps))


WATER_SCHEM = ((100, -62, 100), (5, 2, 3))
WATER_MODEL = {(x, 0, z): "minecraft:stone_bricks" for x in range(5) for z in range(3)}
WATER_MODEL.update({(x, 1, 0): "minecraft:smooth_stone" for x in range(5)})


def test_canal_schem_water(t):
    """The live canal run: water had leaked into the trench, Baritone stopped at the bottom layer ("Unreplaceable liquids")
    and nothing above it was built. A 5x3 floor with water in it and a row on top: everything must be built,
    the water cells by our own placer."""
    new_world(t, "canal-schem-water", [("floor", WATER_SCHEM[1], WATER_SCHEM[0], WATER_MODEL)])
    fill(t, (100, -62, 100, 104, -61, 102), "air")
    t.cmd("/setblock 101 -62 101 minecraft:water", "/setblock 103 -62 101 minecraft:water", "/setblock 100 -62 100 minecraft:water")
    t.cmd("/give @s stone_bricks 15", "/give @s smooth_stone 5", "/give @s dirt 32")
    time.sleep(2)
    if "water" not in t.block((101, -62, 101)):
        raise Fail("setup: no water at 101 -62 101")
    line = run_scenario(t, "canal-schem-water.txt", "TEST schemwater", 300)
    bad = model_diff(t, WATER_SCHEM[0], WATER_SCHEM[1], WATER_MODEL)
    if bad:
        raise Fail("%d blocks differ, e.g. %s (%s)" % (len(bad), bad[:4], line))
    if "left=0" not in line:
        raise Fail("expected nothing left: %s" % line)
    return "floor built over the water and the row on top of it (%s)" % line.split("] ", 1)[-1]


MOVE_MODEL = {(x, 0, z): "minecraft:spruce_planks" for x in range(3) for z in range(3)}


def test_canal_move(t):
    """/queue move dx dy dz: every saved area and every Litematica placement moves by the offset (for a second test run
    elsewhere without setting everything up again); the moved placement is built at its new place."""
    new_world(t, "canal-move", [("pad", (3, 1, 3), (100, -60, 100), MOVE_MODEL)])
    run_scenario(t, "canal-move-sel.txt", "TEST selected", 60)
    t.cmd("/queue area save mvtest")
    t.cmd("/give @s spruce_planks 9")
    time.sleep(1)
    t.start_log()
    t.cmd("/queue move 10 0 5")
    time.sleep(2)
    said = [l.split("] ", 1)[-1] for l in t.lines() if "Moved" in l or "did not move" in l or "Could not" in l]
    if not any("area 'mvtest'" in l and "from 110 -60 105 to 112 -59 107" in l for l in said):
        raise Fail("the area did not move: %s" % said)
    if not any("placement 1" in l and "110, -60, 105" in l for l in said):
        raise Fail("the placement did not move: %s" % said)
    line = run_scenario(t, "canal-move-build.txt", "TEST moved", 240)
    bad = model_diff(t, (110, -60, 105), (3, 1, 3), MOVE_MODEL, region=((99, -60, 99), (114, -60, 109)))
    if bad:
        raise Fail("%d blocks differ after the move, e.g. %s (%s)" % (len(bad), bad[:4], line))
    sel = [l for l in t.lines() if "Area 'mvtest' selected" in l]
    if not sel or "from 110 -60 105 to 112 -59 107" not in sel[-1]:
        raise Fail("the saved area was not moved: %s" % sel[-1:])
    return "area and placement moved by 10 0 5, built at the new place"


def test_canal_flood_resume(t):
    """The live run's loop: restock kept failing (supply chest it could not open), "on restock_needed" fired every 30 s
    and flood() started over each time, walking to the same unreachable cell: 9 hours, nothing filled. Here the supply
    chest has a block on top and 40 buckets are wanted: flood() must carry on after interruptions and fill all 198."""
    new_world(t, "canal-flood-resume")
    fill(t, (100, -62, 100, 108, -61, 110), "air")
    t.cmd("/setblock 96 -60 96 minecraft:chest", "/setblock 96 -59 96 minecraft:stone")
    t.cmd("/give @s bucket 16", "/give @s water_bucket 2", "/give @s dirt 64")
    time.sleep(1)
    t0 = time.time()
    line = run_scenario(t, "canal-flood-resume.txt", "TEST flood done", 900)
    secs = time.time() - t0
    n = layers(t, [(100, -62, 100, 108, -61, 110)])["water"]
    if sum(n) != 198:
        raise Fail("%d water blocks in the trench, expected 198 (%s)" % (sum(n), line))
    starts = [l for l in t.lines() if "Filling 198 cells" in l]
    carried = [l for l in t.lines() if "Carrying on with the water fill" in l]
    fired = [l for l in t.lines() if "restock_needed" in l and "fired" in l]
    if len(starts) > 1:
        raise Fail("flood() started over %d times instead of carrying on" % len(starts))
    if len(fired) > 4:
        raise Fail("restock_needed fired %d times in %.0f s" % (len(fired), secs))
    return "198 sources in %.0f s; restock_needed fired %d times, carried on %d times" % (secs, len(fired), len(carried))


CANAL_TESTS = [("canal_layers", test_canal_layers), ("canal_pillar_scaffold", test_canal_pillar_scaffold),
               ("canal_scaffold_schem", test_canal_scaffold_schem), ("canal_water_pond", test_canal_water_pond),
               ("canal_water_river", test_canal_water_river), ("canal_flood_bucket", test_canal_flood_bucket),
               ("canal_flood_two_water", test_canal_flood_two_water), ("canal_flood_stack", test_canal_flood_stack),
               ("canal_flood_pond", test_canal_flood_pond), ("canal_flood_none", test_canal_flood_none), ("canal_flood_wide", test_canal_flood_wide), ("canal_smoke", test_canal_smoke),
               ("canal_schem_two", test_canal_schem_two), ("canal_schem_needed", test_canal_schem_needed),
               ("canal_survival_dump", test_canal_survival_dump), ("canal_survival_restock", test_canal_survival_restock),
               ("canal_tools_schem", test_canal_tools_schem), ("canal_schem_water", test_canal_schem_water), ("canal_move", test_canal_move), ("canal_flood_resume", test_canal_flood_resume)]


def chest_opens(t, n):
    """How many chests the script opened between its 'TEST start n' and 'TEST end n' lines, and the end line."""
    lines = t.lines()
    i = next((k for k, l in enumerate(lines) if "TEST start %d" % n in l), None)
    j = next((k for k, l in enumerate(lines) if "TEST end %d" % n in l), None)
    if i is None or j is None:
        raise Fail("step %d did not run (chat: %s)" % (n, lines[-3:]))
    return sum(1 for l in lines[i:j] if "] Chest: " in l or l.strip().startswith("Chest: ")), lines[j], lines[i:j]


def test_chest_index(t):
    """The chest index: a fetch from a list of chests opens only the chests known to hold the item (nearest first),
    one that is nowhere opens nothing while the index is fresh, stale entries are opened again, and the index
    survives leaving and rejoining the world."""
    name = "chest-index-%d" % RUN_ID
    new_world(t, "chest-index", kit=False)
    chest_with(t, (96, -60, 96), [("stone", 64)])
    chest_with(t, (96, -60, 98), [("gravel", 64)])
    chest_with(t, (96, -60, 100), [("sand", 64)])
    time.sleep(1)
    line = run_scenario(t, "chest-index.txt", "TEST done", 240)
    expect = {1: (2, "got=10 have=10"), 2: (1, "got=5 have=5"), 3: (1, "got=10 have=20"), 4: (0, "got=0 have=0"),
              5: (3, "got=0"), 6: (1, "put=20 gravel_in_b=64 young=true")}
    problems = []
    for n, (opens, text) in expect.items():
        k, end, steps = chest_opens(t, n)
        if k != opens or text not in end:
            problems.append("step %d: %d chests opened (expected %d), %s" % (n, k, opens, end.split("] ")[-1]))
    if problems:
        raise Fail("; ".join(problems))
    # leave and come back: the index is read from its file, the chests were not opened again
    t.b("leave")
    t.b.wait(lambda: t.b("state")["screen"].endswith("TitleScreen"), 120, step=1, what="the title screen")
    t.b("world " + name)
    t.b.wait(lambda: t.b("state")["inWorld"], 300, step=1, what="the world to load")
    t.b.wait(lambda: t.b("state")["screen"] == "", 60, step=1, what="the loading screen to close")
    wait_server(t)
    time.sleep(3)
    t.start_log()
    t.cmd("/tp @s 100 -60 100 0 0")
    line = run_scenario(t, "chest-index-2.txt", "TEST done", 120)
    k7, end7, _ = chest_opens(t, 7)
    k8, end8, _ = chest_opens(t, 8)
    stone = next((l for l in t.lines() if "TEST known" in l), "")
    if "stone_in_a=59" not in stone:
        raise Fail("after the rejoin the index lost the chest contents: %s" % (stone or t.lines()[-3:]))
    if k7 != 0 or "got=0" not in end7:
        raise Fail("after the rejoin an item that is nowhere opened %d chests: %s" % (k7, end7))
    if k8 != 1 or "got=10" not in end8:
        raise Fail("after the rejoin sand opened %d chests (expected 1, the one that holds it): %s" % (k8, end8))
    return "cold: 2 opens, then 1, 1, nowhere 0, stale 3, put 1; after the rejoin: nowhere 0, sand 1"


def test_hotbar_tools(t):
    """Wanted tools that only sit in the main inventory are moved into the hotbar (not over food or pinned slots)."""
    reset_area(t)
    t.cmd(*["/item replace entity @s hotbar.%d with cobblestone 64" % i for i in range(8)],
          "/item replace entity @s hotbar.8 with bread 8",
          "/give @s diamond_pickaxe 1", "/give @s diamond_shovel 1")
    t.run("hotbar-tools.txt")
    t.wait_chat("TEST hotbar done", 30)
    hb = t.state()["hotbar"]
    missing = [x for x in ("diamond_pickaxe", "diamond_shovel") if x not in hb]
    if missing:
        raise Fail("not in the hotbar: %s (hotbar %s)" % (missing, hb))
    if hb[8] != "bread":
        raise Fail("the food slot was taken: hotbar %s" % hb)
    return "pickaxe and shovel moved into the hotbar, bread stayed in slot 9 (%s)" % hb


def test_air(t):
    """Shut in under water (a stone lid one block over the water): the air guard breaks out before drowning."""
    reset_area(t)
    t.cmd("/fill 102 -62 98 106 -54 102 stone", "/fill 103 -62 99 105 -55 101 water",
          "/fill 103 -54 99 105 -54 101 stone",   # the lid: water right up to it, no air pocket
          "/give @s diamond_pickaxe 1", "/tp @s 104 -60 100")
    t.run("air.txt")
    t.wait_chat("TEST air armed", 10)
    t.wait_chat("Running out of air", 40)
    t.b.wait(lambda: (lambda s: s if not s["underWater"] else None)(t.state()), 40, what="the head above water")
    st = t.state()
    t.b("stopscript")
    if any("drowned" in l or " died" in l for l in t.lines()) or st["health"] <= 0:
        raise Fail("drowned (%s)" % [l for l in t.lines() if "air" in l.lower() or "drown" in l][-4:])
    return "came up for air (health %.0f, air %d)" % (st["health"], st["air"])


def test_clear_wet(t):
    """Blocks next to water are left alone, named in chat and not counted in what clear_area() returns."""
    reset_area(t)
    t.cmd("/fill 102 -60 92 108 -60 96 dirt", "/fill 109 -60 91 110 -59 97 stone", "/fill 109 -60 93 109 -59 95 water",
          "/give @s diamond_shovel 1")
    t.run("clear-wet.txt")
    line = t.wait_chat("TEST cleared", 120)
    done = next((l for l in t.lines() if "clear_area done" in l), "")
    if "left=0" not in line:
        raise Fail("clear_area counted the wet blocks: " + line + " / " + done)
    if "next to water" not in done:
        raise Fail("no 'next to water' in the report: " + done)
    wet = [z for z in (93, 94, 95) if "dirt" in t.block((108, -60, z))]
    if len(wet) != 3:
        raise Fail("the blocks next to the water were broken: %s" % done)
    return done.split("] ", 1)[-1][:100]



def plant(t, feature, p):
    """/place feature at p; returns False (and says why in the log) when the game could not place it."""
    mark = t.b("chatlog 0")["next"]
    t.cmd("/place feature minecraft:%s %d %d %d" % (feature, p[0], p[1], p[2]))
    time.sleep(0.5)
    bad = [l for l in t.b("chatlog %d" % mark)["lines"] if "ailed" in l or "nknown" in l or "rror" in l]
    if bad:
        log("plant %s at %s: %s" % (feature, p, bad[0][:120]))
    return not bad


def kv(line, key):
    m = re.search(r"\b%s=(-?[0-9.]+|null)" % key, line)
    if not m:
        raise Fail("no %s= in '%s'" % (key, line))
    return None if m.group(1) == "null" else float(m.group(1))


def dirt_at(t, a, b):
    """Where dirt stands in the box (slow: one bridge call per cell; only used to explain a failure)."""
    return [(x, y, z) for y in range(a[1], b[1] + 1) for x in range(a[0], b[0] + 1) for z in range(a[2], b[2] + 1)
            if "minecraft:dirt" == t.block((x, y, z)).split("|")[0]][:10]


def test_trees_small(t):
    """clear_trees() on an oak and a birch, no chest: no log left, no scaffolding left, the logs are carried and
    reported, the 64 dirt for scaffolding are all back."""
    new_world(t, "trees-small")
    t.cmd("/tp @s 100 -60 100 0 0")
    time.sleep(2)
    planted = [f for f, p in (("oak", (96, -60, 96)), ("birch", (104, -60, 104))) if plant(t, f, p)]
    if not planted:
        raise Fail("no tree could be planted")
    t.cmd("/give @s dirt 64")
    time.sleep(1)
    line = run_scenario(t, "trees-small.txt", "TEST trees done", 400)
    start = next((l for l in t.lines() if "TEST trees start" in l), "")
    if kv(line, "left") != 0:
        raise Fail("logs left standing: %s (%s)" % (line, start))
    if kv(line, "scaffold") != 0:
        raise Fail("scaffolding left in the area: %s at %s" % (line, dirt_at(t, (92, -60, 92), (110, -52, 110))))
    oak = kv(line, "oak") or 0
    if "oak" in planted and (oak <= 0 or kv(line, "carried") < oak):
        raise Fail("the oak logs were not reported / carried: %s" % line)
    dirt = t.count("dirt")
    if dirt < 64:
        raise Fail("only %d of the 64 scaffolding dirt came back" % dirt)
    return "%s cleared (%s); %s; %d dirt back" % ("+".join(planted), start.split("TEST trees ")[-1], line.split("done ")[-1], dirt)


FOREST = [("oak", (92, -60, 92)), ("birch", (102, -60, 92)), ("fancy_oak", (114, -60, 94)), ("mega_spruce", (92, -60, 106)),
          ("dark_oak", (106, -60, 108)), ("jungle_tree", (118, -60, 108)), ("mangrove", (96, -60, 120)),
          ("huge_red_mushroom", (110, -60, 122)), ("huge_brown_mushroom", (120, -60, 122))]


def test_trees_forest(t):
    """clear_trees(chest) on a mixed forest (2x2 mega spruce, dark oak, jungle with vines, mangrove with roots, huge
    mushrooms, a bee nest): nothing of the trees left, no scaffolding left, the logs end up in the chest, the report
    counts them."""
    new_world(t, "trees-forest")
    t.cmd("/tp @s 105 -60 105 0 0")
    time.sleep(3)
    planted = [f for f, p in FOREST if plant(t, f, p)]
    if len(planted) < 5:
        raise Fail("only %s could be planted" % planted)
    if "oak" in planted:
        t.cmd("/setblock 93 -58 92 minecraft:bee_nest")
    chest_with(t, (100, -60, 84), [])
    t.cmd("/give @s dirt 128", "/tp @s 100 -60 100 0 0")
    time.sleep(1)
    line = run_scenario(t, "trees-forest.txt", "TEST forest done", 1500)
    start = next((l for l in t.lines() if "TEST forest start" in l), "")
    chest = next((l for l in t.lines() if "TEST forest chest" in l), "")
    got = next((l for l in t.lines() if "TEST forest got" in l), "")
    if kv(line, "left") != 0:
        raise Fail("tree blocks left standing: %s (%s)" % (line, start))
    if kv(line, "scaffold") != 0:
        raise Fail("scaffolding left in the area: %s at %s" % (line, dirt_at(t, (86, -60, 88), (124, -55, 128))))
    if kv(line, "logs") < 50:
        raise Fail("too few logs reported: %s / %s" % (line, got))
    if kv(line, "carried_logs") > 0:
        raise Fail("logs still carried, not in the chest: %s" % line)
    if "log" not in chest:
        raise Fail("no logs in the chest: %s" % chest)
    if kv(line, "dirt_carried") < 100:
        raise Fail("scaffolding dirt went missing (or into the chest): %s / %s" % (line, chest))
    return "planted %s; %s; %s" % (",".join(planted), start.split("TEST forest ")[-1], line.split("done ")[-1])


TREE_TESTS = [("trees_small", test_trees_small), ("trees_forest", test_trees_forest)]


TESTS = [("smoke", test_smoke), ("imgui_spike", test_imgui_spike), ("viewport", test_viewport), ("docked_shell", test_docked_shell), ("panels", test_panels), ("fill_water", test_fill_water), ("eat", test_eat), ("defend", test_defend), ("clear_resume", test_clear_resume), ("dump", test_dump), ("chest_index", test_chest_index), ("hotbar_tools", test_hotbar_tools), ("air", test_air), ("clear_wet", test_clear_wet),
         ("schem_list", test_schem_list), ("schem_add", test_schem_add), ("schem_fix", test_schem_fix), ("schem_fix_wrong", test_schem_fix_wrong), ("schem_exact", test_schem_exact)]

TESTS += CANAL_TESTS
TESTS += TREE_TESTS


# ------------------------------------------------------------------ main

def enter_world(b):
    st = b.wait(lambda: (lambda s: s if s["inWorld"] or s["screen"].endswith("TitleScreen") else None)(b("state")),
                240, step=1, what="the title screen")
    if not st["inWorld"]:
        time.sleep(3)   # let the resource reload finish
        r = b("world " + mc.WORLD)
        log("world '%s' %s" % (mc.WORLD, "created (flat, creative, cheats)" if r.get("created") else "opened"))
        b.wait(lambda: b("state")["inWorld"], 300, step=1, what="the world to load")
    b.wait(lambda: b("state")["screen"] == "", 60, step=1, what="the loading screen to close")
    time.sleep(3)
    t = Ctx(b)
    # world rules for repeatable tests; the answers go to the log for checking
    t.start_log()
    t.cmd("/gamerule advance_time false", "/gamerule advance_weather false", "/gamerule spawn_mobs false",
          "/difficulty easy")
    for l in t.lines():
        log("  setup:", l)
    return t


TEST_TIMEOUT = 300          # seconds; a test that takes longer fails and its game is restarted
RAM_PER_CLIENT_GB = 3.5     # a game with its 3 GB heap (MANHATTAN_XMX) and its own memory
MAX_WORKERS = 8             # bridge ports 25700+N for worker N: 25701..25708 (the other harness uses 25599 and 25601..25603)
LONG_FIRST = ["trees_forest", "trees_small", "clear_resume", "schem_exact", "schem_fix_wrong", "schem_fix", "fill_water", "schem_add"]   # start the slow ones first


def free_ram_gb():
    try:
        import ctypes

        class MS(ctypes.Structure):
            _fields_ = [("l", ctypes.c_ulong), ("load", ctypes.c_ulong), ("total", ctypes.c_ulonglong),
                        ("avail", ctypes.c_ulonglong), ("a", ctypes.c_ulonglong), ("b", ctypes.c_ulonglong),
                        ("c", ctypes.c_ulonglong), ("d", ctypes.c_ulonglong), ("e", ctypes.c_ulonglong)]
        m = MS()
        m.l = ctypes.sizeof(MS)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
        return m.avail / 2 ** 30
    except Exception:
        return 6.0


def run_one(t, name, fn, timeout):
    """Runs one test in a thread with a hard time limit. Returns (ok, info, seconds, hung)."""
    t0 = time.time()
    t.start_log()
    box = {}

    def body():
        try:
            box["info"] = fn(t)
        except Exception as e:   # noqa: BLE001
            box["err"] = e
            box["tb"] = traceback.format_exc()

    th = threading.Thread(target=body, daemon=True)
    th.start()
    th.join(timeout)
    if th.is_alive():
        return False, "hard timeout after %d s: the test did not finish (the game is restarted)" % timeout, time.time() - t0, True
    if "err" in box:
        e = box["err"]
        detail = str(e) if isinstance(e, (Fail, BridgeError)) else box["tb"]
        try:
            t.b("stopscript")
            chat = t.lines()[-15:]
        except Exception:
            chat = []
        if chat:
            detail += "\n      chat:\n        " + "\n        ".join(chat)
        return False, detail, time.time() - t0, False
    return True, box.get("info", ""), time.time() - t0, False


def wait_server(t, timeout=60):
    """The integrated server answers block queries (it is not there for a moment after a world switch)."""
    def ok():
        try:
            t.b("layers 0 0 0 0 0 0")
            return True
        except BridgeError:
            return False
    t.b.wait(ok, timeout, step=0.5, what="the world's server")


def fresh_world(t, name):
    """Leaves the current world and opens (creating it flat, creative, cheats on) a world of its own: name."""
    if t.b("state")["inWorld"]:
        t.b("leave")
        t.b.wait(lambda: t.b("state")["screen"].endswith("TitleScreen"), 120, step=1, what="the title screen")
    r = t.b("world " + name)
    t.b.wait(lambda: t.b("state")["inWorld"], 300, step=1, what="world %s to load" % name)
    t.b.wait(lambda: t.b("state")["screen"] == "", 60, step=1, what="the loading screen to close")
    time.sleep(3)
    t.cmd("/gamerule advance_time false", "/gamerule advance_weather false", "/gamerule spawn_mobs false",
          "/difficulty easy", "/gamemode survival", "/time set noon", "/weather clear")
    return r


def start_game(b, attach):
    if attach:
        b.connect()
        return None
    mc.stop_testing_instance()
    mc.install()
    prepare_schem()
    proc = mc.launch()
    b.wait_ready(proc=proc)
    return proc


def worker(claim, keep, attach, timeout, tag=""):
    """Starts one game, takes tests from claim() until it returns None, returns [(name, ok, info, seconds)]."""
    b = mc.Bridge()
    start_game(b, attach)
    log(tag + "bridge connected")
    results = []
    try:
        t = enter_world(b)
        while True:
            item = claim()
            if item is None:
                break
            name, fn = item
            log(tag + "running", name, "...")
            ok, info, dt, hung = run_one(t, name, fn, timeout)
            results.append((name, ok, info, dt))
            if hung:   # a stuck game is closed through its own bridge, then a fresh one carries on
                try:
                    mc.stop_testing_instance(b)
                except Exception as e:   # noqa: BLE001
                    log(tag + "closing the stuck game:", e)
                b.close()
                b = mc.Bridge()
                start_game(b, False)
                t = enter_world(b)
    finally:
        if not keep and not attach:
            log(tag + "closing the game")
            mc.stop_testing_instance(b)
        b.close()
    return results


def summary(results, wall, workers):
    print()
    print("=" * 60)
    order = [n for n, _ in TESTS]
    for r in sorted(results, key=lambda r: order.index(r[0])):
        print("%s  %-11s %5.1fs  %s%s" % ("PASS" if r[1] else "FAIL", r[0], r[3], r[2], ("   [worker %s]" % r[4]) if len(r) > 4 else ""))
    failed = sum(1 for r in results if not r[1])
    print("=" * 60)
    print("%d passed, %d failed   in %.0f s on %d game%s   (game log: %s)" % (
        len(results) - failed, failed, wall, workers, "" if workers == 1 else "s", os.path.join(mc.PROFILE, "logs", "latest.log")))
    return 0 if failed == 0 and results else 1


def opt(argv, flag, default):
    if flag in argv:
        i = argv.index(flag)
        return argv[i + 1]
    return default


def main(argv):
    if "--help" in argv or "-h" in argv:
        print(__doc__)
        return 0
    keep = "--keep-open" in argv
    attach = "--attach" in argv
    timeout = int(opt(argv, "--test-timeout", TEST_TIMEOUT))
    values = {opt(argv, f, None) for f in ("--workers", "-j", "--worker", "--queue", "--test-timeout")}
    names = [a for a in argv if not a.startswith("-") and a not in values]
    tests = [(n, f) for n, f in TESTS if not names or n in names]
    if names and len(tests) != len(names):
        print("unknown test; known: " + ", ".join(n for n, _ in TESTS))
        return 2

    # internal: one parallel worker, tests come from the queue directory
    if "--worker" in argv:
        k, qdir = opt(argv, "--worker", "1"), opt(argv, "--queue", None)
        table = dict(TESTS)

        def claim():
            for n in open(os.path.join(qdir, "tests.txt")).read().split():
                try:
                    os.mkdir(os.path.join(qdir, "claimed-" + n))   # atomic: only one worker gets it
                except FileExistsError:
                    continue
                return n, table[n]
            return None
        res = worker(claim, False, False, timeout, tag="[w%s] " % k)
        with open(os.path.join(qdir, "results-%s.json" % k), "w", encoding="utf-8") as f:
            json.dump([list(r) + [k] for r in res], f)
        return 0

    # up to 8 games at once (MAX_WORKERS), as many as the free RAM allows at RAM_PER_CLIENT_GB each; -j N sets it
    default_workers = max(1, min(MAX_WORKERS, int(free_ram_gb() // RAM_PER_CLIENT_GB), len(tests)))
    workers = 1 if attach or keep else int(opt(argv, "--workers", opt(argv, "-j", default_workers)))
    workers = max(1, min(workers, len(tests)))
    t0 = time.time()
    if attach or keep:   # one game (the copy w1), kept open or attached to
        todo = list(tests)
        res = worker(lambda: todo.pop(0) if todo else None, keep, attach, timeout)
        return summary(res, time.time() - t0, 1)

    # parallel: every worker is its own game with its own game dir, world, bridge port and log under tmp/ingame/
    log("%d games in parallel (free RAM %.1f GB, about %.0f GB each)" % (workers, free_ram_gb(), RAM_PER_CLIENT_GB))
    root = os.path.join(mc.REPO, "tmp", "ingame")
    qdir = os.path.join(root, "queue")
    shutil.rmtree(qdir, ignore_errors=True)
    os.makedirs(qdir)
    order = sorted((n for n, _ in tests), key=lambda n: LONG_FIRST.index(n) if n in LONG_FIRST else 99)
    with open(os.path.join(qdir, "tests.txt"), "w") as f:
        f.write("\n".join(order))
    procs = []
    for k in range(1, workers + 1):
        home = os.path.join(root, "w%d" % k)
        os.makedirs(home, exist_ok=True)
        env = dict(os.environ, MANHATTAN_PROFILE=home, MANHATTAN_BRIDGE_PORT=str(mc.PORT_BASE + k), MANHATTAN_XMX="3G")
        out = open(os.path.join(root, "w%d.log" % k), "w", encoding="utf-8")
        procs.append((k, subprocess.Popen([sys.executable, "-u", os.path.abspath(__file__), "--worker", str(k), "--queue", qdir,
                                           "--test-timeout", str(timeout)], env=env, stdout=out, stderr=subprocess.STDOUT)))
        log("worker %d: game dir %s, port %d, log %s" % (k, home, mc.PORT_BASE + k, os.path.join(root, "w%d.log" % k)))
        time.sleep(5)    # stagger the starts: loading many clients at once is slower than one after the other
    for k, pr in procs:
        pr.wait()
    res = []
    for k, _ in procs:
        fn = os.path.join(qdir, "results-%d.json" % k)
        if os.path.exists(fn):
            res += [tuple(r) for r in json.load(open(fn, encoding="utf-8"))]
    done = {r[0] for r in res}
    for n in order:
        if n not in done:
            res.append((n, False, "never ran (a worker died; see tmp/ingame/w*.log)", 0.0, "?"))
    return summary(res, time.time() - t0, workers)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
