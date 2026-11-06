"""
Generates high-resolution modern launcher icons for TurboDrop Android App
in all Android screen densities (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi).
"""

import os
import sys
from PIL import Image, ImageDraw

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

RES_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "res"))

def draw_turbodrop_icon(size: int, is_round: bool = False) -> Image.Image:
    # 4x supersampling for ultra-crisp antialiasing
    scale = 4
    s = size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    margin = max(2, s // 18)
    rect = [margin, margin, s - margin, s - margin]

    # Background shape (Circular or Rounded Squircle)
    bg_color = (22, 27, 34, 255)       # #161B22
    border_color = (88, 166, 255, 255) # #58A6FF
    border_w = max(2, s // 28)

    if is_round:
        draw.ellipse(rect, fill=bg_color, outline=border_color, width=border_w)
    else:
        radius = s // 4
        draw.rounded_rectangle(rect, radius=radius, fill=bg_color, outline=border_color, width=border_w)

    # Subtle inner lightning glow background
    glow_points = [
        (s * 0.52, s * 0.16),
        (s * 0.25, s * 0.52),
        (s * 0.47, s * 0.52),
        (s * 0.40, s * 0.85),
        (s * 0.76, s * 0.46),
        (s * 0.53, s * 0.46),
    ]
    draw.polygon(glow_points, fill=(31, 111, 235, 120))  # Deep blue glow

    # Main Electric Cyan Bolt
    bolt_points = [
        (s * 0.52, s * 0.17),
        (s * 0.27, s * 0.52),
        (s * 0.48, s * 0.52),
        (s * 0.41, s * 0.83),
        (s * 0.74, s * 0.46),
        (s * 0.53, s * 0.46),
    ]
    draw.polygon(bolt_points, fill=(88, 166, 255, 255))  # Electric cyan #58A6FF

    # Inner bright highlight on bolt
    hi_points = [
        (s * 0.52, s * 0.20),
        (s * 0.33, s * 0.50),
        (s * 0.48, s * 0.50),
        (s * 0.44, s * 0.72),
        (s * 0.64, s * 0.48),
        (s * 0.52, s * 0.48),
    ]
    draw.polygon(hi_points, fill=(240, 246, 252, 220))   # White/Cyan shimmer

    # Downsample with Lanczos high-quality filter
    return img.resize((size, size), Image.Resampling.LANCZOS)

def main():
    print("Generating TurboDrop Android Launcher Icons...")
    for folder, size in DENSITIES.items():
        out_dir = os.path.join(RES_DIR, folder)
        os.makedirs(out_dir, exist_ok=True)

        # Standard squircle icon
        icon_standard = draw_turbodrop_icon(size, is_round=False)
        std_path = os.path.join(out_dir, "ic_launcher.png")
        icon_standard.save(std_path, "PNG")

        # Round icon
        icon_round = draw_turbodrop_icon(size, is_round=True)
        round_path = os.path.join(out_dir, "ic_launcher_round.png")
        icon_round.save(round_path, "PNG")

        print(f"  -> Generated {folder}: {size}x{size} (standard & round)")

    print("✅ All Android launcher icons created successfully!")

if __name__ == "__main__":
    main()
