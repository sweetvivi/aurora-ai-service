#!/usr/bin/env bash
# 检查 Flyway 迁移的版本号是否唯一。
#
# 为什么要有这个脚本：两个分支各自新增 V98 时，git 合并**不会有任何提示** ——
# 两个文件都在，名字不同，冲突只发生在版本号上。等应用启动才炸，报的还是
# 「Found more than one migration with version 98」，而此时可能已经在部署流程里了。
#
# 用法：
#   tools/check-migrations.sh                 # 只查本地是否有重号
#   tools/check-migrations.sh origin/main     # 再把本地版本号与该引用比对（拉取后必跑）
#
# 退出码：0 无冲突（可能有告警）；1 有重号；2 用法错误。

set -u

DIR="src/main/resources/db/migration"
[ -d "$DIR" ] || { echo "找不到 $DIR，请在仓库根目录执行" >&2; exit 2; }

fail=0

# 1) 本地重号
dups=$(find "$DIR" -maxdepth 1 -type f -name 'V*__*.sql' -printf '%f\n' \
       | sed -E 's/^(V[0-9]+)__.*/\1/' | sort -V | uniq -d)
if [ -n "$dups" ]; then
    echo "✗ 本地存在重复的迁移版本号：" >&2
    for v in $dups; do
        echo "   $v" >&2
        find "$DIR" -maxdepth 1 -type f -name "${v}__*.sql" -printf '     - %f\n' >&2
    done
    fail=1
fi

# 2) 与远端引用比对：同一个版本号两边各有一个文件，就是将来合并后的重号
if [ $# -ge 1 ]; then
    REF="$1"
    if ! git rev-parse --verify --quiet "$REF" >/dev/null; then
        echo "找不到引用 $REF（先 git fetch）" >&2
        exit 2
    fi

    tmp_local=$(mktemp)
    tmp_remote=$(mktemp)
    find "$DIR" -maxdepth 1 -type f -name 'V*__*.sql' -printf '%f\n' | sort > "$tmp_local"
    git ls-tree --name-only "$REF" "$DIR/" | sed 's#.*/##' | grep -E '^V[0-9]+__.*\.sql$' | sort > "$tmp_remote"

    # 只在「版本号相同、文件名不同」时报警：完全相同说明是同一份，无害。
    collisions=$(join -j1 \
        <(sed -E 's/^(V[0-9]+)__.*/\1 &/' "$tmp_local" | sort -k1,1) \
        <(sed -E 's/^(V[0-9]+)__.*/\1 &/' "$tmp_remote" | sort -k1,1) \
        | awk '$2 != $3 {print $1"  本地:"$2"  远端:"$3}')
    if [ -n "$collisions" ]; then
        echo "✗ 与 $REF 存在版本号冲突（合并后 Flyway 会直接拒绝启动）：" >&2
        echo "$collisions" | sed 's/^/   /' >&2
        echo "   处理：本地这份顺延到该引用最大值之后，别改已执行过的版本。" >&2
        fail=1
    fi

    # 只提示，不算失败：本地落后于远端是正常状态。
    behind=$(comm -13 <(sed -E 's/^(V[0-9]+)__.*/\1/' "$tmp_local" | sort -V) \
                     <(sed -E 's/^(V[0-9]+)__.*/\1/' "$tmp_remote" | sort -V) | tr '\n' ' ')
    [ -n "${behind// /}" ] && echo "提示：$REF 上有本地没有的版本：$behind"

    rm -f "$tmp_local" "$tmp_remote"
fi

if [ "$fail" -eq 0 ]; then
    echo "✓ 迁移版本号无冲突"
fi
exit "$fail"
