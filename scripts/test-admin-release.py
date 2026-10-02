"""Closed-list delivery, installation and corrupt-input checks in a fresh temporary tree."""
import hashlib, importlib.util, json, pathlib, tempfile, unittest, zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path); value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
packer=module('admin_packer',ROOT/'scripts/pack-admin-release.py')
installer=module('admin_installer',ROOT/'scripts/install-admin-release.py')

class AdminReleaseTest(unittest.TestCase):
    def prepare(self,base):
        for source in packer.FIXED.values():
            path=base/source;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('candidate fixture')
        for folder in ['web/dist','deploy/postgres/migrations']:(base/folder).mkdir(parents=True,exist_ok=True)
        (base/'web/dist/index.html').write_text('<!doctype html><title>Admin fixture</title>')
        (base/'.env').write_text('MUST_NOT_BE_PACKAGED')
        (base/'.test-runtime').mkdir();(base/'.test-runtime/private.json').write_text('MUST_NOT_BE_PACKAGED')
    def test_closed_archive_is_installed_and_corruption_refused(self):
        with tempfile.TemporaryDirectory(prefix='workdsh-admin-release-') as td:
            base=pathlib.Path(td)/'source';base.mkdir();self.prepare(base);archive=pathlib.Path(td)/'admin.zip';packer.build_archive(archive,base)
            with zipfile.ZipFile(archive) as z:
                blobs={n:z.read(n) for n in z.namelist()};manifest=json.loads(blobs['manifest.json'])
            self.assertEqual(packer.KIND,manifest['kind']);self.assertFalse(manifest['memberRuntimeIncluded'])
            self.assertEqual(set(manifest['files'])|{'manifest.json'},set(blobs));self.assertFalse(any('gateway.mjs' in n or '.env' in n or '.test-runtime' in n for n in blobs))
            target=pathlib.Path(td)/'installed';result=installer.install(archive,target)
            self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(),result['archiveSha256']);self.assertTrue((target/'server/workdsh-admin-server.jar').is_file())
            with self.assertRaises(ValueError):installer.install(archive,target)
            blobs['server/workdsh-admin-server.jar']+=b'corrupt';bad=pathlib.Path(td)/'bad.zip'
            with zipfile.ZipFile(bad,'w') as z:
                for name,data in blobs.items():z.writestr(name,data)
            with self.assertRaises(ValueError):installer.install(bad,pathlib.Path(td)/'corrupt')
            self.assertFalse((pathlib.Path(td)/'corrupt').exists());self.assertFalse(list(pathlib.Path(td).glob('.workdsh-install-*')))
    def test_symlink_and_existing_output_are_refused(self):
        with tempfile.TemporaryDirectory(prefix='workdsh-admin-release-') as td:
            base=pathlib.Path(td)/'source';base.mkdir();self.prepare(base);archive=pathlib.Path(td)/'admin.zip';archive.write_bytes(b'existing')
            with self.assertRaises(ValueError):packer.build_archive(archive,base)
            archive.unlink();(base/'web/dist/outside').symlink_to('/etc/hosts')
            with self.assertRaises(ValueError):packer.build_archive(archive,base)
            self.assertFalse(archive.exists())
if __name__=='__main__':unittest.main()
