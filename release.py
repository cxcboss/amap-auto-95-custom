#!/usr/bin/env python3
"""Version bump, signed-APK release preparation and explicit GitHub publication."""
from pathlib import Path
from urllib.parse import quote
from urllib.request import Request, urlopen
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from apk_metadata import ROOT, version


def run(*command):
    return subprocess.run([str(part) for part in command], cwd=ROOT, check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def inspect(apk, sdk, info):
    tools = sdk / "build-tools/36.1.0"
    badge = run(tools / "aapt", "dump", "badging", apk)
    identity = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badge)
    if not identity or identity.groups() != (info["packageName"], str(info["versionCode"]), info["versionName"]):
        raise ValueError("APK package/version differs from version.json; rebuild first")
    signatures = run(tools / "apksigner", "verify", "--verbose", "--print-certs", apk)
    digest = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", signatures)
    if not digest or digest[1] != info["certificateSha256"] or "Number of signers: 1" not in signatures:
        raise ValueError("APK signing certificate is incompatible")
    run(tools / "zipalign", "-c", "-P", "16", "4", apk)
    return int(re.search(r"^sdkVersion:'(\d+)'", badge, re.MULTILINE)[1])


def metadata(folder, info):
    data = json.loads((folder / "update.json").read_text(encoding="utf-8"))
    apk = folder / (info["versionName"] + "高德地图.apk")
    sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
    if (data["versionName"] != info["versionName"] or data["versionCode"] != info["versionCode"]
            or data["packageName"] != info["packageName"] or data["sha256"] != sha256
            or data["assetName"] != apk.name or data["size"] != apk.stat().st_size
            or data["downloadUrl"] != "https://github.com/" + info["repository"] + "/releases/download/v" + info["versionName"] + "/" + quote(apk.name)):
        raise ValueError("Release assets differ from version.json or APK")
    return data, apk


