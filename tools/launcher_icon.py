"""Generates the launcher icon (a jigsaw piece holding a photo) as Android vector drawables.

Usage: python3 tools/launcher_icon.py [--preview DIR]
Writes app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml; with --preview, also
SVG renderings (full canvas with safe zone, circle and squircle masks, monochrome) into DIR.

108x108 adaptive-icon canvas; launchers mask it to a circle/squircle of the central ~72dp, and the
safe zone is a 33dp-radius circle around (54, 54).
"""
import pathlib
import sys

# Canonical tab on an edge from (0,0) to (1,0), bulging towards -y (outwards), in edge units.
TAB = [  # cubic segments after a straight run to (0.38, 0): (c1, c2, end)
    ((0.41, 0.00), (0.43, -0.04), (0.41, -0.09)),
    ((0.36, -0.19), (0.42, -0.28), (0.50, -0.28)),
    ((0.58, -0.28), (0.64, -0.19), (0.59, -0.09)),
    ((0.57, -0.04), (0.59, 0.00), (0.62, 0.00)),
]

def edge(p0, p1, kind):
    """Path commands from p0 to p1. kind: +1 tab (outwards = left of travel), -1 blank, 0 straight."""
    (x0, y0), (x1, y1) = p0, p1
    dx, dy = x1 - x0, y1 - y0
    nx, ny = dy, -dx  # left normal for clockwise travel in screen coords = outward
    def pt(u, v):
        v *= kind
        return (x0 + u * dx - v * nx, y0 + u * dy - v * ny)
    if kind == 0:
        return [f"L{x1:.2f},{y1:.2f}"]
    s = pt(0.38, 0)
    out = [f"L{s[0]:.2f},{s[1]:.2f}"]
    for c1, c2, e in TAB:
        a, b, c = pt(*c1), pt(*c2), pt(*e)
        out.append(f"C{a[0]:.2f},{a[1]:.2f} {b[0]:.2f},{b[1]:.2f} {c[0]:.2f},{c[1]:.2f}")
    out.append(f"L{x1:.2f},{y1:.2f}")
    return out

def piece(x0, y0, x1, y1, top, right, bottom, left):
    d = [f"M{x0:.2f},{y0:.2f}"]
    d += edge((x0, y0), (x1, y0), top)
    d += edge((x1, y0), (x1, y1), right)
    d += edge((x1, y1), (x0, y1), bottom)
    d += edge((x0, y1), (x0, y0), left)
    return " ".join(d) + " Z"

# Body 32..76 (44dp): corners stay inside the 33dp safe circle; tabs on top and right, blanks bottom/left.
PIECE = piece(33, 39, 71, 77, top=1, right=1, bottom=-1, left=-1)
SHADOW = piece(34.2, 40.6, 72.2, 78.6, top=1, right=1, bottom=-1, left=-1)

# Landscape inside the piece (drawn under the clip).
SUN = "M60,49 a5,5 0 1,0 0.01,0 Z"
FAR = "M20,70 L38,56 L46,62 L58,52 L90,74 L90,90 L20,90 Z"
NEAR = "M20,76 L34,66 L46,73 L56,68 L90,82 L90,90 L20,90 Z"
WATER = "M20,80 L90,80 L90,90 L20,90 Z"

BG_TOP, BG_BOTTOM = "#4F46E5", "#0EA5E9"      # indigo -> sky
SKY_TOP, SKY_BOTTOM = "#FDE68A", "#FCA5A5"    # warm dusk sky inside the piece
SUN_C, FAR_C, NEAR_C, WATER_C = "#FFFBEB", "#7C3AED", "#312E81", "#1E1B4B"

def svg(mask=None):
    clip = f'<clipPath id="p"><path d="{PIECE}"/></clipPath>'
    grad = (f'<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{BG_TOP}"/>'
            f'<stop offset="1" stop-color="{BG_BOTTOM}"/></linearGradient>'
            f'<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{SKY_TOP}"/>'
            f'<stop offset="1" stop-color="{SKY_BOTTOM}"/></linearGradient>')
    body = (f'<rect width="108" height="108" fill="url(#bg)"/>'
            f'<path d="{SHADOW}" fill="#000" fill-opacity="0.22"/>'
            f'<g clip-path="url(#p)"><rect x="20" y="18" width="72" height="72" fill="url(#sky)"/>'
            f'<path d="{SUN}" fill="{SUN_C}"/><path d="{FAR}" fill="{FAR_C}"/>'
            f'<path d="{NEAR}" fill="{NEAR_C}"/></g>'
            f'<path d="{PIECE}" fill="none" stroke="#fff" stroke-width="2.2" stroke-linejoin="round"/>')
    if mask == "circle":
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><defs>{clip}{grad}'
                f'<clipPath id="m"><circle cx="54" cy="54" r="36"/></clipPath></defs><g clip-path="url(#m)">{body}</g></svg>')
    if mask == "squircle":
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><defs>{clip}{grad}'
                f'<clipPath id="m"><rect x="18" y="18" width="72" height="72" rx="20"/></clipPath></defs>'
                f'<g clip-path="url(#m)">{body}</g></svg>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><defs>{clip}{grad}</defs>{body}' \
           f'<circle cx="54" cy="54" r="33" fill="none" stroke="#f00" stroke-width="0.3"/></svg>'

