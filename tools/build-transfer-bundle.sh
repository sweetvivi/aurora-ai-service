#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="${HUB_DEPLOY_ROOT:-/vol1/1000/hub-test}"
RUN_ID="${1:-$(date +%Y%m%d-%H%M%S)}"
INPUT_DIR="${TRANSFER_INPUT_DIR:-$ROOT/.transfer-input}"
STAGE="$ROOT/hub-test-transfer-$RUN_ID"
ARCHIVE="$ROOT/hub-test-frontend-backend-$RUN_ID.tar.gz"

umask 077

[[ "$RUN_ID" =~ ^[0-9A-Za-z._-]+$ ]] || {
    echo "Invalid run id: $RUN_ID" >&2
    exit 2
}

ROOT="$(readlink -f "$ROOT")"
case "$ROOT" in
    /vol1/*) ;;
    *)
        echo "Refusing to package outside /vol1: $ROOT" >&2
        exit 2
        ;;
esac

[[ -f "$ROOT/.env" ]] || { echo "Missing $ROOT/.env" >&2; exit 1; }
[[ -f "$ROOT/docker-compose.yml" ]] || { echo "Missing $ROOT/docker-compose.yml" >&2; exit 1; }
[[ -f "$INPUT_DIR/docker-compose.yml" ]] || { echo "Missing transfer compose template" >&2; exit 1; }
[[ -f "$INPUT_DIR/env.example" ]] || { echo "Missing transfer env example" >&2; exit 1; }
[[ -f "$INPUT_DIR/NEWAPI-需要修改的地方.md" ]] || { echo "Missing New API checklist" >&2; exit 1; }
[[ -f "$INPUT_DIR/RESTORE.md" ]] || { echo "Missing restore guide" >&2; exit 1; }
[[ ! -e "$STAGE" ]] || { echo "Staging directory already exists: $STAGE" >&2; exit 1; }
[[ ! -e "$ARCHIVE" ]] || { echo "Archive already exists: $ARCHIVE" >&2; exit 1; }

set -a
source "$ROOT/.env"
set +a

mysql_image="${MYSQL_IMAGE:-mysql:8.4}"
FRONTEND_VERSION="$(sed -n 's/^FRONTEND_VERSION=//p' "$ROOT/deploy.env" | tail -1)"
BACKEND_VERSION="$(sed -n 's/^BACKEND_VERSION=//p' "$ROOT/deploy.env" | tail -1)"
SOURCE_ROOT="$(readlink -f "$ROOT/current")"

[[ -n "$FRONTEND_VERSION" && -n "$BACKEND_VERSION" ]] || {
    echo "Missing release versions in $ROOT/deploy.env" >&2
    exit 1
}

mkdir -p "$STAGE"/{source,db,data,images,tls}

printf 'Copying source trees from %s\n' "$SOURCE_ROOT"
cp -a "$SOURCE_ROOT/web-app" "$STAGE/source/"
cp -a "$SOURCE_ROOT/backend-service" "$STAGE/source/"

find "$STAGE/source" -type d \( \
    -name node_modules -o \
    -name target -o \
    -name dist -o \
    -name .git -o \
    -name .codex-logs \
\) -prune -exec rm -rf -- {} +
find "$STAGE/source" -type f \( \
    -name '*.log' -o \
    -name '.codex-*' \
\) -delete

printf 'Copying deployment configuration\n'
install -m 600 "$ROOT/.env" "$STAGE/.env"
install -m 600 "$INPUT_DIR/env.example" "$STAGE/.env.example"
install -m 644 "$INPUT_DIR/docker-compose.yml" "$STAGE/docker-compose.yml"
install -m 600 "$INPUT_DIR/NEWAPI-需要修改的地方.md" "$STAGE/NEWAPI-需要修改的地方.md"
install -m 600 "$INPUT_DIR/RESTORE.md" "$STAGE/RESTORE.md"

if [[ -d "$ROOT/tls" ]]; then
    cp -a "$ROOT/tls/." "$STAGE/tls/"
    chmod 700 "$STAGE/tls"
    chmod 600 "$STAGE/tls"/* 2>/dev/null || true
fi

python3 - "$STAGE/.env" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
lines = path.read_text(encoding="utf-8").splitlines()
drop_prefixes = (
    "NEW_API_IMAGE=",
    "NEW_API_PORT=",
    "NEW_API_SESSION_SECRET=",
    "NEW_API_CRYPTO_SECRET=",
    "NEW_API_ADMIN_USERNAME=",
    "NEW_API_ADMIN_PASSWORD=",
    "NEW_API_ONBOARDING_SECRET=",
    "NEW_API_ACCESS_TOKEN=",
    "NEW_API_PASSWORD_SYNC_ENABLED=",
    "NEW_API_NAME_CLAIM_ENABLED=",
    "NEW_API_REVERSE_PASSWORD_SYNC_ENABLED=",
    "NEW_API_REVERSE_PASSWORD_SYNC_ATTEMPTS=",
    "NEW_API_REVERSE_SYNC_GLOBAL_ATTEMPTS=",
    "NEW_API_CATALOG_SYNC_WRITE_BACK=",
    "AUTH_NEW_API_PASSWORD_AUTHORITY=",
    "FRONTEND_BOOTSTRAP_PORT=",
    "SMOKE_USERNAME=",
    "SMOKE_PASSWORD=",
)
kept = [line for line in lines if not line.startswith(drop_prefixes)]
overrides = {
    "NEW_API_BASE_URL": "http://replace-with-newapi-host:3000",
    "LLM_BASE_URL": "http://replace-with-newapi-host:3000/v1",
    "FRONTEND_VERSION": None,
    "BACKEND_VERSION": None,
}

seen = set()
result = []
for line in kept:
    key = line.split("=", 1)[0] if "=" in line else None
    if key in overrides:
        value = overrides[key]
        if value is not None:
            result.append(f"{key}={value}")
        seen.add(key)
    else:
        result.append(line)

for key, value in overrides.items():
    if key not in seen and value is not None:
        result.append(f"{key}={value}")

path.write_text("\n".join(result) + "\n", encoding="utf-8")
PY

sed -i \
    -e "s/^FRONTEND_VERSION=.*/FRONTEND_VERSION=$FRONTEND_VERSION/" \
    -e "s/^BACKEND_VERSION=.*/BACKEND_VERSION=$BACKEND_VERSION/" \
    "$STAGE/.env"