def online(folder, info, latest=False):
    local, apk = metadata(folder, info)
    endpoint = "latest" if latest else "tags/v" + info["versionName"]
    request = Request("https://api.github.com/repos/" + info["repository"] + "/releases/" + endpoint,
                      headers={"User-Agent": "AmapCustomRelease", "Accept": "application/vnd.github+json"})
    with urlopen(request, timeout=30) as response:
        remote = json.load(response)
    if remote["draft"] or remote["prerelease"] or remote["tag_name"] != "v" + info["versionName"]:
        raise ValueError("Public release/tag is not the expected stable version")
    assets = {item["name"]: item for item in remote["assets"]}
    for file in (apk, folder / "update.json", folder / "SHA256SUMS.txt"):
        entry = assets[file.name]
        if entry["size"] != file.stat().st_size or entry.get("digest") != "sha256:" + hashlib.sha256(file.read_bytes()).hexdigest():
            raise ValueError("Published asset size/digest differs: " + file.name)
    with urlopen(Request(assets["update.json"]["browser_download_url"], headers={"User-Agent": "AmapCustomRelease"}), timeout=30) as response:
        if json.load(response) != local:
            raise ValueError("Public update.json differs from prepared manifest")
    if assets[apk.name]["browser_download_url"] != local["downloadUrl"]:
        raise ValueError("GitHub renamed the APK asset")
    print(remote["html_url"])
    print("Public assets and update manifest match the signed local APK")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("bump", help="Increase our display version by 0.1 and versionCode by 1")
    prepare = sub.add_parser("prepare", help="Validate signed APK and prepare assets locally")
    prepare.add_argument("--apk", type=Path, required=True)
    prepare.add_argument("--notes", type=Path, required=True)
    prepare.add_argument("--sdk", type=Path, default=Path.home() / "Library/Android/sdk")
    prepare.add_argument("--output", type=Path, default=ROOT / "build/release")
    publish = sub.add_parser("publish", help="Upload draft first, verify uploads, then expose as Latest")
    publish.add_argument("--folder", type=Path, default=ROOT / "build/release")
    publish.add_argument("--sdk", type=Path, default=Path.home() / "Library/Android/sdk")
    verify = sub.add_parser("verify-online", help="Verify public GitHub metadata and asset digests")
    verify.add_argument("--folder", type=Path, default=ROOT / "build/release")
    args = parser.parse_args()
    info = version()
    tag = "v" + info["versionName"]
    if args.action == "bump":
        major, minor, _, _ = map(int, info["versionName"].split("."))
        major, minor = divmod(major * 10 + minor + 1, 10)
        info["versionName"] = f"{major}.{minor}.9.5"
        info["versionCode"] += 1
        (ROOT / "version.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(info["versionName"], info["versionCode"])
        return
    if args.action == "prepare":
        apk = args.apk.resolve()
        min_sdk = inspect(apk, args.sdk, info)
        notes = args.notes.read_text(encoding="utf-8").strip()
        if not notes:
            raise ValueError("Release notes must describe the actual changes")
        folder = args.output.resolve()
        folder.mkdir(parents=True, exist_ok=True)
        asset = info["versionName"] + "高德地图.apk"
        destination = folder / asset
        if apk != destination:
            shutil.copy2(apk, destination)
        sha256 = hashlib.sha256(destination.read_bytes()).hexdigest()
        data = dict(schemaVersion=1, packageName=info["packageName"], versionName=info["versionName"],
                    versionCode=info["versionCode"], minSdk=min_sdk, assetName=asset,
                    downloadUrl="https://github.com/" + info["repository"] + "/releases/download/" + tag + "/" + quote(asset),
                    size=destination.stat().st_size, sha256=sha256, notes=notes)
        (folder / "update.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (folder / "SHA256SUMS.txt").write_text(sha256 + "  " + asset + "\n", encoding="utf-8")
        (folder / "release-notes.md").write_text(notes + "\n", encoding="utf-8")
        print(folder)
        print(sha256)
    elif args.action == "publish":
        folder = args.folder.resolve()
        data, apk = metadata(folder, info)
        inspect(apk, args.sdk, info)
        if run("git", "status", "--porcelain").strip():
            raise ValueError("Commit source and docs before publishing")
        head = run("git", "rev-parse", "HEAD").strip()
        # Compare remote directly; an old local origin/main is not publication evidence.
        refs = run("git", "ls-remote", "origin", "refs/heads/main").split()
        if not refs or refs[0] != head:
            raise ValueError("Push this source commit to origin/main before publishing")
        latest = json.loads(run("gh", "api", "repos/" + info["repository"] + "/releases"))
        for item in latest:
            if item["tag_name"] == tag:
                raise ValueError("Tag/release already exists; never replace a distributed APK; bump instead")
            if not item["draft"] and not item["prerelease"]:
                assets = {asset["name"]: asset for asset in item["assets"]}
                if "update.json" in assets:
                    with urlopen(assets["update.json"]["browser_download_url"], timeout=30) as response:
                        if data["versionCode"] <= json.load(response)["versionCode"]:
                            raise ValueError("versionCode must exceed every published stable update")
        print(run("gh", "release", "create", tag, apk, folder / "update.json", folder / "SHA256SUMS.txt",
                  "--repo", info["repository"], "--target", head, "--title", info["versionName"],
                  "--notes-file", folder / "release-notes.md", "--draft"), end="")
        draft = json.loads(run("gh", "api", "repos/" + info["repository"] + "/releases/tags/" + tag))
        expected = {file.name: "sha256:" + hashlib.sha256(file.read_bytes()).hexdigest()
                    for file in (apk, folder / "update.json", folder / "SHA256SUMS.txt")}
        uploaded = {asset["name"]: asset.get("digest") for asset in draft["assets"]}
        if expected != uploaded:
            raise ValueError("Draft assets differ; keep the draft unpublished and inspect it")
        run("gh", "release", "edit", tag, "--repo", info["repository"], "--draft=false", "--latest")
        online(folder, info, latest=True)
    elif args.action == "verify-online":
        online(args.folder.resolve(), info, latest=True)


if __name__ == "__main__":
    main()
