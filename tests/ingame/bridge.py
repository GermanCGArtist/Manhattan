"""Send one command to a running testing instance by hand (or from Claude); prints the JSON answer.

  python tests/ingame/bridge.py state
  python tests/ingame/bridge.py chat /time set day
  python tests/ingame/bridge.py chatlog 0
  python tests/ingame/bridge.py runfile F:/path/to/script.txt

The arguments are joined with spaces into one command. Commands: see docs/TESTING.md.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mc  # noqa: E402

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    reply = mc.Bridge().connect().raw(" ".join(sys.argv[1:]))
    print(json.dumps(reply, ensure_ascii=False))
    sys.exit(0 if reply.get("ok") else 1)
