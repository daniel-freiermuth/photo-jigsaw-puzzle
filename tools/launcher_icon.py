"""Generates the launcher icon (a jigsaw piece holding a photo) as Android vector drawables.

Usage: python3 tools/launcher_icon.py [--preview DIR] [--store]
Writes app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml; with --preview, also
SVG renderings (full canvas with safe zone, circle and squircle masks, monochrome) into DIR; with --store,
the store icon and feature graphics under fastlane/metadata/android (needs rsvg-convert).

108x108 adaptive-icon canvas; launchers mask it to a circle/squircle of the central ~72dp, and the
safe zone is a 33dp-radius circle around (54, 54).
"""
import pathlib
import subprocess
import sys
import tempfile

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

def icon_art():
    """The icon's piece and photo without background, for embedding in other artwork."""
    return (f'<path d="{SHADOW}" fill="#000" fill-opacity="0.22"/>'
            f'<g clip-path="url(#p)"><rect x="20" y="18" width="72" height="72" fill="url(#sky)"/>'
            f'<path d="{SUN}" fill="{SUN_C}"/><path d="{FAR}" fill="{FAR_C}"/>'
            f'<path d="{NEAR}" fill="{NEAR_C}"/></g>'
            f'<path d="{PIECE}" fill="none" stroke="#fff" stroke-width="2.2" stroke-linejoin="round"/>')

def feature_svg(title, tagline):
    """1024x500 store banner: the piece on the icon's gradient, with the app name and a tagline."""
    defs = (f'<defs><clipPath id="p"><path d="{PIECE}"/></clipPath>'
            f'<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{BG_TOP}"/>'
            f'<stop offset="1" stop-color="{BG_BOTTOM}"/></linearGradient>'
            f'<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{SKY_TOP}"/>'
            f'<stop offset="1" stop-color="{SKY_BOTTOM}"/></linearGradient></defs>')
    font = 'font-family="Roboto, Noto Sans, DejaVu Sans, sans-serif"'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">{defs}'
            f'<rect width="1024" height="500" fill="url(#bg)"/>'
            f'<g transform="translate(-62,-78) scale(6.2)">{icon_art()}</g>'
            f'<text x="500" y="235" {font} font-size="96" font-weight="700" fill="#fff">{title}</text>'
            f'<text x="502" y="300" {font} font-size="34" fill="#E0F2FE">{tagline}</text></svg>')

FEATURE_TEXT = {
    "en-US": ("Jigsaw", "Puzzles from your own photos"),
    "de-DE": ("Puzzle", "Puzzles aus deinen Fotos"),
}

def render(svg_text, out, width, height):
    with tempfile.NamedTemporaryFile("w", suffix=".svg") as f:
        f.write(svg_text)
        f.flush()
        subprocess.run(["rsvg-convert", "-w", str(width), "-h", str(height), f.name, "-o", str(out)], check=True)

def store(root):
    for locale, (title, tagline) in FEATURE_TEXT.items():
        images = root / "fastlane/metadata/android" / locale / "images"
        images.mkdir(parents=True, exist_ok=True)
        render(svg("squircle"), images / "icon.png", 512, 512)
        render(feature_svg(title, tagline), images / "featureGraphic.png", 1024, 500)

if __name__ == "__main__":
    root = pathlib.Path(__file__).resolve().parent.parent
    drawable = root / "app/src/main/res/drawable"
    bg, fg, mono = android()
    for name, content in [("ic_launcher_background", bg), ("ic_launcher_foreground", fg), ("ic_launcher_monochrome", mono)]:
        (drawable / f"{name}.xml").write_text(content)
    args = sys.argv[1:]
    if "--preview" in args:
        previews(pathlib.Path(args[args.index("--preview") + 1]))
    if "--store" in args:
        store(root)
