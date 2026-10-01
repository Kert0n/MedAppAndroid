#!/usr/bin/env python3
"""touches.py in.mp4 out.mp4 — точки касаний поверх записи по журналу режиссёра (in.mp4.touches.json)."""
import json, subprocess, sys, os

src, dst = sys.argv[1], sys.argv[2]
log = json.load(open(src + ".touches.json"))
dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", src],
                           capture_output=True, text=True).stdout)
start = log["stopped"] - dur          # момент первого кадра записи
LAG = 0.08                            # команда доходит до экрана чуть позже, чем записана
R = 42

dot = os.path.join(os.path.dirname(dst) or ".", ".touch-dot.png")
subprocess.run([
    "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"color=c=black@0:s={2*R}x{2*R},format=rgba",
    "-vf", f"geq=r=255:g=255:b=255:a='if(lt(hypot(X-{R},Y-{R}),{R-3}),120,if(lt(hypot(X-{R},Y-{R}),{R}),200,0))'",
    "-frames:v", "1", dot], check=True)

touches = [t for t in log["touches"] if t["at"] - start > 0]
if not touches:
    subprocess.run(["cp", src, dst], check=True)
    raise SystemExit
parts = [f"[1]split={len(touches)}" + "".join(f"[d{i}]" for i in range(len(touches)))]
prev = "0:v"
for i, t in enumerate(touches):
    a = t["at"] - start + LAG
    d = max(t["ms"], 1) / 1000
    b = a + d + 0.18
    x = f"{t['x1'] - R}+({t['x2'] - t['x1']})*clip((t-{a:.3f})/{d:.3f},0,1)"
    y = f"{t['y1'] - R}+({t['y2'] - t['y1']})*clip((t-{a:.3f})/{d:.3f},0,1)"
    out = f"v{i}"
    parts.append(f"[{prev}][d{i}]overlay=x='{x}':y='{y}':enable='between(t,{a:.3f},{b:.3f})'[{out}]")
    prev = out
graph = ";".join(parts)
subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src, "-loop", "1", "-i", dot, "-filter_complex", graph,
                "-map", f"[{prev}]", "-c:v", "libx264", "-crf", "20", "-preset", "medium", "-pix_fmt", "yuv420p",
                "-movflags", "+faststart", "-shortest", "-t", f"{dur:.2f}", dst], check=True)
print(f"касаний: {len(touches)} → {dst}")
