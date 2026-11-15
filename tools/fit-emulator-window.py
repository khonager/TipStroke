#!/usr/bin/env python3
"""Keep the fixed-aspect tablet emulator out of Hyprland's tiled layout."""
import json
import os
import re
import shutil
import subprocess
import sys


def output(*args):
    return subprocess.check_output(args, text=True, timeout=10)


def dispatch(expression, *legacy):
    result = output("hyprctl", "dispatch", expression).strip()
    if result == "Invalid dispatcher":
        result = output("hyprctl", "dispatch", *legacy).strip()
    if result not in ("", "ok"):
        raise RuntimeError(result)


def main():
    if not os.environ.get("HYPRLAND_INSTANCE_SIGNATURE") or not shutil.which("hyprctl"):
        return
    adb, serial = sys.argv[1:]
    clients = json.loads(output("hyprctl", "clients", "-j"))
    # Match this AVD's main window only; never touch other emulators or toolbars.
    title = f"Android Emulator - TipStroke_Tablet_API_35:{serial.removeprefix('emulator-')}"
    window = next((c for c in clients if c["class"] == "Emulator" and c["title"] == title), None)
    if not window or window["floating"]:
        return
    address = window["address"]
    if not re.fullmatch(r"0x[0-9a-fA-F]+", address):
        raise ValueError("Unexpected window address")
    monitors = json.loads(output("hyprctl", "monitors", "-j"))
    monitor = next(m for m in monitors if m["id"] == window["monitor"])
    display = output(adb, "-s", serial, "shell", "dumpsys", "display")
    rect = re.search(r"mCurrentDisplayRect=Rect\(0, 0 - (\d+), (\d+)\)", display)
    if not rect:
        raise ValueError("Could not read Android display dimensions")
    width, height = map(int, rect.groups())
    mw, mh = monitor["width"], monitor["height"]
    if monitor.get("transform", 0) % 2:
        mw, mh = mh, mw
    mw, mh = mw / monitor["scale"], mh / monitor["scale"]
    factor = min((mw * .85 - 64) / width, mh * .85 / height)
    w, h = max(1, round(width * factor)), max(1, round(height * factor))
    selector = f"address:{address}"
    dispatch(f'hl.dsp.window.float({{window="{selector}",action="enable"}})', "setfloating", selector)
    dispatch(f'hl.dsp.window.resize({{window="{selector}",x={w},y={h}}})', "resizewindowpixel", f"exact {w} {h},{selector}")
    x = round(monitor["x"] + (mw - w - 64) / 2)
    y = round(monitor["y"] + (mh - h) / 2)
    dispatch(f'hl.dsp.window.move({{window="{selector}",x={x},y={y}}})', "movewindowpixel", f"exact {x} {y},{selector}")
    print("Fitted the emulator window to the Android display (floating).")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, StopIteration, RuntimeError, subprocess.SubprocessError) as error:
        print(f"Could not fit emulator window: {error}. Float it manually to avoid unused space.", file=sys.stderr)
