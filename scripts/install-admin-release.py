"""Verify an explicit candidate and atomically install into a new version directory."""
import argparse,hashlib,io,json,os,pathlib,shutil,tempfile,zipfile

def install(archive,destination):
 archive=pathlib.Path(archive).resolve();destination=pathlib.Path(destination).absolute()
 if destination.exists() or destination.is_symlink():raise ValueError('Destination already exists; refusing overwrite')
 destination.parent.mkdir(parents=True,exist_ok=True)
 staging=pathlib.Path(tempfile.mkdtemp(prefix='.workdsh-install-',dir=destination.parent))
 try:
  archive_bytes=archive.read_bytes()
  with zipfile.ZipFile(io.BytesIO(archive_bytes)) as z:
   names=z.namelist()
   if len(names)!=len(set(names)):raise ValueError('Duplicate archive entries')
   manifest=json.loads(z.read('manifest.json'))
   if manifest.get('kind')!='workdsh-admin-server-candidate':raise ValueError('Unsupported candidate kind')
   if manifest.get('memberRuntimeIncluded') is not False or manifest.get('personalDefaultChanged') is not False:raise ValueError('Unsupported delivery boundary')
   if set(names)!=set(manifest['files'])|{'manifest.json'}:raise ValueError('Archive differs from closed manifest')
   for info in z.infolist():
    name=info.filename;parts=pathlib.PurePosixPath(name)
    if parts.is_absolute() or '..' in parts.parts or '\\' in name or info.is_dir():raise ValueError('Unsafe archive path')
    if (info.external_attr>>16)&0o170000 not in (0,0o100000):raise ValueError('Nonregular archive entry')
    target=staging/name
    if not target.resolve().is_relative_to(staging.resolve()):raise ValueError('Archive path escapes installation')
    data=z.read(info)
    if name!='manifest.json':
     row=manifest['files'][name]
     if len(data)!=row['bytes'] or hashlib.sha256(data).hexdigest()!=row['sha256']:raise ValueError('Candidate checksum or byte count mismatch')
    target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data);target.chmod(0o644)
  # A destination created concurrently must not be replaced, including an empty directory.
  if destination.exists() or destination.is_symlink():raise ValueError('Destination changed during verification')
  # mkdir reserves the name; rename into that owned empty directory has atomic visibility on POSIX.
  destination.mkdir(mode=0o700)
  try:os.replace(staging,destination)
  except BaseException:destination.rmdir();raise
  return {'archiveSha256':hashlib.sha256(archive_bytes).hexdigest(),'directory':str(destination),'filesVerified':len(names),'personalProfileModified':False,'servicesStarted':False}
 finally:
  if staging.exists():shutil.rmtree(staging)

if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('archive');parser.add_argument('destination');args=parser.parse_args()
 print(json.dumps(install(args.archive,args.destination)))
