#!/usr/bin/env python3
"""Rasterize Stint's original geometric S mark and assemble a macOS icon."""
from pathlib import Path
import subprocess
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "branding"
BRAND.mkdir(exist_ok=True)
SVG = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
<rect x="32" y="32" width="960" height="960" rx="210" fill="#1C3028"/>
<path d="M700 320H390Q320 320 320 390V430Q320 500 390 500H634Q704 500 704 570V634Q704 704 634 704H320" fill="none" stroke="#F4F0E7" stroke-width="100" stroke-linecap="round" stroke-linejoin="round"/>
<circle cx="734" cy="744" r="35" fill="#B96B4C"/>
</svg>'''
(BRAND / "stint.svg").write_text(SVG)


def quadratic(start, control, end):
    return [((1-t)**2*start[0]+2*(1-t)*t*control[0]+t*t*end[0],
             (1-t)**2*start[1]+2*(1-t)*t*control[1]+t*t*end[1])
            for t in [i/32 for i in range(33)]]


points = [(700,320),(390,320)]
points += quadratic((390,320),(320,320),(320,390))
points += [(320,430)] + quadratic((320,430),(320,500),(390,500))
points += [(634,500)] + quadratic((634,500),(704,500),(704,570))
points += [(704,634)] + quadratic((704,634),(704,704),(634,704)) + [(320,704)]


def render(size):
    scale = size*3/1024
    image = Image.new("RGBA", (size*3,size*3))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle(tuple(int(v*scale) for v in (32,32,992,992)), radius=int(210*scale), fill="#1C3028")
    path = [(x*scale,y*scale) for x,y in points]
    draw.line(path, fill="#F4F0E7", width=round(100*scale), joint="curve")
    for x,y in path:
        radius=50*scale
        draw.ellipse((x-radius,y-radius,x+radius,y+radius),fill="#F4F0E7")
    draw.ellipse(tuple(v*scale for v in (699,709,769,779)),fill="#B96B4C")
    return image.resize((size,size), Image.Resampling.LANCZOS)


iconset=BRAND / "Stint.iconset"
iconset.mkdir(exist_ok=True)
for size in [16,32,128,256,512]:
    render(size).save(iconset/f"icon_{size}x{size}.png")
    render(size*2).save(iconset/f"icon_{size}x{size}@2x.png")
render(1024).save(BRAND / "stint-1024.png")
subprocess.run(["iconutil","-c","icns",str(iconset),"-o",str(BRAND/"Stint.icns")],check=True)
print("Generated original Stint SVG, PNG and macOS ICNS assets.")
