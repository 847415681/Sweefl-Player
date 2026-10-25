"""Rebuild only Nextlib JNI against the shipped FFmpeg 6 libraries (Windows NDK r25c).
Pristine source kit is never modified. Every input and replaced AAR member is hashed.
Usage: python third-party/nextlib/rebuild_native_adapter.py --ndk PATH --aar ORIGINAL_AAR
"""
import argparse,difflib,hashlib,json,os,subprocess,tarfile,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--ndk',required=True);p.add_argument('--aar',required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[2];kit=root/'third-party/nextlib';work=root/'app/build/nextlib-native';work.mkdir(parents=True,exist_ok=True)
temporary=work/'tmp';temporary.mkdir(exist_ok=True)
os.environ['TMP']=os.environ['TEMP']=str(temporary)
source=kit/'pinned-build/nextlib/media3ext/src/main/cpp'
assert hashlib.sha256((source/'ffvideo.cpp').read_bytes()).hexdigest()=='467101e71505918287ee4b16c0087af23968b94416692b06047ba4afcefd1158','Pinned native source changed'
original=(source/'ffvideo.cpp').read_text(encoding='utf-8')
start=original.index('    bool MaybeAcquireNativeWindow(');end=original.index('    jfieldID data_field',start)
replacement='''    bool MaybeAcquireNativeWindow(JNIEnv *env, jobject new_surface) {
        // JNI local references cannot be retained across calls. Even the same Java
        // Surface can be rebound to a different BufferQueue after recreation.
        ANativeWindow *acquired = new_surface == nullptr ? nullptr
                : ANativeWindow_fromSurface(env, new_surface);
        if (acquired == nullptr) {
            LOGE("Unable to acquire current Surface native window");
            return false;
        }
        if (acquired == native_window) {
            ANativeWindow_release(acquired); // Balance this call's acquisition.
            return true;
        }
        if (native_window) ANativeWindow_release(native_window);
        native_window = acquired;
        native_window_width = 0;
        native_window_height = 0;
        return true;
    }

'''
modified=original[:start]+replacement+original[end:]
modified=modified.replace('    jobject surface = nullptr;\n','')
# Surface recreation must not leak the old scaler when geometry is configured again.
modified=modified.replace('        jniContext->swsContext = swsContext;','        sws_freeContext(jniContext->swsContext);\n        jniContext->swsContext = swsContext;')
old='''    if (result == -19) {
        jniContext->surface = nullptr;
        return VIDEO_DECODER_SUCCESS;
    } else if (result || native_window_buffer.bits == nullptr) {'''
new='''    if (result == -19) {
        // The Java Surface may have changed between acquire and lock. Reacquire
        // once for this SAME output buffer; never report a dropped paused frame
        // as successfully presented.
        if (!jniContext->MaybeAcquireNativeWindow(env, surface) ||
            ANativeWindow_setBuffersGeometry(jniContext->native_window,
                    displayed_width, displayed_height, kImageFormatYV12) != 0) {
            return VIDEO_DECODER_ERROR_OTHER;
        }
        jniContext->native_window_width = displayed_width;
        jniContext->native_window_height = displayed_height;
        result = ANativeWindow_lock(jniContext->native_window, &native_window_buffer, nullptr);
    }
    if (result || native_window_buffer.bits == nullptr) {'''
assert modified.count(old)==1,'Pinned lock branch changed';modified=modified.replace(old,new)
assert 'jobject surface = nullptr' not in modified and 'surface == new_surface' not in modified
for f in source.glob('*'):
 if f.suffix in ('.cpp','.h'): (work/f.name).write_bytes(f.read_bytes())
