#!/usr/bin/env python3
"""Checks that a built IDE APK carries what package installs need.

The bundled library index (assets/packages/index.json) must be there, every archive it names
for every ABI must be in the APK with the SHA-256 the index gives, and the runtime template
and the `pkg` command's entry point must be present. A packaging change (resource or asset
shrinking, an aapt ignore pattern, a renamed asset folder) then fails CI instead of failing on
the phone with "library not found" or a checksum error.

Usage: check-apk-packages.py <app.apk>
"""
import hashlib
import json
import sys
import zipfile


def main(path: str) -> int:
    errors = []
    with zipfile.ZipFile(path) as apk:
        names = set(apk.namelist())
        try:
            index = json.loads(apk.read("assets/packages/index.json"))
        except KeyError:
            print("::error::assets/packages/index.json is missing from the APK")
            return 1
        packages = index.get("packages", [])
        if not packages:
            errors.append("the bundled index lists no packages")
        abis = set()
        for pkg in packages:
            url = pkg.get("downloadUrl", "")
            label = f"{pkg.get('name')} ({pkg.get('architecture')})"
            abis.add(pkg.get("architecture"))
            if not url.startswith("asset:"):
                errors.append(f"{label}: archive is not in the APK ({url})")
                continue
            entry = "assets/" + url[len("asset:"):]
            if entry not in names:
                errors.append(f"{label}: {entry} is missing")
                continue
            digest = hashlib.sha256(apk.read(entry)).hexdigest()
            if digest != pkg.get("sha256", "").lower():
                errors.append(f"{label}: {entry} has SHA-256 {digest}, the index says {pkg.get('sha256')}")
        for abi in ("aarch64", "arm", "x86_64"):
            if abi not in abis:
                errors.append(f"no bundled libraries for {abi}")
        if "assets/runtime/template.apk" not in names:
            errors.append("assets/runtime/template.apk is missing (Export needs it)")
        dex = b"".join(apk.read(n) for n in names if n.startswith("classes") and n.endswith(".dex"))
        for cls in (b"Lio/nativeforge/app/cli/PkgMain;", b"Lio/nativeforge/core/packages/PackageManager;"):
            if cls not in dex:
                errors.append(f"{cls.decode()} is not in the dex (removed by R8?)")
    for error in errors:
        print(f"::error::{error}")
    if not errors:
        print(f"{len(packages)} bundled archives present and matching their SHA-256")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
