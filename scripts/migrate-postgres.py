"""Explicit maintenance-only migrations; credentials come from psql environment."""
import argparse
import hashlib
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--emit-sql', action='store_true', help='Emit deterministic maintenance SQL without connecting')
args = parser.parse_args()
parts = [r'''\set ON_ERROR_STOP on
BEGIN;
SELECT pg_advisory_xact_lock(1869373293, 1);
DO $$ BEGIN
 IF to_regclass('public.workdsh_schema_history') IS NULL AND EXISTS (
   SELECT 1 FROM pg_tables WHERE schemaname='public'
 ) THEN
   RAISE EXCEPTION 'Existing unversioned database requires reviewed baseline; automatic adoption refused';
 END IF;
END $$;
CREATE TABLE IF NOT EXISTS public.workdsh_schema_history (
 version integer PRIMARY KEY, filename text NOT NULL, sha256 varchar(64) NOT NULL,
 installed_at timestamptz NOT NULL DEFAULT now()
);
''']
for file in sorted((root/'deploy/postgres/migrations').glob('V[0-9][0-9][0-9]__*.sql')):
    version = int(file.name[1:4])
    digest = hashlib.sha256(file.read_bytes()).hexdigest()
    assert "'" not in file.name
    parts.append(f"""DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM public.workdsh_schema_history WHERE version={version} AND (sha256<>'{digest}' OR filename<>'{file.name}')) THEN
  RAISE EXCEPTION 'Applied migration {version} checksum differs; migration refused';
 END IF;
END $$;
SELECT NOT EXISTS (SELECT 1 FROM public.workdsh_schema_history WHERE version={version}) AS apply_migration \\gset
\\if :apply_migration
{file.read_text()}
INSERT INTO public.workdsh_schema_history(version,filename,sha256) VALUES ({version},'{file.name}','{digest}');
\\endif
""")
parts.append('COMMIT;\n')
sql = '\n'.join(parts)
if args.emit_sql:
    print(sql, end='')
else:
    # psql uses PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSFILE; never interpolate a secret.
    subprocess.run(['psql', '-X', '-v', 'ON_ERROR_STOP=1'], input=sql, text=True, check=True)
