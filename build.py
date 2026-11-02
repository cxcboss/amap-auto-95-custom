#!/usr/bin/env python3
from pathlib import Path
import argparse
import hashlib
import os
import shutil
import subprocess
import zipfile
from apk_metadata import patch_manifest, patch_provider_paths, version

ROOT = Path(__file__).resolve().parent
BASE_SHA256 = "7f88ecbcb20c16d07ed7f427d25b79a31062209295a133f3d846128b7cbc9b54"
GFRAME_SHA256 = "3285048f3dc2b734de3fe1e30dd5a3ea4092f4dd6092b7c24a61f46741fd4a06"
COMMON_SHA256 = "d0aad0a8f8bf0236bd365e9fd55a0ad081be378aac794855535c37807eef8e6e"

# CGSceneUICmptMapElementLuming is the bottom road-name/speed capsule shared by
# navigation and cruise. Its initScene already hides the speed group (+0xaf8).
# Keep it hidden, skip its text/color updates, and retain the road and limit sign.
# In this fixed library's executable LOAD segment, file offsets equal addresses.
CAPSULE_SPEED_PATCHES = (
    (0xc35de0, 0xd503201f),  # sceneLimitSpeedState: skip showing the speed group.
    (0xc35e84, 0xd65f03c0),  # setTextSpeed: return before text/layout work.
    (0xc3602c, 0xd65f03c0),  # sceneNormalSpeed: skip hidden speed styling.
    (0xc360ac, 0x2a1f03f4),  # setVisibleSpeed: use false (mov w20, wzr).
    (0xc36200, 0xaa1303e0),  # initScene: pass this instead of the speed child.
    (0xc36208, 0x97ffff6e),  # initScene: call capsule_center at 0xc35fc0.
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-apk", type=Path, required=True)
    parser.add_argument("--key", type=Path, required=True)
    parser.add_argument("--cert", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", Path.home() / "Library/Android/sdk"))))
    parser.add_argument("--build-tools", default="36.1.0")
    parser.add_argument("--ndk", default="27.0.12077973")
    parser.add_argument("--platform", default="android-35")
    parser.add_argument("--output", type=Path, default=ROOT / "build/amap-auto-95-custom.apk")
    args = parser.parse_args()
    info = version()
    if hashlib.sha256(args.base_apk.read_bytes()).hexdigest() != BASE_SHA256:
        parser.error("--base-apk must be the versionCode 26000007 cruise-response build")
    build = ROOT / "build"
    build.mkdir(exist_ok=True)
    classes, dex = build / "classes", build / "dex"
    for directory in (classes, dex):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir()
    generated = build / "generated/floatapp/update/BuildInfo.java"
    generated.parent.mkdir(parents=True, exist_ok=True)
    generated.write_text('package floatapp.update;\npublic final class BuildInfo {\n'
        + ''.join('    public static final String ' + key + ' = "' + info[field] + '";\n'
                  for key, field in (("VERSION_NAME", "versionName"), ("PACKAGE_NAME", "packageName"), ("REPOSITORY", "repository")))
        + '    public static final int VERSION_CODE = ' + str(info["versionCode"]) + ';\n}\n', encoding="utf-8")
    android = args.sdk / "platforms" / args.platform / "android.jar"
    bt = args.sdk / "build-tools" / args.build_tools
    host = "darwin-x86_64" if os.uname().sysname == "Darwin" else "linux-x86_64"
    clang = args.sdk / "ndk" / args.ndk / "toolchains/llvm/prebuilt" / host / "bin/aarch64-linux-android21-clang"

    def run(*command):
        subprocess.run([str(part) for part in command], check=True)

    native = build / "libamapextras.so"
    run(clang, "-shared", "-fPIC", "-O2", "-Wl,-z,max-page-size=16384",
        ROOT / "src/native/extras.c", "-ldl", "-o", native)
    run(clang.parent / "llvm-strip", "--strip-unneeded", "--remove-section=.comment", native)
    fade_object, fade_linked = build / "gframe-fade.o", build / "gframe-fade.elf"
    run(clang, "-c", ROOT / "src/native/gframe_fade.S", "-o", fade_object)
    run(clang.parent / "ld.lld", "-T", ROOT / "src/native/gframe_fade.ld",
        "-e", "gframe_fade_enter", fade_object, "-o", fade_linked)
    fade_patches = []
    for section, start, end in ((".inflate_enter_branch", 0x5eecb0, 0x5eecb4),
                                (".inflate_exit_branch", 0x5eee70, 0x5eee74),
                                (".fade_enter", 0x5f4f40, 0x5f5074),
                                (".fade_exit", 0x5f51c0, 0x5f5310)):
        fragment = build / (section[1:] + ".bin")
        run(clang.parent / "llvm-objcopy", "--dump-section", str(section) + "=" + str(fragment), fade_linked)
        code = fragment.read_bytes()
        if len(code) > end - start or len(code) % 4:
            parser.error("GFrame fade patch exceeds its original transition log block")
        fade_patches.append((start, end, code + bytes.fromhex("1f2003d5") * ((end - start - len(code)) // 4)))
    center_object, center_linked = build / "capsule-center.o", build / "capsule-center.elf"
    run(clang, "-c", ROOT / "src/native/capsule_center.S", "-o", center_object)
    run(clang.parent / "ld.lld", "-T", ROOT / "src/native/capsule_center.ld",
        "-e", "capsule_center", center_object, "-o", center_linked)
    center_binary = build / "capsule-center.bin"
    run(clang.parent / "llvm-objcopy", "--dump-section", ".capsule_center=" + str(center_binary), center_linked)
    center_code = center_binary.read_bytes()
    if len(center_code) > 0xc3602c - 0xc35fc0 or len(center_code) % 4:
        parser.error("Capsule centering exceeds the disabled road-speed color method")
    sources = sorted((ROOT / "src/java").rglob("*.java")) + sorted((ROOT / "src/stubs").rglob("*.java")) + [generated]
    run("javac", "-source", "8", "-target", "8", "-g:none", "-encoding", "UTF-8", "-cp", android, "-d", classes, *sources)
    # Compile-only vendor signatures must never be included in the helper dex.
    packaged = [file for group in ("audio", "floatset", "font", "startup", "input", "update")
                for file in sorted((classes / "floatapp" / group).glob("*.class"))]
    run(bt / "d8", "--release", "--min-api", "21", "--lib", android, "--output", dex, *packaged)
    with zipfile.ZipFile(args.base_apk) as base, zipfile.ZipFile(build / "unsigned.apk", "w") as out:
        for item in base.infolist():
            name = item.filename
            if name in ("classes2.dex", "lib/arm64-v8a/libamapextras.so"):
                continue
            if name.upper().startswith("META-INF/") and name.upper().endswith((".RSA", ".DSA", ".EC", ".SF", "/MANIFEST.MF")):
                continue
            data = base.read(name)
            if name == "AndroidManifest.xml":
                data = patch_manifest(data, info)
            elif name == "res/zz.xml":
                data = patch_provider_paths(data)
            elif name == "lib/arm64-v8a/libGFrame.so":
                if hashlib.sha256(data).hexdigest() != GFRAME_SHA256:
                    parser.error("Unexpected bundled GFrame engine; do not apply these offsets")
                patched = bytearray(data)
                for start, end, code in fade_patches:
                    patched[start:end] = code
                data = bytes(patched)
            elif name == "lib/arm64-v8a/libCommon.so":
                if hashlib.sha256(data).hexdigest() != COMMON_SHA256:
                    parser.error("Unexpected bundled Common engine; do not apply capsule offsets")
                patched = bytearray(data)
                for address, instruction in CAPSULE_SPEED_PATCHES:
                    patched[address:address + 4] = instruction.to_bytes(4, "little")
                patched[0xc35fc0:0xc35fc0 + len(center_code)] = center_code
                data = bytes(patched)
            out.writestr(item, data)
        out.write(dex / "classes.dex", "classes2.dex", compress_type=zipfile.ZIP_DEFLATED)
        out.write(native, "lib/arm64-v8a/libamapextras.so", compress_type=zipfile.ZIP_STORED)
    run(bt / "zipalign", "-f", "-P", "16", "4", build / "unsigned.apk", build / "aligned.apk")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    run(bt / "apksigner", "sign", "--key", args.key, "--cert", args.cert,
        "--out", args.output, build / "aligned.apk")
    run(bt / "apksigner", "verify", args.output)
    run(bt / "zipalign", "-c", "-P", "16", "4", args.output)
    print(args.output)
    print("SHA256", hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
