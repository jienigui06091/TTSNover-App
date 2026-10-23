# 书源引擎一致性对照（Java 移植 vs 网页版 Node 引擎）

`RuleEngine.java` 是把网页版 `srce/engine.js` 等价移植到 Java 的产物。改动它之后，
用这套脚本拿同一批书源跑两边，逐个源比对结果，避免「改一处、悄悄弄坏另一批源」。

## 一次性准备

```bash
# 1) 从网页版的库里导出书源（91 个）
cd /home/liumanu/code/webreader
.venv/bin/python -c "
import sqlite3, json
db = sqlite3.connect('data/reader.db')
rows = db.execute('SELECT raw FROM sources WHERE user_id=3').fetchall()
out = [json.loads(r[0]) for r in rows]
json.dump(out, open('/tmp/enginetest/srcs.json','w'), ensure_ascii=False)
print('导出', len(out))
"

# 2) 依赖 jar：libs/ 里的 jsoup + rhino，外加桌面版 org.json
mkdir -p /tmp/enginetest && cd /tmp/enginetest
curl -sSLo json.jar https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar
```

## 跑对照

```bash
CP=$HOME/code/ttsnover-app/libs/jsoup-1.13.1.jar:$HOME/code/ttsnover-app/libs/rhino-1.7.14.jar:/tmp/enginetest/json.jar
cd /tmp/enginetest
javac -nowarn -encoding UTF-8 -cp "$CP" -d out \
  $HOME/code/ttsnover-app/java/com/ttsnover/app/{RuleEngine,Net,Lenient}.java \
  $HOME/code/ttsnover-app/tools/parity/stub/android/util/Base64.java \
  $HOME/code/ttsnover-app/tools/parity/Harness.java
unset http_proxy https_proxy
java -cp "out:$CP" Harness 剑来        # 输出 ok/zero/err，并写 java_results.json

python3 $HOME/code/ttsnover-app/tools/parity/node_baseline.py   # 写 node_results.json
python3 -c "
import json
n={r['name']:r for r in json.load(open('node_results.json'))}
j={r['name']:r for r in json.load(open('java_results.json'))}
d=[k for k in n if n[k].get('ok')!=j.get(k,{}).get('ok')]
print('差异源:', len(d), d)
"
```

`Deep.java` / `node_deep.py` 是同一个思路往下走一层：详情 → 目录 → 第一段正文，
比对目录章数和正文字数（`CDebug.java` 用来单看某一个源的规则求值过程）。

## 注意

- 桌面跑要一个 `android.util.Base64` 桩（`stub/` 里），Android 上用系统自带的。
- 书源本身经常改版/被封，两边跑的时间差会导致个别源结果不同；**差异要看是不是同一个源反复出现**。
- 2026-10 实测：91 源搜索命中数逐个比对，Java 与 Node **差异 0**；`@js:` 源（得间小说、阅友小说）在模拟器上 2/2 命中，Rhino 在 Android 上正常。