def mono_svg():
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><rect x="18" y="18" width="72" height="72" fill="#333"/>'
            f'<defs><clipPath id="p"><path d="{PIECE}"/></clipPath></defs>'
            f'<g clip-path="url(#p)"><path d="{SUN}" fill="#cde"/><path d="{FAR}" fill="#cde" fill-opacity="0.55"/>'
            f'<path d="{NEAR}" fill="#cde"/></g>'
            f'<path d="{PIECE}" fill="none" stroke="#cde" stroke-width="3" stroke-linejoin="round"/></svg>')

def previews(out):
    for name, content in [("full", svg()), ("circle", svg("circle")), ("squircle", svg("squircle")), ("mono", mono_svg())]:
        (out / f"{name}.svg").write_text(content)

AAPT = 'xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"'
HEAD = "<!-- Generated by tools/launcher_icon.py; edit that instead. Launcher icon layer: 108dp adaptive-icon canvas, safe zone a 33dp circle around the centre. -->\n"

def gradient_path(path, top, bottom, y0, y1):
    return (f'    <path android:pathData="{path}">\n'
            f'        <aapt:attr name="android:fillColor">\n'
            f'            <gradient android:type="linear" android:startX="54" android:startY="{y0}" '
            f'android:endX="54" android:endY="{y1}" android:startColor="{top}" android:endColor="{bottom}" />\n'
            f'        </aapt:attr>\n    </path>\n')

def vector(body):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n{HEAD}<vector {AAPT}\n    android:width="108dp" android:height="108dp"\n'
            f'    android:viewportWidth="108" android:viewportHeight="108">\n{body}</vector>\n')

def android():
    background = vector(gradient_path("M0,0 H108 V108 H0 Z", BG_TOP, BG_BOTTOM, 0, 108))
    foreground = vector(
        f'    <path android:pathData="{SHADOW}" android:fillColor="#38000000" />\n'
        f'    <group>\n        <clip-path android:pathData="{PIECE}" />\n'
        + gradient_path("M20,18 H92 V90 H20 Z", SKY_TOP, SKY_BOTTOM, 26, 77).replace("\n    ", "\n        ").replace("    <path", "        <path", 1)
        + f'        <path android:pathData="{SUN}" android:fillColor="{SUN_C}" />\n'
        f'        <path android:pathData="{FAR}" android:fillColor="{FAR_C}" />\n'
        f'        <path android:pathData="{NEAR}" android:fillColor="{NEAR_C}" />\n'
        f'    </group>\n'
        f'    <path android:pathData="{PIECE}" android:strokeColor="#FFFFFF" android:strokeWidth="2.2" '
        f'android:strokeLineJoin="round" />\n')
    monochrome = vector(
        f'    <group>\n        <clip-path android:pathData="{PIECE}" />\n'
        f'        <path android:pathData="{SUN}" android:fillColor="#FFFFFF" />\n'
        f'        <path android:pathData="{FAR}" android:fillColor="#FFFFFF" android:fillAlpha="0.55" />\n'
        f'        <path android:pathData="{NEAR}" android:fillColor="#FFFFFF" />\n'
        f'    </group>\n'
        f'    <path android:pathData="{PIECE}" android:strokeColor="#FFFFFF" android:strokeWidth="3" '
        f'android:strokeLineJoin="round" />\n')
    return background, foreground, monochrome

if __name__ == "__main__":
    drawable = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"
    bg, fg, mono = android()
    for name, content in [("ic_launcher_background", bg), ("ic_launcher_foreground", fg), ("ic_launcher_monochrome", mono)]:
        (drawable / f"{name}.xml").write_text(content)
    if len(sys.argv) == 3 and sys.argv[1] == "--preview":
        previews(pathlib.Path(sys.argv[2]))
