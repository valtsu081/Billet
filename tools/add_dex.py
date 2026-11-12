import shutil
import sys
import zipfile

base, dex, out = sys.argv[1:4]
shutil.copyfile(base, out)
with zipfile.ZipFile(out, 'a', compression=zipfile.ZIP_DEFLATED) as apk:
    apk.write(dex, 'classes.dex')
