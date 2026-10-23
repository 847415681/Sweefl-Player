from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1]
shots = root / 'docs' / 'screenshots'
canvas = Image.new('RGB', (1200, 820), '#0b1220')
draw = ImageDraw.Draw(canvas)
font = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 32)
small = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 18)
draw.text((36, 24), 'Sweefl Player', font=font, fill='#adf6dc')
draw.text((36, 68), '0.1.0  /  Android 16  /  ARM64  /  Real device captures', font=small, fill='#a8b8ca')
phone = Image.open(shots / 'home-final.png').convert('RGB')
phone.thumbnail((302, 655), Image.Resampling.LANCZOS)
canvas.paste(phone, (36, 126))
landscape = Image.open(shots / 'landscape.png').convert('RGB')
landscape.thumbnail((780, 500), Image.Resampling.LANCZOS)
draw.text((378, 170), 'Playback controls / Landscape', font=small, fill='#adf6dc')
canvas.paste(landscape, (378, 210))
draw.text((378, 610), 'Local playback  |  1.5x speed  |  Resume at 00:10', font=small, fill='#e5eef8')
draw.text((378, 646), 'Video shown is a recording of the app itself.', font=small, fill='#a8b8ca')
draw.text((378, 682), 'Glass-inspired gradients, translucency and edge highlights.', font=small, fill='#a8b8ca')
canvas.save(shots / 'preview.png')
