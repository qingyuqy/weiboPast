import base64, io, re, sys
from PIL import Image

sys.stdout.reconfigure(encoding='utf-8')

AVATAR_RE = re.compile(r'(<img class="(?:avatar|c-avatar)" src=")(data:image/[^"]+)(")')
COVER_RE = re.compile(r'(<div class="cover-user">\s*<img src=")(data:image/[^"]+)(")')
MAX_SIDE = 160

def shrink(data_uri):
    try:
        m = re.match(r'data:image/(\w+);base64,(.*)$', data_uri, re.S)
        if not m:
            return data_uri
        raw = base64.b64decode(m.group(2))
        im = Image.open(io.BytesIO(raw))
        im.load()
        if max(im.size) <= MAX_SIDE:
            return data_uri
        im.thumbnail((MAX_SIDE, MAX_SIDE), Image.LANCZOS)
        if im.mode in ('RGBA', 'LA', 'P'):
            im = im.convert('RGB')
        buf = io.BytesIO()
        im.save(buf, 'JPEG', quality=85, optimize=True)
        return 'data:image/jpeg;base64,' + base64.b64encode(buf.getvalue()).decode('ascii')
    except Exception as e:
        return data_uri

def process(path):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    uris = set()
    for m in AVATAR_RE.finditer(content):
        uris.add(m.group(2))
    for m in COVER_RE.finditer(content):
        uris.add(m.group(2))
    mapping = {}
    for u in uris:
        s = shrink(u)
        if s != u:
            mapping[u] = s
    for u, s in mapping.items():
        content = content.replace(u, s)
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(content)
    return len(uris), len(mapping)

if __name__ == '__main__':
    import glob
    files = sorted(glob.glob(r'C:\Users\QingYu\Downloads\weibo-*.html'))
    print('files:', len(files))
    for p in files:
        u, ok = process(p)
        print('{0}  头像dataURI:{1}  压缩:{2}'.format(p.split('\\')[-1], u, ok))
