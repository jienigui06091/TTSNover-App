import json, subprocess, concurrent.futures, time, os

srcs = json.load(open('/tmp/enginetest/srcs.json'))
env = dict(os.environ)
env.pop('http_proxy', None); env.pop('https_proxy', None); env.pop('HTTP_PROXY', None); env.pop('HTTPS_PROXY', None)
env['no_proxy'] = '*'

def run(s):
    job = json.dumps({"cmd": "test", "source": s, "key": "剑来"}, ensure_ascii=False)
    t0 = time.time()
    try:
        p = subprocess.run(['node', 'srce/cli.js'], input=job.encode(), cwd='/home/liumanu/code/webreader',
                           capture_output=True, timeout=40, env=env)
        r = json.loads(p.stdout.decode() or '{}')
    except Exception as e:
        r = {"ok": False, "error": "harness:" + str(e)}
    r['name'] = s.get('bookSourceName', '')
    r['sec'] = round(time.time() - t0, 1)
    return r

t0 = time.time()
with concurrent.futures.ThreadPoolExecutor(max_workers=10) as ex:
    res = list(ex.map(run, srcs))
json.dump(res, open('/tmp/enginetest/node_results.json', 'w'), ensure_ascii=False, indent=1)
ok = sum(1 for r in res if r.get('ok'))
print(f"node: {ok}/{len(res)} 有结果, 用时 {time.time()-t0:.0f}s")