(work/'ffvideo.cpp').write_text(modified,encoding='utf-8')
(work/'native-window.patch').write_text(''.join(difflib.unified_diff(original.splitlines(True),modified.splitlines(True),fromfile='a/ffvideo.cpp',tofile='b/ffvideo.cpp')),encoding='utf-8')
assert (kit/'native-window.patch').read_text(encoding='utf-8') == (work/'native-window.patch').read_text(encoding='utf-8'),'Review patch differs from generated transformation'
headers=work/'headers';headers.mkdir(exist_ok=True)
with tarfile.open(kit/'archives/ffmpeg-6.0.tar.gz') as tf:
 for m in tf:
  path=Path(m.name)
  if m.isfile() and path.suffix=='.h' and len(path.parts)>1:
   relative=Path(*path.parts[1:]);dest=headers/relative
   if not dest.resolve().is_relative_to(headers.resolve()):raise ValueError('Unsafe archive path')
   dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(tf.extractfile(m).read())
# Public architecture configuration required by FFmpeg headers; AArch64 is LE and
# supports unaligned scalar accesses. No FFmpeg sources or libraries are rebuilt.
(headers/'libavutil/avconfig.h').write_text('#ifndef AVUTIL_AVCONFIG_H\n#define AVUTIL_AVCONFIG_H\n#define AV_HAVE_BIGENDIAN 0\n#define AV_HAVE_FAST_UNALIGNED 1\n#endif\n')
libs=work/'libs';libs.mkdir(exist_ok=True)
input_aar=Path(a.aar).resolve();output=root/'app/libs/nextlib-media3ext-1.8.0-0.9.0-sweefl.aar';output.parent.mkdir(exist_ok=True)
sha=lambda b:hashlib.sha256(b).hexdigest()
expected='f18b041a8fe823f86b23af6270f1ddd617036dff5753edf7b2a21ed6d806ca11'
assert sha(input_aar.read_bytes())==expected,'Unrecognized original AAR'
with zipfile.ZipFile(input_aar) as z:
 for name in ('avcodec','avutil','swresample','swscale'):
  (libs/f'lib{name}.so').write_bytes(z.read(f'jni/arm64-v8a/lib{name}.so'))
ndk=Path(a.ndk).resolve()
assert '25.2.9519653' in (ndk/'source.properties').read_text(),'Expected Android NDK r25c'
bin=ndk/'toolchains/llvm/prebuilt/windows-x86_64/bin'
compiler=bin/'clang++.exe';sysroot=bin.parent/'sysroot';binary=work/'libmedia3ext.so'
cmd=[str(compiler),'--target=aarch64-linux-android21',f'--sysroot={sysroot}','-std=c++17','-O2','-fPIC','-shared','-static-libstdc++','-Wl,-z,max-page-size=16384','-Wl,-soname,libmedia3ext.so','-Wl,--no-undefined',f'-I{headers}',f'-L{libs}',*[str(work/f) for f in ('ffmain.cpp','ffcommon.cpp','ffaudio.cpp','ffvideo.cpp')],'-lavcodec','-lavutil','-lswresample','-lswscale','-llog','-landroid','-o',str(binary)]
subprocess.run(cmd,check=True)
subprocess.run([str(bin/'llvm-strip.exe'),'--strip-unneeded',str(binary)],check=True)
replacement_name='jni/arm64-v8a/libmedia3ext.so'
with zipfile.ZipFile(input_aar) as src,zipfile.ZipFile(output,'w') as dst:
 for item in src.infolist():dst.writestr(item,binary.read_bytes() if item.filename==replacement_name else src.read(item.filename))
with zipfile.ZipFile(input_aar) as before,zipfile.ZipFile(output) as after:
 changed=[n for n in before.namelist() if before.read(n)!=after.read(n)]
 assert changed==[replacement_name],changed
report={'inputAarSha256':sha(input_aar.read_bytes()),'outputAarSha256':sha(output.read_bytes()),'originalFfvideoSha256':sha(original.encode()),'patchedFfvideoSha256':sha(modified.encode()),'nativeSha256':sha(binary.read_bytes()),'changedEntries':changed,'compiler':str(compiler),'command':cmd,'note':'JNI-only rebuild; upstream FFmpeg shared libraries and Java classes byte-identical. Device validation performed separately.'}
(work/'build-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2));print(output)
