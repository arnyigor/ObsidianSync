from pathlib import Path
from math import sqrt

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "branding" / "obsidelta-sync-icon.png"
RESAMPLING = Image.Resampling.LANCZOS


def resized(source: Image.Image, size: int) -> Image.Image:
    return source.resize((size, size), RESAMPLING)


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True)


def round_icon(source: Image.Image, size: int) -> Image.Image:
    image = resized(source, size).convert("RGBA")
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    image.putalpha(mask)
    return image


def adaptive_foreground(source: Image.Image) -> Image.Image:
    rgb = source.convert("RGB")
    corners = [rgb.getpixel((x, y)) for x, y in ((0, 0), (1023, 0), (0, 1023), (1023, 1023))]
    background = tuple(sum(pixel[channel] for pixel in corners) // len(corners) for channel in range(3))
    rgba = Image.new("RGBA", rgb.size)
    output = []
    for red, green, blue in rgb.get_flattened_data():
        distance = sqrt(
            (red - background[0]) ** 2
            + (green - background[1]) ** 2
            + (blue - background[2]) ** 2
        )
        alpha = max(0, min(255, int((distance - 16) * 7)))
        output.append((red, green, blue, alpha))
    rgba.putdata(output)

    symbol = rgba.resize((324, 324), RESAMPLING)
    canvas = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    canvas.alpha_composite(symbol, ((432 - 324) // 2, (432 - 324) // 2))
    return canvas


def main() -> None:
    source = Image.open(SOURCE).convert("RGB").resize((1024, 1024), RESAMPLING)

    save_png(resized(source, 256), ROOT / "docs" / "assets" / "app-icon.png")
    save_png(source, ROOT / "iosApp" / "iosApp" / "Assets.xcassets" / "AppIcon.appiconset" / "app-icon-1024.png")
    save_png(resized(source, 512), ROOT / "desktopApp" / "src" / "main" / "resources" / "icon.png")
    save_png(adaptive_foreground(source), ROOT / "androidApp" / "src" / "main" / "res" / "drawable-nodpi" / "ic_launcher_foreground.png")

    densities = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    for density, size in densities.items():
        target = ROOT / "androidApp" / "src" / "main" / "res" / f"mipmap-{density}"
        save_png(resized(source, size), target / "ic_launcher.png")
        save_png(round_icon(source, size), target / "ic_launcher_round.png")

    ico_sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    source.save(
        ROOT / "desktopApp" / "src" / "main" / "resources" / "icon.ico",
        format="ICO",
        sizes=ico_sizes,
    )
    source.save(
        ROOT / "desktopApp" / "src" / "main" / "resources" / "icon.icns",
        format="ICNS",
    )


if __name__ == "__main__":
    main()
