#!/bin/sh
# PostgreSQL 首次初始化脚本（仅在数据卷为空时由 postgres 官方镜像执行）。
# 对应 scripts/migrate-postgres.py + deploy/postgres/README.md 的维护流程：
# 创建最小权限运行角色 -> 按版本应用 migrations -> 写入 schema history -> 授予运行权限。
# 迁移文件名带单引号、已应用迁移校验不一致或任何 SQL 失败时立即中止，容器不会带着半初始化库启动。
set -eu

app_user="workdsh_app"
migrations_dir="/migrations"
grants_file="/runtime-grants.sql"
migration_sql="/tmp/workdsh-migration.sql"

log() { printf '[workdsh-init] %s\n' "$*" >&2; }

: "${POSTGRES_USER:?POSTGRES_USER 未设置}"
: "${POSTGRES_DB:?POSTGRES_DB 未设置}"

psql_admin() {
  psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" "$@"
}

log "创建/更新运行角色 ${app_user}"
psql_admin <<'SQL'
\getenv app_password WORKDSH_DB_APP_PASSWORD
\if :{?app_password}
\else
  \echo 'WORKDSH_DB_APP_PASSWORD 未设置，拒绝初始化'
  \quit 1
\endif
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', 'workdsh_app', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'workdsh_app')
\gexec
SELECT format('ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD %L', 'workdsh_app', :'app_password')
WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'workdsh_app')
\gexec
SQL

log "初始化 schema history"
psql_admin <<'SQL'
DO $$ BEGIN
 IF to_regclass('public.workdsh_schema_history') IS NULL AND EXISTS (
   SELECT 1 FROM pg_tables WHERE schemaname = 'public'
 ) THEN
   RAISE EXCEPTION 'Existing unversioned database requires reviewed baseline; automatic adoption refused';
 END IF;
END $$;
CREATE TABLE IF NOT EXISTS public.workdsh_schema_history (
  version integer PRIMARY KEY,
  filename text NOT NULL,
  sha256 varchar(64) NOT NULL,
  installed_at timestamptz NOT NULL DEFAULT now()
);
SQL

found=0
for migration in "$migrations_dir"/V[0-9][0-9][0-9]__*.sql; do
  [ -f "$migration" ] || continue
  found=1

  name=$(basename "$migration")
  case "$name" in
    *"'"*)
      log "迁移文件名包含单引号，拒绝: $name"
      exit 1
      ;;
  esac

  version=$(printf '%s' "$name" | cut -c2-4 | sed 's/^0*//')
  version=${version:-0}
  digest=$(sha256sum "$migration" | cut -d ' ' -f1)

  applied=$(psql_admin -tA -c "SELECT sha256 || ' ' || filename FROM public.workdsh_schema_history WHERE version = ${version}")
  if [ -n "$applied" ]; then
    if [ "$applied" != "${digest} ${name}" ]; then
      log "已应用迁移校验不一致，拒绝继续: ${name}"
      exit 1
    fi
    log "跳过已应用迁移 ${name}"
    continue
  fi

  log "应用迁移 ${name}"
  {
    printf 'BEGIN;\n'
    cat "$migration"
    printf "\nINSERT INTO public.workdsh_schema_history(version, filename, sha256) VALUES (%s, '%s', '%s');\nCOMMIT;\n" \
      "$version" "$name" "$digest"
  } > "$migration_sql"
  psql_admin -f "$migration_sql"
  rm -f "$migration_sql"
done

if [ "$found" -eq 0 ]; then
  log "未在 ${migrations_dir} 找到迁移文件，拒绝初始化"
  exit 1
fi

log "授予运行角色权限"
psql_admin -f "$grants_file"

log "初始化完成"
