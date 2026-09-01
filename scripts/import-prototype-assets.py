#!/usr/bin/env python3
"""Copy local design fonts and convert supported exported SVG paths without redrawing."""
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "prototype/assets"
RES = ROOT / "android/app/src/main/res"
ICONS = {
    "home": "bea94e9a-63d6-46a2-be51-c2a550277636.svg",
    "route": "91c986c2-5c7e-4908-a9ea-11f77f84ba30.svg",
    "plans": "1ae38d70-e6a0-416f-85d1-71545f1256bf.svg",
    "settings": "7b9895bd-a3db-41ea-b369-eb67fda8373d.svg",
    "shield": "2c52264e-dee6-4069-95c5-e8105043da04.svg",
    "sound": "1a089d58-bce2-4212-9ddf-707263a04ac1.svg",
    "calendar": "90134b92-81bd-4d11-a660-840f3ce9404d.svg",
    "preparation": "884d8613-99c8-456f-a031-be74b3fac797.svg",
    "snooze": "129742a8-cb1d-4163-9258-71c82066766b.svg",
    "weather": "425e3964-9492-4b65-b2a0-a59c5b4987ec.svg",
}
NS = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", NS)
def attr(name): return f"{{{NS}}}{name}"

def convert(name, source):
    tree = ET.parse(ASSETS / "figma-svg" / source).getroot()
    x, y, width, height = tree.attrib["viewBox"].split()
    assert float(x) == float(y) == 0
    vector = ET.Element("vector", {
        attr("width"): f"{width}dp", attr("height"): f"{height}dp",
        attr("viewportWidth"): width, attr("viewportHeight"): height,
    })
    def walk(node, inherited):
        local = node.tag.split("}")[-1]
        assert "transform" not in node.attrib, f"Unsupported transform in {source}"
        style = dict(inherited)
        style.update(node.attrib)
        if local in ("svg", "g"):
            for child in node: walk(child, style)
        elif local == "path":
            fill = style.get("fill", "#000000")
            props = {attr("pathData"): node.attrib["d"], attr("fillColor"): "#00000000" if fill == "none" else fill}
            for svg_name, android_name in (("stroke", "strokeColor"), ("stroke-width", "strokeWidth"), ("stroke-linecap", "strokeLineCap"), ("stroke-linejoin", "strokeLineJoin"), ("stroke-miterlimit", "strokeMiterLimit"), ("stroke-opacity", "strokeAlpha"), ("fill-opacity", "fillAlpha")):
                if svg_name in style: props[attr(android_name)] = style[svg_name]
            if style.get("fill-rule") == "evenodd": props[attr("fillType")] = "evenOdd"
            ET.SubElement(vector, "path", props)
        else:
            raise ValueError(f"Unsupported {local} in {source}; do not silently drop geometry")
    walk(tree, {})
    ET.indent(vector, space="    ")
    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    output = RES / "drawable" / f"ic_figma_{name}.xml"
    output.write_text(f'<?xml version="1.0" encoding="utf-8"?>\n<!-- Source: prototype/assets/figma-svg/{source}; path data unchanged. -->\n' + ET.tostring(vector, encoding="unicode") + "\n")

for name, source in ICONS.items(): convert(name, source)
(RES / "font").mkdir(parents=True, exist_ok=True)
for source, target in {
    "NotoSansCJKsc-Regular.otf": "noto_sans_sc_regular.otf",
    "NotoSansCJKsc-Medium.otf": "noto_sans_sc_medium.otf",
    "NotoSansCJKsc-Bold.otf": "noto_sans_sc_bold.otf",
    "Roboto-Variable.ttf": "roboto_variable.ttf",
}.items(): shutil.copyfile(ASSETS / "fonts" / source, RES / "font" / target)
license_dir = ROOT / "android/app/src/main/assets/licenses"
license_dir.mkdir(parents=True, exist_ok=True)
for source in (ASSETS / "fonts").glob("LICENSE-*.txt"):
    shutil.copyfile(source, license_dir / source.name)
print(f"Imported {len(ICONS)} original vector icons and 4 licensed fonts.")
