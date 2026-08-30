import subprocess, re, sys, os

# 匹配全局配置文件（触发全量构建）
global_change = re.compile(r"^(?:(?:build|settings)\.gradle(?:\.kts)?|gradle\.properties|\.github\/.+|\.gradle\/.+)$")
# 匹配任意子插件目录下的任意文件修改：例如 AITranslate/...
plugin_change = re.compile(r"^([^/]+)/.+$")

sha = sys.argv[1]

# 尝试比对当前提交与上一个版本
try:
    cmd = ["git", "diff", "--name-only", "HEAD~1", "HEAD"]
    out = subprocess.run(cmd, stdout=subprocess.PIPE, cwd=f"{os.environ['GITHUB_WORKSPACE']}/src").stdout.strip(b'\n').decode("utf-8")
except Exception:
    out = ""

matched = ""
found = set()

for file in out.split("\n"):
    file = file.strip()
    if not file:
        continue
    
    # 命中全局改动 -> 构建所有插件
    if global_change.match(file):
        print("make generateUpdaterJson --no-daemon")
        sys.exit(0)

    # 命中子目录改动
    plugin_match = plugin_change.match(file)
    if plugin_match:
        name = plugin_match.group(1)
        # 排除掉根目录隐藏文件夹或非插件目录（如 gradle）
        if not name.startswith(".") and name not in ["gradle", "build"] and name not in found:
            matched += f":{name}:make "
            found.add(name)

# 如果检测到具体插件变动，只编译变动的插件；否则兜底全量构建 make
if matched:
    print(f"{matched}generateUpdaterJson --no-daemon")
else:
    print("make generateUpdaterJson --no-daemon")