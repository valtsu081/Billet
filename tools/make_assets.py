import os
import re
import sys
import urllib.request

UA = ('Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/124.0 Safari/537.36')
SUBSETS = ('latin', 'latin-ext')


def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def main(src, out):
    html = open(src, encoding='utf-8').read()
    link = re.search(r'<link rel="stylesheet" href="(https://fonts\.googleapis\.com/css2[^"]+)">', html)
    if not link:
        sys.exit('Could not find the Google Fonts link in ' + src)
    css = fetch(link.group(1).replace('&amp;', '&')).decode('utf-8')

    os.makedirs(os.path.join(out, 'fonts'), exist_ok=True)
    faces = []
    saved = {}  # Google serves one variable-font file for several weights: bundle each file once
    for subset, block in re.findall(r'/\* ([\w-]+) \*/\s*(@font-face\s*\{[^}]*\})', css):
        if subset not in SUBSETS:
            continue
        family = re.search(r"font-family:\s*'([^']+)'", block).group(1)
        weight = re.search(r'font-weight:\s*([^;]+);', block).group(1).strip().replace(' ', '-')
        url = re.search(r'url\((https://[^)]+)\)', block).group(1)
        if url not in saved:
            name = f"{family.lower().replace(' ', '-')}-{weight}-{subset}.woff2"
            with open(os.path.join(out, 'fonts', name), 'wb') as f:
                f.write(fetch(url))
            saved[url] = name
        faces.append(block.replace(url, 'fonts/' + saved[url]))
    if not faces:
        sys.exit('No font files found in the Google Fonts response')

    html = re.sub(r'<link rel="preconnect"[^>]*>\s*', '', html)
    html = html.replace(link.group(0), '<style>\n' + '\n'.join(faces) + '\n</style>')

    head = ('<!doctype html><html lang="en"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
            '<style>:root{color-scheme:light}body{margin:0;font:14px system-ui}'
            'img{max-width:100%}[hidden]{display:none!important}</style></head><body>')
    with open(os.path.join(out, 'index.html'), 'w', encoding='utf-8') as f:
        f.write(head + html + '</body></html>')
    print(f'index.html written with {len(faces)} bundled font files')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
