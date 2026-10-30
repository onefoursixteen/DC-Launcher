"""Renders the DC Launcher banner/icon (house with a TV, DC Connect style) and a preview on the TV screenshot."""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import math, sys

def lerp(a, b, t): t = max(0, min(1, t)); return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))
def hexc(h): return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))

NAVY_GLOW, NAVY_MID, NAVY_DARK = hexc('#0B3466'), hexc('#06204A'), hexc('#010518')
TOP, MID, BOT = hexc('#6FD3F6'), hexc('#58ACF2'), hexc('#2F63CC')
SCREEN_TOP, SCREEN_BOT = hexc('#173A93'), hexc('#0E2763')

def background(w, h):
    bg = Image.new('RGB', (w, h)); px = bg.load(); cx, cy = w * 0.28, h * 0.30; r = math.hypot(w, h) * 0.85
    for y in range(h):
        for x in range(w):
            t = min(1, math.hypot(x - cx, y - cy) / r)
            px[x, y] = lerp(NAVY_GLOW, NAVY_MID, t * 1.6) if t < 0.62 else lerp(NAVY_MID, NAVY_DARK, (t - 0.62) / 0.38)
    return bg

def vgrad(w, h, top, mid, bot, shade=1.0):
    g = Image.new('RGB', (w, h)); d = ImageDraw.Draw(g)
    for y in range(h):
        t = y / max(1, h - 1); c = lerp(top, mid, t / 0.45) if t < 0.45 else lerp(mid, bot, (t - 0.45) / 0.55)
        d.line([(0, y), (w, y)], fill=tuple(int(v * shade) for v in c))
    return g

def house(size):
    S = size * 4; cx = S / 2
    pts = [(cx, S * 0.04), (S * 0.92, S * 0.43), (S * 0.92, S * 0.96), (S * 0.08, S * 0.96), (S * 0.08, S * 0.43)]
    m = Image.new('L', (S, S), 0); ImageDraw.Draw(m).polygon(pts, fill=255)
    m = m.filter(ImageFilter.GaussianBlur(S * 0.035)).point(lambda v: 255 if v > 127 else 0).filter(ImageFilter.GaussianBlur(2))
    split = Image.new('L', (S, S), 0); ImageDraw.Draw(split).rectangle([cx, 0, S, S], fill=255)
    fill = Image.composite(vgrad(S, S, TOP, MID, BOT, 0.86), vgrad(S, S, TOP, MID, BOT), split)
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0)); img.paste(fill, (0, 0), m)

    # White TV, solid like the padlock: thick white body, deep screen, chunky stand.
    d = ImageDraw.Draw(img)
    tw, th = S * 0.44, S * 0.29; tx0 = cx - tw / 2; ty0 = S * 0.46
    d.rounded_rectangle([tx0, ty0, tx0 + tw, ty0 + th], radius=int(S * 0.05), fill=(255, 255, 255, 255))
    bw = S * 0.05
    sx0, sy0, sx1, sy1 = tx0 + bw, ty0 + bw, tx0 + tw - bw, ty0 + th - bw
    scr = vgrad(int(sx1 - sx0), int(sy1 - sy0), SCREEN_TOP, SCREEN_TOP, SCREEN_BOT)
    sm = Image.new('L', scr.size, 0); ImageDraw.Draw(sm).rounded_rectangle([0, 0, scr.size[0] - 1, scr.size[1] - 1], radius=int(S * 0.02), fill=255)
    img.paste(scr, (int(sx0), int(sy0)), sm)
    # small white play mark on the screen: says "TV / watch" without words
    pcx, pcy, pr = (sx0 + sx1) / 2 + S * 0.006, (sy0 + sy1) / 2, (sy1 - sy0) * 0.26
    d.polygon([(pcx - pr * 0.7, pcy - pr), (pcx + pr, pcy), (pcx - pr * 0.7, pcy + pr)], fill=(255, 255, 255, 235))
    # stand
    ny = ty0 + th; nw = S * 0.06
    d.rectangle([cx - nw / 2, ny - 4, cx + nw / 2, ny + S * 0.045], fill=(255, 255, 255, 255))
    st_w, st_h = S * 0.24, S * 0.05
    d.rounded_rectangle([cx - st_w / 2, ny + S * 0.04, cx + st_w / 2, ny + S * 0.04 + st_h], radius=int(st_h / 2), fill=(255, 255, 255, 255))
    return img.resize((size, size), Image.LANCZOS)

def place(c, hs, x, y, blur, off):
    sh = house(hs); s = Image.new('RGBA', c.size, (0, 0, 0, 0)); s.paste((0, 0, 0, 150), (x, y + off), sh.split()[3])
    s = s.filter(ImageFilter.GaussianBlur(blur)); c = Image.alpha_composite(c, s); c.alpha_composite(sh, (x, y)); return c

W, H = 1280, 720; b = background(W, H).convert('RGBA'); hs = int(H * 0.80)
b = place(b, hs, (W - hs) // 2, (H - hs) // 2 - 4, 24, 16)
b.convert('RGB').save('dc_banner_1280x720.png'); b.convert('RGB').resize((320, 180), Image.LANCZOS).save('dc_banner_320x180.png')

I = 512; ic = background(I, I).convert('RGBA'); hs = int(I * 0.74); ic = place(ic, hs, (I - hs) // 2, (I - hs) // 2 - 4, 10, 8)
m = Image.new('L', (I, I), 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, I - 1, I - 1], radius=int(I * 0.17), fill=255)
o = Image.new('RGBA', (I, I), (0, 0, 0, 0)); o.paste(ic, (0, 0), m); o.save('dc_icon_512.png')

shot = Image.open('../screenshots/chromecast-20261027-205740.png').convert('RGB')
x0, y0, x1, y1 = 469, 379, 762, 544
tile = b.convert('RGB').resize((x1 - x0, y1 - y0), Image.LANCZOS)
mk = Image.new('L', tile.size, 0); ImageDraw.Draw(mk).rounded_rectangle([0, 0, tile.size[0] - 1, tile.size[1] - 1], radius=22, fill=255)
shot.paste(tile, (x0, y0), mk)
L, R, T, B = x0 - 25, x1 + 25, 562, 616; px = shot.load()
for y in range(T, B):
    a = px[L - 1, y]; c = px[R + 1, y]
    for x in range(L, R): px[x, y] = lerp(a, c, (x - L) / (R - L))
d = ImageDraw.Draw(shot); f = ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf', 30)
t = 'DC Launcher'; d.text(((x0 + x1) / 2 - d.textlength(t, font=f) / 2, 571), t, font=f, fill=(214, 220, 229))
shot.save('preview_on_chromecast.png'); shot.crop((96, 360, 1100, 640)).save('preview_closeup.png')
print('ok')
