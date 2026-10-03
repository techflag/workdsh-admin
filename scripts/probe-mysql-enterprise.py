import argparse,subprocess,secrets,time,os,re
from pathlib import Path
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--image',required=True);args=parser.parse_args()
if not re.fullmatch(r'[A-Za-z0-9./:_-]+@sha256:[a-f0-9]{64}',args.image):raise ValueError('Explicit already-installed immutable MySQL image required')
name='workdsh-mysql-check-'+secrets.token_hex(4);password=secrets.token_urlsafe(32)
def run(args,**kw):
 result=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kw)
 if result.returncode:raise RuntimeError(result.stderr.decode().replace(password,'[redacted]'))
 return result
try:
 run(['docker','run','--pull=never','-d','--name',name,'--label','workdsh.test=current-database','-e','MYSQL_ROOT_PASSWORD','-e','MYSQL_DATABASE=workdsh_test','-p','127.0.0.1::3306',args.image],env=dict(os.environ,MYSQL_ROOT_PASSWORD=password))
 for i in range(60):
  probe=subprocess.run(['docker','exec',name,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "select 1" workdsh_test'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  if probe.returncode==0:break
  time.sleep(1)
 else:raise RuntimeError('MySQL startup timeout')
 print('MySQL test server ready',flush=True)
 schema=(root/'server/src/main/resources/schema-mysql.sql').read_text()
 sql_command=['docker','exec','-i',name,'sh','-c','MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot -N workdsh_test']
 run(sql_command,input=schema.encode())
 print('Current MySQL new schema initialized',flush=True)
 port=run(['docker','port',name,'3306/tcp']).stdout.decode().strip().split(':')[-1]
 env=dict(os.environ,WORKDSH_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:'+port+'/workdsh_test?allowPublicKeyRetrieval=true&useSSL=false',WORKDSH_TEST_MYSQL_PASSWORD=password)
 result=subprocess.run(['mvn','-q','-Dtest=MysqlEnterpriseTest','test'],cwd=root/'server',env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 if result.returncode:raise RuntimeError(result.stdout.decode().replace(password,'[redacted]')[-6500:])
 print('MySQL integration test passed',flush=True)
finally:
 subprocess.run(['docker','rm','-f','-v',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
