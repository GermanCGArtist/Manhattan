"""Records which window has the focus and where the cursor is, twice a second, to prove test games do not steal them.

  python tests/ingame/focuswatch.py <seconds> <outfile>
Prints a summary: how often the foreground window changed, whether a Minecraft window was ever in front, cursor moves.
"""
import ctypes
import sys
import time
from ctypes import wintypes

u = ctypes.windll.user32


def title(h):
    n = u.GetWindowTextLengthW(h)
    b = ctypes.create_unicode_buffer(n + 1)
    u.GetWindowTextW(h, b, n + 1)
    return b.value


def main(seconds, out):
    end = time.time() + seconds
    last, changes, mc_front, cursor, moves = None, 0, 0, None, 0
    log = open(out, "w", encoding="utf-8")
    while time.time() < end:
        h = u.GetForegroundWindow()
        t = title(h)
        pt = wintypes.POINT()
        u.GetCursorPos(ctypes.byref(pt))
        if t != last:
            changes += 1
            log.write("%.1f foreground: %r\n" % (time.time(), t))
            last = t
        if "Minecraft" in t and "Manhattan" not in t:
            pass
        if cursor is not None and (pt.x, pt.y) != cursor:
            moves += 1
        cursor = (pt.x, pt.y)
        log.flush()
        time.sleep(0.5)
    log.write("foreground changes: %d, cursor moves: %d\n" % (changes, moves))
    log.close()


if __name__ == "__main__":
    main(float(sys.argv[1]), sys.argv[2])
