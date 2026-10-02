"""Isolated packaged-server smoke: fake account, memory H2, loopback, no supplier calls."""
import argparse, http.client, json, os, pathlib, shutil, socket, subprocess, tempfile, time

ROOT = pathlib.Path(__file__).resolve().parents[1]

def smoke(java, jar, web, log):
    if not jar.is_file() or not (web/'index.html').is_file():
        raise ValueError('Build or install the admin release before this smoke')
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
    origin = f'http://127.0.0.1:{port}'
    email, password = 'packaged-smoke@example.test', 'PackagedSmokeFixture123!'
    # Ambient credentials and JVM hooks must not enter the isolated test process.
    env = {name: os.environ[name] for name in ['PATH', 'LANG', 'LC_ALL', 'TZ', 'SYSTEMROOT'] if name in os.environ}
    with tempfile.TemporaryDirectory(prefix='workdsh-admin-packaged-') as directory, log.open('w') as output:
        process = subprocess.Popen([java, '-jar', str(jar), f'--server.port={port}', '--server.address=127.0.0.1',
            '--spring.datasource.url=jdbc:h2:mem:packaged-smoke;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1',
            f'--workdsh.web.origin={origin}', f'--workdsh.bootstrap.admin-email={email}',
            f'--workdsh.bootstrap.admin-password={password}',
            f'--spring.web.resources.static-locations=file:{web}/'], cwd=directory, env=env,
            stdout=output, stderr=subprocess.STDOUT)
        def request(path, method='GET', body=None, authorization=None):
            connection = http.client.HTTPConnection('127.0.0.1', port, timeout=1)
            try:
                headers = {'Content-Type': 'application/json'}
                if authorization: headers['Authorization'] = authorization
                connection.request(method, path, json.dumps(body) if body is not None else None, headers)
                response = connection.getresponse()
                return response.status, response.read()
            finally: connection.close()
        try:
            # Tomcat accepts requests before ApplicationRunner finishes the bootstrap account.
            deadline = time.monotonic() + 30; login = None
            while time.monotonic() < deadline and process.poll() is None:
                try:
                    status, content = request('/api/auth/login', 'POST', {'email': email, 'password': password})
                    if status == 200: login = json.loads(content); break
                    if status != 401: raise AssertionError(f'Unexpected startup login status: {status}')
                except (OSError, http.client.HTTPException): pass
                time.sleep(.1)
            assert login and login.get('token'), 'Packaged admin login did not become ready; inspect private smoke log'
            authorization = 'Bearer ' + login['token']
            assert request('/api/auth/me')[0] == 401
            status, page = request('/')
            assert status == 200 and b'<html' in page.lower(), 'Packaged frontend was not served'
            status, account = request('/api/auth/me', authorization=authorization)
            assert status == 200 and json.loads(account)['email'] == email
            assert request('/api/admin/members', authorization=authorization)[0] == 200
            for path in ['/api/orders', '/api/admin/orders', '/api/reviews/inbox', '/api/internal/runtime/identity', '/api/admin/model-config']:
                assert request(path, authorization=authorization)[0] == 404, 'Retired route remains: ' + path
            assert request('/api/model-gateway/v1/models')[0] == 401
            assert request('/api/collaboration/inbox', authorization='Runtime obsolete-fixture')[0] == 401
            assert request('/api/auth/logout', 'POST', authorization=authorization)[0] == 204
            assert request('/api/auth/me', authorization=authorization)[0] == 401
            return {'packagedServer': True, 'staticWeb': True, 'memberLoginAndLogout': True,
                'retiredRoutes404': True, 'runtimeCredentialRejected': True, 'modelCalls': 0}
        finally:
            if process.poll() is None: process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill(); process.wait()

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--release-root', type=pathlib.Path)
    parser.add_argument('--log', type=pathlib.Path, default=pathlib.Path(tempfile.gettempdir())/'workdsh-admin-packaged-smoke.log')
    args = parser.parse_args()
    if not args.java: raise ValueError('Java 17+ is required')
    base = args.release_root.resolve() if args.release_root else ROOT
    jar = base/'server/workdsh-admin-server.jar' if args.release_root else base/'server/target/workdsh-admin-server-0.1.0-SNAPSHOT.jar'
    print(json.dumps(smoke(args.java, jar, base/'web/dist', args.log)))
