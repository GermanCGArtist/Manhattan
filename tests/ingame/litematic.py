"""Writes a small .litematic (gzip NBT) and the Litematica placement file for the schematic tests. Standard library only."""
import gzip
import json
import math
import os
import struct


def _s(x):
    b = x.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def _tag(kind, name, payload):
    return bytes([kind]) + _s(name) + payload


class Comp:
    def __init__(self):
        self.items = []

    def put(self, name, value):
        self.items.append((name, value))
        return self

    def raw(self):
        out = b""
        for name, v in self.items:
            out += _enc(name, v)
        return out + b"\x00"


class IntList(list):
    pass


class LongArray(list):
    pass


class CompList(list):
    pass


def _enc(name, v):
    if isinstance(v, bool):
        raise TypeError
    if isinstance(v, int):
        return _tag(3, name, struct.pack(">i", v))
    if isinstance(v, str):
        return _tag(8, name, _s(v))
    if isinstance(v, Comp):
        return _tag(10, name, v.raw())
    if isinstance(v, LongArray):
        return _tag(12, name, struct.pack(">i", len(v)) + b"".join(struct.pack(">q", x) for x in v))
    if isinstance(v, CompList):
        return _tag(9, name, bytes([10]) + struct.pack(">i", len(v)) + b"".join(c.raw() for c in v))
    raise TypeError(type(v))


def pack(indices, palette_size):
    bits = max(2, math.ceil(math.log2(max(2, palette_size))))
    total = len(indices) * bits
    longs = [0] * ((total + 63) // 64)
    for i, v in enumerate(indices):
        start = i * bits
        a, off = start >> 6, start & 63
        longs[a] |= (v << off) & 0xFFFFFFFFFFFFFFFF
        if off + bits > 64:
            longs[a + 1] |= v >> (64 - off)
    return [x - (1 << 64) if x >= (1 << 63) else x for x in longs]


def write(path, name, size, blocks):
    """blocks: {(x,y,z): "minecraft:stone[facing=east]"}; every other position is air. One region at 0,0,0."""
    sx, sy, sz = size
    palette = ["minecraft:air"]
    idx = []
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                b = blocks.get((x, y, z), "minecraft:air")
                if b not in palette:
                    palette.append(b)
                idx.append(palette.index(b))
    pal = CompList()
    for p in palette:
        c = Comp().put("Name", p.split("[")[0])
        if "[" in p:
            props = Comp()
            for kv in p[p.index("[") + 1:-1].split(","):
                k, v = kv.split("=")
                props.put(k, v)
            c.put("Properties", props)
        pal.append(c)
    region = (Comp().put("Position", Comp().put("x", 0).put("y", 0).put("z", 0))
              .put("Size", Comp().put("x", sx).put("y", sy).put("z", sz))
              .put("BlockStatePalette", pal).put("BlockStates", LongArray(pack(idx, len(palette)))))
    root = (Comp().put("MinecraftDataVersion", 4700).put("Version", 6).put("SubVersion", 1)
            .put("Metadata", Comp().put("Name", name).put("RegionCount", 1).put("TotalBlocks", len(blocks)))
            .put("Regions", Comp().put("main", region)))
    data = _tag(10, "", root.raw())
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with gzip.open(path, "wb") as f:
        f.write(data)


def placements_json(items):
    """items: [(name, schematic_path, (x,y,z), rotation, mirror, enabled)] -> Litematica's per-world file content."""
    out = []
    for name, path, origin, rot, mir, enabled in items:
        out.append({
            "schematic": path, "name": name, "origin": list(origin), "rotation": rot, "mirror": mir,
            "ignore_entities": False, "enabled": enabled, "enable_render": True, "render_enclosing_box": False,
            "locked": False, "locked_coords": 0, "bb_color": 16757504, "verifier_type": "all",
            "hash_code": "00000000-0000-0000-0000-0000000000%02d" % len(out),
            "placements": [{"name": "main", "placement": {
                "pos": [0, 0, 0], "name": "main", "rotation": "NONE", "mirror": "NONE", "locked_coords": 0,
                "enabled": True, "rendering_enabled": True, "ignore_entities": False}}]})
    return json.dumps({"placements": out}, indent=1)
