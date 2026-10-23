import json, subprocess, os

srcs = json.load(open('/tmp/enginetest/srcs.json'))
env = dict(os.environ)
for k in ('http_proxy', 'https_proxy', 'HTTP_PROXY', 'HTTPS_PROXY'):
    env.pop(k, None)
env['no_proxy'] = '*'


def cli(job, timeout=60):
    p = subprocess.run(['node', 'srce/cli.js'], input=json.dumps(job, ensure_ascii=False).encode(),
                       cwd='/home/liumanu/code/webreader', capture_output=True, timeout=timeout, env=env)
    return json.loads(p.stdout.decode() or '{}')


want = {x['name'] for x in json.load(open('/tmp/enginetest/java_deep.json'))}
out = []
for s in srcs:
    if s.get('bookSourceName') not in want:
        continue
    name = s.get('bookSourceName', '')
    r = cli({"cmd": "search", "sources": [s], "key": "剑来", "page": 1})
    res = (r.get('results') or [{}])[0]
    books = res.get('books') or []
    if not books:
        continue
    rec = {"name": name, "bookUrl": books[0].get('bookUrl')}
    try:
        info = cli({"cmd": "info", "source": s, "bookUrl": rec["bookUrl"]})
        rec["infoName"] = info.get('name', '')
        rec["infoAuthor"] = info.get('author', '')
        rec["tocUrl"] = info.get('tocUrl', '')
        toc = cli({"cmd": "toc", "source": s, "tocUrl": rec["tocUrl"] or rec["bookUrl"]})
        chs = toc.get('chapters') or []
        rec["toc"] = len(chs)
        if chs:
            rec["chName"] = chs[0].get('name', '')
            ct = cli({"cmd": "content", "source": s, "chapterUrl": chs[0].get('url', '')})
            txt = ct.get('content', '') or ''
            rec["chars"] = len(txt)
            rec["head"] = txt[:60]
            rec["title"] = ct.get('title', '')
        rec["ok"] = True
    except Exception as e:
        rec["ok"] = False
        rec["error"] = str(e)[:160]
    out.append(rec)
    print(f"{name[:14]:<16} toc={rec.get('toc')} chars={rec.get('chars')} {str(rec.get('error',''))[:60]}")

json.dump(out, open('/tmp/enginetest/node_deep.json', 'w'), ensure_ascii=False, indent=1)
print("done", len(out))