chmod 600 "$STAGE/.env"

printf 'Dumping application MySQL database\n'
docker exec \
    -e "MYSQL_PWD=$MYSQL_PASSWORD" \
    hub-test-mysql \
    mysqldump \
    --host=127.0.0.1 \
    --user="$MYSQL_USER" \
    --single-transaction \
    --quick \
    --routines \
    --triggers \
    --events \
    --no-tablespaces \
    --set-gtid-purged=OFF \
    "$MYSQL_DATABASE" \
    | gzip -1 > "$STAGE/db/hub_test.sql.gz"

printf 'Archiving application uploads\n'
docker run --rm \
    -v hub-test_uploads:/src:ro \
    -v "$STAGE/data:/out" \
    "$mysql_image" \
    sh -c 'tar -C /src -czf /out/uploads.tar.gz .'

if docker volume inspect hub-test_public >/dev/null 2>&1; then
    printf 'Archiving public media\n'
    docker run --rm \
        -v hub-test_public:/src:ro \
        -v "$STAGE/data:/out" \
        "$mysql_image" \
        sh -c 'tar -C /src -czf /out/public-assets.tar.gz .'
fi

printf 'Saving frontend and backend Docker images\n'
docker save \
    "hub-test-backend:$BACKEND_VERSION" \
    "hub-test-frontend:$FRONTEND_VERSION" \
    | gzip -1 > "$STAGE/images/hub-test-images.tar.gz"

if [[ -f "$ROOT/image-export/mysql-8.4.tar.gz" ]]; then
    cp "$ROOT/image-export/mysql-8.4.tar.gz" "$STAGE/images/mysql-8.4.tar.gz"
else
    docker save "$mysql_image" | gzip -1 > "$STAGE/images/mysql-8.4.tar.gz"
fi

backend_digest="$(docker image inspect "hub-test-backend:$BACKEND_VERSION" --format '{{.Id}}')"
frontend_digest="$(docker image inspect "hub-test-frontend:$FRONTEND_VERSION" --format '{{.Id}}')"
mysql_digest="$(docker image inspect "$mysql_image" --format '{{.Id}}')"

{
    printf 'created_at=%s\n' "$(date -Iseconds)"
    printf 'source_host=%s\n' "$(hostname)"
    printf 'source_release=%s\n' "$SOURCE_ROOT"
    printf 'frontend_version=%s\n' "$FRONTEND_VERSION"
    printf 'backend_version=%s\n' "$BACKEND_VERSION"
    printf 'frontend_image_id=%s\n' "$frontend_digest"
    printf 'backend_image_id=%s\n' "$backend_digest"
    printf 'mysql_image_id=%s\n' "$mysql_digest"
    printf 'package_scope=frontend,backend,mysql-dump,uploads,deployment-config\n'
    printf 'excluded=new-api-image,new-api-source,new-api-data-volume\n'
} > "$STAGE/MANIFEST.txt"

printf 'Generating checksums\n'
find "$STAGE" -type f ! -name SHA256SUMS -printf '%P\0' \
    | sort -z \
    | while IFS= read -r -d '' file; do
        (cd "$STAGE" && sha256sum "$file")
    done > "$STAGE/SHA256SUMS"

printf 'Creating archive\n'
tar -C "$ROOT" -czf "$ARCHIVE" "hub-test-transfer-$RUN_ID"

printf 'Bundle: %s\n' "$ARCHIVE"
printf 'Size: %s\n' "$(du -h "$ARCHIVE" | cut -f1)"
printf 'SHA256: %s\n' "$(sha256sum "$ARCHIVE" | cut -d' ' -f1)"
