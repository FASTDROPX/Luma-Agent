#!/usr/bin/env python3
"""Install pinned Android SDK/Gradle packages into a shared local toolchain."""
from pathlib import Path
import hashlib, os, shutil, stat, urllib.request, zipfile
ROOT=Path(__file__).resolve().parents[1]
TOOLS=Path(os.environ.get('LUMA_TOOLCHAIN',str(Path.home()/'.cache/luma-toolchain')))
TOOLS.mkdir(parents=True,exist_ok=True)
SDK=TOOLS/'android-sdk'
PACKAGES=[
 ('platform-35_r02.zip','0bb560a90a7a2cbd0dd8348224d518b638fe7949',SDK/'platforms/android-35'),
 ('build-tools_r35_linux.zip','2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc',SDK/'build-tools/35.0.0')]
for name,sha,dest in PACKAGES:
 if (dest/'source.properties').exists():continue
 archive=TOOLS/name
 if not archive.exists():
  with urllib.request.urlopen('https://dl.google.com/android/repository/'+name,timeout=60) as r,archive.open('wb') as out:shutil.copyfileobj(r,out)
 if hashlib.sha1(archive.read_bytes()).hexdigest()!=sha:raise SystemExit('SDK checksum mismatch: '+name)
 staging=TOOLS/('extract-'+name)
 staging.mkdir(exist_ok=True)
 with zipfile.ZipFile(archive) as z:
  z.extractall(staging)
  for entry in z.infolist():
   mode=entry.external_attr>>16
   if mode&stat.S_IXUSR:(staging/entry.filename).chmod(mode)
 source=next(p.parent for p in staging.rglob('source.properties'))
 dest.parent.mkdir(parents=True,exist_ok=True)
 shutil.move(str(source),dest)
 shutil.rmtree(staging,ignore_errors=True)
 print('Ready:',dest,flush=True)
(ROOT/'local.properties').write_text('sdk.dir='+str(SDK)+'\n')
print('Android SDK configured')
