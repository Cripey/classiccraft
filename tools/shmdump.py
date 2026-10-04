#!/usr/bin/env python3
"""Dump the classiccraft bridge file (protocol/mcwow_protocol.h v3) once."""
import struct, sys
path = sys.argv[1] if len(sys.argv) > 1 else "/dev/shm/classiccraft_v1.shm"
b = open(path, "rb").read()
magic, ver, wpid, mpid, whb, mhb = struct.unpack_from("<IIIIQQ", b, 0)
print(f"magic={magic:#x} v{ver} wowPid={wpid} mcPid={mpid} wowHb={whb} mcHb={mhb}")
seq, x, y, z, facing, tick = struct.unpack_from("<IffffQ", b, 32)
grid = struct.unpack_from("<25f", b, 60)
mask, mapid, tseq, diff, guid = struct.unpack_from("<IIIIQ", b, 160)
print(f"wow: seq={seq} pos=({x:.2f},{y:.2f},{z:.2f}) facing={facing:.3f} map={mapid} teleportSeq={tseq} guid={guid:#x}")
print(f"     groundMask={mask:025b} center={grid[12]:.2f} (mc Y; feet mc Y {z/1.4667:.2f})")
mseq, ex, ey, ez, yaw, pitch, mtick, fx, fy, fz, fp, ack = struct.unpack_from("<IfffffQfffII", b, 184)
og, iw, vx, vy, vz, fov = struct.unpack_from("<IIffff", b, 236)
print(f"mc:  seq={mseq} eye=({ex:.2f},{ey:.2f},{ez:.2f}) yaw={yaw:.1f} pitch={pitch:.1f} feet=({fx:.2f},{fy:.2f},{fz:.2f}) "
      f"firstPerson={fp} ack={ack} onGround={og} water={iw} vel=({vx:.2f},{vy:.2f},{vz:.2f}) fov={fov:.1f}")
