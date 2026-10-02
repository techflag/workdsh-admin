"""Closed-list admin delivery; native DSH processes belong to workdsh/deploy/member-process."""
import argparse, hashlib, json, pathlib, zipfile
ROOT = pathlib.Path(__file__).resolve().parents[1]
KIND = 'workdsh-admin-server-candidate'
FIXED = {
    'server/workdsh-admin-server.jar': 'server/target/workdsh-admin-server-0.1.0-SNAPSHOT.jar',
    'deploy/docker/Admin.Dockerfile': 'deploy/docker/Admin.Dockerfile',
    'deploy/docker/AdminFront.Dockerfile': 'deploy/docker/AdminFront.Dockerfile',
    'deploy/docker/admin-loopback-front.mjs': 'deploy/docker/admin-loopback-front.mjs',
    'deploy/docker/user-entry.html': 'deploy/docker/user-entry.html',
    'deploy/docker/shared-entry-client.js': 'deploy/docker/shared-entry-client.js',
    'deploy/docker/account-storage-fence.mjs': 'deploy/docker/account-storage-fence.mjs',
    'scripts/migrate-postgres.py': 'scripts/migrate-postgres.py',
    'deploy/postgres/runtime-grants.sql': 'deploy/postgres/runtime-grants.sql',
    'deploy/mysql/schema.sql': 'server/src/main/resources/schema-mysql.sql',
    'deploy/sqlite/schema.sql': 'server/src/main/resources/schema-sqlite.sql',
    'deploy/postgres/README.md': 'deploy/postgres/README.md',
    'docs/ENTERPRISE-DATABASES.md': 'docs/ENTERPRISE-DATABASES.md',
    'README.md': 'deploy/ADMIN-SERVER-CANDIDATE.md',
}

def build_archive(output, root=ROOT):
    root = pathlib.Path(root).resolve(); output = pathlib.Path(output).absolute()
    if output.exists() or output.is_symlink(): raise ValueError('Output exists; refusing overwrite')
    files = {}
    def include(name, path):
        path = pathlib.Path(path)
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root):
            raise ValueError('Expected regular candidate input inside repository: ' + name)
        files[name] = path
    for name, source in FIXED.items(): include(name, root/source)
    for folder in ['web/dist', 'deploy/postgres/migrations']:
        source = root/folder
        if source.is_symlink() or not source.is_dir(): raise ValueError('Missing build input: ' + folder)
        for path in sorted(source.rglob('*')):
            if path.is_symlink(): raise ValueError('Symlink in candidate assets refused')
            if path.is_file(): include(folder + '/' + path.relative_to(source).as_posix(), path)
    if 'web/dist/index.html' not in files: raise ValueError('Build admin Web before packaging')
    blobs = {name: path.read_bytes() for name, path in files.items()}
    manifest = {'kind': KIND, 'personalDefaultChanged': False, 'memberRuntimeIncluded': False,
        'nativeProcessSource': 'workdsh/deploy/member-process',
        'requires': {'java': 17, 'node': '22.19+ or 24+', 'database': 'explicit deployment configuration'},
        'files': {name: {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)} for name, data in sorted(blobs.items())}}
    blobs['manifest.json'] = json.dumps(manifest, ensure_ascii=False, indent=2).encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('xb') as stream:
        with zipfile.ZipFile(stream, 'w', zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(blobs.items()):
                item = zipfile.ZipInfo(name, date_time=(2026,10,2,0,0,0)); item.external_attr = 0o100644 << 16
                item.compress_type = zipfile.ZIP_DEFLATED; archive.writestr(item, data)
    return {'output': str(output), 'sha256': hashlib.sha256(output.read_bytes()).hexdigest(), 'entries': len(blobs), 'published': False}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--output', required=True); args = parser.parse_args()
    print(json.dumps(build_archive(args.output)))
