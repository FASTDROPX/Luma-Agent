#!/usr/bin/env python3
"""Align/sign an existing release APK and export a source archive without local data."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, zipfile
ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--sdk', type=Path)
p.add_argument('--keystore', type=Path)
p.add_argument('--alias', default='luma')
a = p.parse_args()
if a.sdk:
    sdk = a.sdk
else:
    properties = dict(line.split('=',1) for line in (ROOT/'local.properties').read_text().splitlines() if '=' in line)
    sdk = Path(properties['sdk.dir'])
kit = sdk/'build-tools/35.0.0'
store = a.keystore or Path.home()/'.cache/luma-signing/development.p12'
store.parent.mkdir(parents=True,exist_ok=True)
env = os.environ.copy()
if not os.environ.get('LUMA_SIGN_PASS'):
    raise SystemExit('Set LUMA_SIGN_PASS to the signing key password.')
env['LD_LIBRARY_PATH'] = str(kit/'lib64') + (':'+env['LD_LIBRARY_PATH'] if env.get('LD_LIBRARY_PATH') else '')
java_home = env.get('JAVA_HOME')
java = str(Path(java_home)/'bin/java') if java_home else shutil.which('java')
keytool = str(Path(java_home)/'bin/keytool') if java_home else shutil.which('keytool')
def run(*cmd):
    subprocess.run(list(map(str,cmd)),cwd=ROOT,env=env,check=True)
if not store.exists():
    run(keytool,'-genkeypair','-storetype','PKCS12','-keystore',store,'-alias',a.alias,'-keyalg','RSA','-keysize','3072','-validity','3650','-dname','CN=Luma Development','-storepass:env','LUMA_SIGN_PASS','-keypass:env','LUMA_SIGN_PASS')
    store.chmod(0o600)
unsigned=ROOT/'app/build/outputs/apk/release/app-release-unsigned.apk'
inputs=[p for p in (ROOT/'app/src/main').rglob('*') if p.is_file()]+[ROOT/'app/build.gradle.kts',ROOT/'build.gradle.kts']
if not unsigned.exists() or any(p.stat().st_mtime > unsigned.stat().st_mtime for p in inputs):
    raise SystemExit('Release APK is missing or older than its sources. Run ./gradlew :app:assembleRelease first.')
aligned=ROOT/'app/build/luma-aligned.apk'
dist=ROOT/'dist';dist.mkdir(exist_ok=True)
metadata=json.loads((unsigned.parent/'output-metadata.json').read_text())['elements'][0]
version=metadata['versionName']
apk=dist/f'Luma-Agent-{version}.apk'
run(kit/'zipalign','-P','16','-f','4',unsigned,aligned)
run(java,'-jar',kit/'lib/apksigner.jar','sign','--ks',store,'--ks-key-alias',a.alias,'--ks-pass','env:LUMA_SIGN_PASS','--key-pass','env:LUMA_SIGN_PASS','--min-sdk-version','26','--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','true','--v4-signing-enabled','false','--out',apk,aligned)
run(java,'-jar',kit/'lib/apksigner.jar','verify','--verbose','--print-certs',apk)
run(kit/'zipalign','-c','-P','16','4',apk)
with zipfile.ZipFile(apk) as z:
    if z.testzip():raise SystemExit('Corrupt APK')
sha=hashlib.sha256(apk.read_bytes()).hexdigest()
apk.with_suffix('.apk.sha256').write_text(f'{sha}  {apk.name}\n')
archive=dist/f'Luma-Agent-{version}-source.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    for path in sorted(ROOT.rglob('*')):
        if not path.is_file():continue
        parts=path.relative_to(ROOT).parts
        if any(x in {'.gradle','.git','.kotlin','build','dist','__pycache__','.idea'} for x in parts):continue
        if path.suffix in {'.log','.p12','.jks','.keystore','.apk'} or path.name=='local.properties':continue
        z.write(path,Path('LumaAgent')/path.relative_to(ROOT))
print('APK:',apk)
print('Bytes:',apk.stat().st_size)
print('SHA-256:',sha)
print('Sources:',archive)
