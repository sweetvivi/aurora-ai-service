#!/usr/bin/env bash
#
# Read-only pre-flight for the New API endpoints used by this backend.
#
# The model catalogue is anonymous. There is deliberately no management token:
# prices, model visibility, channel availability and ordering all come from the
# public model plaza. User-scoped checks are optional and use only the user's
# own login token.
#
# Usage:
#   NEW_API_BASE_URL=http://new-api:3000 ./tools/newapi-preflight.sh
#
#   NEW_API_BASE_URL=http://new-api:3000 \
#   NEW_API_TEST_USERNAME=alice \
#   NEW_API_TEST_PASSWORD='<user password>' \
#   ./tools/newapi-preflight.sh
#
# The optional credentials are not sent to an administrative API. The script
# signs in as that user and performs one read-only access-token listing.

set -uo pipefail

BASE="${NEW_API_BASE_URL:-}"
TEST_USERNAME="${NEW_API_TEST_USERNAME:-}"
TEST_PASSWORD="${NEW_API_TEST_PASSWORD:-}"

fail=0
ok()   { printf '  \033[32mok\033[0m    %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=1; }
skip() { printf '  \033[33mskip\033[0m  %s\n' "$1"; }
note() { printf '        %s\n' "$1"; }

if [ -z "$BASE" ]; then
    echo "NEW_API_BASE_URL is not set (use the New API root URL, without /v1)" >&2
    exit 2
fi
BASE="${BASE%/}"

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

# call METHOD PATH [BEARER_TOKEN] [JSON_BODY]
# Echoes the HTTP status; the response body is written to $tmp.
call() {
    local method="$1" path="$2" token="${3:-}" body="${4:-}"
    local args=(-sS -o "$tmp" -w '%{http_code}' -X "$method" --max-time 20)
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data-binary "$body")
    curl "${args[@]}" "$BASE$path" 2>/dev/null || echo "000"
}

json_string() {
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$tmp" | head -1
}

has_json_key() {
    grep -Eq "\"$1\"[[:space:]]*:" "$tmp"
}

echo "New API read-only pre-flight against $BASE"
echo

echo "1. GET /api/pricing - model and price catalogue"
catalog_token=""
if [ -n "$CATALOG_USERNAME" ] && [ -n "$CATALOG_PASSWORD" ]; then
    body="$(printf '{"username":"%s","password":"%s"}' "$CATALOG_USERNAME" "$CATALOG_PASSWORD")"
    status="$(call POST /api/user/login '' "$body")"
    catalog_token="$(json_string access_token)"
    if [ "$status" = "200" ] && [ -n "$catalog_token" ]; then
        ok "同步账号可登录 New API"
    else
        bad "同步账号登录失败（HTTP $status）"
        note "口令加密（password-v2）开启时明文探测登不进去；用第 4 步或站点实际登录确认"
        catalog_token=""
    fi
fi
status="$(call GET /api/pricing "$catalog_token")"
if [ "$status" = "200" ] \
    && grep -Eq '"data"[[:space:]]*:[[:space:]]*\[' "$tmp" \
    && has_json_key "model_name"; then
    if [ -n "$catalog_token" ]; then
        ok "模型广场可用（同步账号登录态）；目录与价格随该账号的分组"
    else
        ok "模型广场可匿名访问；本地同步只使用该快照"
    fi
    if has_json_key "group_ratio"; then
        note "响应包含分组倍率；价格按分组倍率换算"
    else
        note "响应没有分组倍率；价格按未定价处理"
    fi
else
    if [ "$status" = "401" ] && [ -z "$catalog_token" ]; then
        bad "模型广场要求登录（HTTP 401）"
        note "关掉 New API 模型广场的「需登录」，或配置 NEW_API_CATALOG_USERNAME / NEW_API_CATALOG_PASSWORD"
    else
        bad "模型广场返回 $status，或响应中没有 data/model_name"
        note "确认 NEW_API_BASE_URL 指向 New API 根地址，且访问没有被代理拦截"
    fi
fi

echo
echo "2. GET /api/user/login/encryption-key - password transport metadata"
status="$(call GET /api/user/login/encryption-key)"
if [ "$status" = "200" ] && has_json_key "enabled"; then
    ok "登录加密配置可匿名读取"
else
    bad "登录加密配置返回 $status（期望 200 且包含 enabled）"
fi

echo
echo "3. POST /api/user/login - route availability (no account created or changed)"
status="$(call POST /api/user/login '' \
    '{"username":"preflight-probe-does-not-exist","password":"not-a-real-password"}')"
if [ "$status" = "200" ]; then
    ok "登录路由存活；错误凭据由响应体表达"
else
    bad "登录路由返回 $status（期望 200）"
    note "本站即时开户和用户自有凭据登录依赖该路由"
fi

if [ -z "$TEST_USERNAME" ] || [ -z "$TEST_PASSWORD" ]; then
    echo
    skip "用户级只读检查（设置 NEW_API_TEST_USERNAME 和 NEW_API_TEST_PASSWORD 可启用）"
else
    echo
    echo "4. POST /api/user/login - current user's own credentials"
    body="$(printf '{"username":"%s","password":"%s"}' "$TEST_USERNAME" "$TEST_PASSWORD")"
    status="$(call POST /api/user/login '' "$body")"
    login_token="$(json_string access_token)"
    if [ "$status" = "200" ] && [ -n "$login_token" ]; then
        ok "用户凭据可登录 New API"

        echo
        echo "5. GET /api/user/access_tokens - current user's login token, read only"
        status="$(call GET /api/user/access_tokens "$login_token")"
        if [ "$status" = "200" ] && grep -Eq '"items"[[:space:]]*:[[:space:]]*\[' "$tmp"; then
            ok "当前用户的 access token 列表可读取"
        else
            bad "access token 列表返回 $status，或响应中没有 items"
        fi
    else
        bad "用户登录失败（HTTP $status，或响应中没有 access_token）"
        note "这里只使用用户自己的凭据，不使用 NEW_API_ACCESS_TOKEN"
    fi
fi

echo
if [ "$fail" = 0 ]; then
    echo "结果：通过"
else
    echo "结果：存在失败项（见 docs/newapi-deployment-checklist.md）"
fi
exit "$fail"
