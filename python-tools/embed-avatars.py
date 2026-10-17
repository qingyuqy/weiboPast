import base64, html, re, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.stdout.reconfigure(encoding='utf-8')

# 匹配头像图床：tvax1/tvax2/tva3/tva4 等（仅头像，不碰正文图）
SRC_RE = re.compile(r'src="([^"]*(?:tv[ae]x?\d*)\.sinaimg\.cn[^"]*)"')
HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0',
    'Referer': 'https://weibo.com/',
}

def fetch_to_dataurl(url):
    try:
        req = urllib.request.Request(url, headers=HEADERS)
        with urllib.request.urlopen(req, timeout=20) as r:
            data = r.read()
        if not data:
            return None
        mime = r.headers.get_content_type() or 'image/jpeg'
        if mime not in ('image/jpeg', 'image/png', 'image/gif', 'image/webp'):
            mime = 'image/jpeg'
        return 'data:' + mime + ';base64,' + base64.b64encode(data).decode('ascii')
    except Exception:
        return None

def embed_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    srcs = SRC_RE.findall(content)
    if not srcs:
        return 0, 0, 0
    by_url = {}
    for s in srcs:
        by_url.setdefault(html.unescape(s), set()).add(s)
    url_to_data = {}
    with ThreadPoolExecutor(max_workers=16) as ex:
        futs = {ex.submit(fetch_to_dataurl, u): u for u in by_url}
        for fut in as_completed(futs):
            u = futs[fut]
            d = fut.result()
            if d:
                url_to_data[u] = d
    ok = fail = 0
    for u, escaped_set in by_url.items():
        d = url_to_data.get(u)
        for esc in escaped_set:
            if d:
                content = content.replace('src="' + esc + '"', 'src="' + d + '"')
                ok += 1
            else:
                fail += 1
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(content)
    return len(by_url), ok, fail

if __name__ == '__main__':
    import glob
    files = sorted(glob.glob(r'C:\Users\QingYu\Downloads\weibo-*.html'))
    print('files:', len(files))
    total_ok = total_fail = 0
    for p in files:
        u, ok, fail = embed_file(p)
        total_ok += ok; total_fail += fail
        print('{0}  唯一头像URL:{1}  内嵌:{2}  失败:{3}'.format(p.split('\\')[-1], u, ok, fail))
    print('TOTAL 内嵌:%d 失败:%d' % (total_ok, total_fail))
