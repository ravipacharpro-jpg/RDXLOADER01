import os
from PIL import Image, ImageDraw, ImageFilter

def clean_background(image_path, output_path):
    print(f"Loading background from {image_path}...")
    if not os.path.exists(image_path):
        raise FileNotFoundError(f"Source image not found: {image_path}")

    img = Image.open(image_path).convert("RGBA")
    w, h = img.size
    print(f"Image dimensions: {w}x{h}")

    # Create a clean canvas
    clean_img = img.copy()

    # 1. Clean Top-Left Text and Logos (x: 0..720, y: 0..780)
    # The sky in this area is a subtle vertical gradient from (248, 250, 252) to (236, 242, 248)
    top_w = 720
    top_h = 780
    sky_patch = Image.new("RGBA", (top_w, top_h))
    draw_sky = ImageDraw.Draw(sky_patch)

    top_color = (248, 250, 252)
    bottom_color = (236, 241, 247)

    for y in range(top_h):
        ratio = y / float(top_h)
        r = int(top_color[0] + (bottom_color[0] - top_color[0]) * ratio)
        g = int(top_color[1] + (bottom_color[1] - top_color[1]) * ratio)
        b = int(top_color[2] + (bottom_color[2] - top_color[2]) * ratio)
        draw_sky.line([(0, y), (top_w, y)], fill=(r, g, b, 255))

    # Create a soft alpha mask for smooth feathering at right (x: 650..720) and bottom (y: 740..780)
    mask = Image.new("L", (top_w, top_h), 255)

    # Feather right edge to blend into leaf branches
    for x in range(650, top_w):
        alpha = int(255 * (1.0 - (x - 650) / 70.0))
        for y in range(top_h):
            current_a = mask.getpixel((x, y))
            mask.putpixel((x, y), min(current_a, alpha))

    # Feather bottom edge to blend into soldier helmet & mountain mist
    for y in range(740, top_h):
        alpha = int(255 * (1.0 - (y - 740) / 40.0))
        for x in range(top_w):
            current_a = mask.getpixel((x, y))
            mask.putpixel((x, y), min(current_a, alpha))

    # Paste the clean sky patch over the text & IB logo region
    clean_img.paste(sky_patch, (0, 0), mask)

    # 2. Clean Bottom Sheet & Old Login Button (y: 1330..h)
    # Fade smoothly into #F8FAFC so no old buttons, password box, or old cards bleed through
    bot_y_start = 1330
    bot_h = h - bot_y_start
    bot_patch = Image.new("RGBA", (w, bot_h))
    draw_bot = ImageDraw.Draw(bot_patch)

    bg_target = (248, 250, 252) # #F8FAFC

    fade_zone = 55 # quick smooth fade from 1330 to 1385, then 100% solid #F8FAFC
    for y in range(bot_h):
        if y < fade_zone:
            alpha = int(255 * (y / float(fade_zone)))
        else:
            alpha = 255
        draw_bot.line([(0, y), (w, y)], fill=(bg_target[0], bg_target[1], bg_target[2], alpha))

    clean_img.paste(bot_patch, (0, bot_y_start), bot_patch)

    # Save as high-quality RGB JPEG
    final_rgb = clean_img.convert("RGB")
    final_rgb.save(output_path, "JPEG", quality=95, optimize=True)
    print(f"Clean background successfully saved to {output_path} ({os.path.getsize(output_path)} bytes)")

if __name__ == "__main__":
    import sys
    src = "app/src/main/res/drawable/bg_soldier.jpg"
    out = "app/src/main/res/drawable/bg_soldier.jpg"
    if len(sys.argv) > 1:
        src = sys.argv[1]
    if len(sys.argv) > 2:
        out = sys.argv[2]
    clean_background(src, out)
