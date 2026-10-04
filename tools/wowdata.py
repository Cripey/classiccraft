"""Shared readers for the server's data (MariaDB on 3307, VMaNGOS .map files): used by
mine_sites.py and vendor_types.py."""
import os, struct, subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAPS = os.path.join(ROOT, "data/server/maps")
GRID = 533.33333


def sql(q):
    out = subprocess.run(["mysql", "-h127.0.0.1", "-P3307", "-umangos", "-pmangos", "mangos", "-N", "-B", "-e", q],
                         capture_output=True, text=True, check=True).stdout
    return [l.split("\t") for l in out.splitlines() if l]

_tiles = {}
def tile(m, x, y):
    # file name: map, then the grid of world x, then of world y (VMaNGOS TerrainInfo::LoadMapAndVMap)
    key = (m, int(32 - x / GRID), int(32 - y / GRID))
    if key not in _tiles:
        p = os.path.join(MAPS, "%03d%02d%02d.map" % key)
        _tiles[key] = open(p, "rb").read() if os.path.exists(p) else None
    return _tiles[key]

def area_flag(m, x, y):
    d = tile(m, x, y)
    if not d: return None
    off = struct.unpack_from("<I", d, 8)[0]
    _, flags, grid_area = struct.unpack_from("<IHH", d, off)
    if flags & 1: return grid_area
    lx = int(16 * (32 - x / GRID)) & 15; ly = int(16 * (32 - y / GRID)) & 15
    return struct.unpack_from("<H", d, off + 8 + 2 * (lx * 16 + ly))[0]

def height(m, x, y):
    d = tile(m, x, y)
    if not d: return None
    off = struct.unpack_from("<I", d, 16)[0]
    _, flags, h0, hmax = struct.unpack_from("<IIff", d, off)
    if flags & 1: return h0
    fx = 128 * (32 - x / GRID); fy = 128 * (32 - y / GRID)
    xi = int(fx) & 127; yi = int(fy) & 127
    i = xi * 129 + yi; base = off + 16
    if flags & 2: return struct.unpack_from("<H", d, base + 2 * i)[0] * (hmax - h0) / 65535 + h0
    if flags & 4: return struct.unpack_from("<B", d, base + i)[0] * (hmax - h0) / 255 + h0
    return struct.unpack_from("<f", d, base + 4 * i)[0]


def areas():
    """(map, explore flag) -> (area entry, zone entry, area level, name)."""
    out = {}
    for e, m, z, flag, lvl, name in sql("select entry,map_id,zone_id,explore_flag,area_level,name from area_template"):
        out[(int(m), int(flag))] = (int(e), int(z), int(lvl), name)
    return out
