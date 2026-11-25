#!/usr/bin/env python3
"""Compiles and links the full-screen (NativeActivity) example projects with the NDK.

The phone builds these examples with Termux's clang against the libraries bundled in the APK.
This does the same on CI with the NDK's clang and the same archives (app/src/main/assets/packages),
so an example that no longer compiles, or links with a missing symbol, fails the pull request
instead of the user's first build. Flags follow core/build's CompilerFlags for a NativeActivity
program: a shared library linked with --no-undefined that keeps ANativeActivity_onCreate.

Usage: compile-examples.py [template-id ...]   (default: every NativeActivity example with
sources in core/project's resources). Needs ANDROID_NDK_HOME (or ANDROID_NDK_ROOT).
"""
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
TEMPLATES_KT = REPO / "core/project/src/main/kotlin/io/nativeforge/core/project/template/ProjectTemplates.kt"
RESOURCES = REPO / "core/project/src/main/resources/io/nativeforge/core/project/templates"
PACKAGES = REPO / "app/src/main/assets/packages"
API_LEVEL = 24  # ClangToolchain.DEFAULT_API_LEVEL
ABIS = {"aarch64": "aarch64-linux-android", "arm": "armv7a-linux-androideabi", "x86_64": "x86_64-linux-android"}
STANDARDS = {".c": "c17", ".cpp": "c++20"}  # Language.defaultStandard


def examples():
    """(id, packages, source files) of the NativeActivity templates whose sources are resource files."""
    text = TEMPLATES_KT.read_text()
    found = []
    for block in text.split("SimpleTemplate(")[1:]:
        ident = re.search(r'\bid = "([^"]+)"', block)
        if ident is None:  # the class itself
            continue
        ident = ident.group(1)
        if "RuntimeKind.NATIVE_ACTIVITY" not in block or "TemplateSources." in block:
            continue
        files = set(re.findall(r'TemplateResources\.read\("([^"]+)"\)', block))
        for folder in re.findall(r'resourceSources\(language, "([^"]+)"\)', block):
            files |= {f"{folder}/main.c", f"{folder}/main.cpp"}
        if not files:
            continue
        packages = re.search(r"packages = listOf\(([^)]*)\)", block)
        names = re.findall(r'"([^"]+)"', packages.group(1)) if packages else []
        found.append((ident, names, sorted(files)))
    return found


def resolve(index, names, arch):
    """The packages and their dependencies for one architecture, dependencies first."""
    by_name = {}
    for p in index["packages"]:
        if p["architecture"] in (arch, "all"):
            by_name.setdefault(p["name"], p)
    ordered = []

    def visit(name):
        if any(p["name"] == name for p in ordered):
            return
        if name not in by_name:
            sys.exit(f"package {name} is not bundled for {arch}")
        for dep in by_name[name].get("depends", []):
            visit(dep)
        ordered.append(by_name[name])

    for name in names:
        visit(name)
    return ordered


def unpack(package, work):
    folder = work / f'{package["name"]}-{package["architecture"]}'
    if not folder.exists():
        archive = PACKAGES / package["downloadUrl"].removeprefix("asset:packages/")
        folder.mkdir(parents=True)
        with tarfile.open(archive) as tar:
            tar.extractall(folder, filter="data")
    return folder


def run(command):
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        print(" ".join(map(str, command)))
        print(result.stdout + result.stderr)
    return result.returncode == 0


def main():
    ndk = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    if not ndk:
        sys.exit("Set ANDROID_NDK_HOME to the NDK folder")
    bin_dir = Path(ndk) / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    index = json.loads((PACKAGES / "index.json").read_text())
    wanted = set(sys.argv[1:])
    selected = [e for e in examples() if not wanted or e[0] in wanted]
    if wanted - {e[0] for e in selected}:
        sys.exit(f"not NativeActivity examples: {sorted(wanted - {e[0] for e in selected})}")

    failures = []
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        for ident, names, files in selected:
            for arch, triple in ABIS.items():
                target = f"--target={triple}{API_LEVEL}"
                deps = [(p, unpack(p, work)) for p in resolve(index, names, arch)]
                # -O0 without NDEBUG, like the phone's default Debug build: inline library code
                # (Box3D's asserts, for one) then keeps every call it makes, which -O1 can fold away.
                compile_flags = ["-fPIC", "-O0", "-Wall", "-Werror=return-type", "-DNATIVEFORGE=1",
                                 "-DNATIVEFORGE_RUNTIME_NATIVE_ACTIVITY=1"]
                for p, root in deps:
                    build = p.get("build", {})
                    for inc in build.get("includeDirs", []):
                        compile_flags += ["-isystem", str(root / inc)]
                    compile_flags += build.get("compileFlags", [])
                # C and C++ variants of a template are separate programs.
                for language in sorted({Path(f).suffix for f in files}):
                    out = work / "out" / ident / arch / language.lstrip(".")
                    out.mkdir(parents=True, exist_ok=True)
                    objects, ok = [], True
                    for f in (f for f in files if Path(f).suffix == language):
                        source = out / Path(f).name
                        source.write_text((RESOURCES / f).read_text().replace("@PROJECT_NAME@", "Example"))
                        obj = source.with_suffix(".o")
                        compiler = bin_dir / ("clang++" if language == ".cpp" else "clang")
                        ok &= run([compiler, target, *compile_flags, f"-std={STANDARDS[language]}",
                                   "-c", source, "-o", obj])
                        objects.append(obj)
                    library = out / "libmain.so"
                    link = [bin_dir / "clang++", target, *objects, "-o", library, "-shared", "-Wl,--no-undefined",
                            "-Wl,-u,ANativeActivity_onCreate", "-static-libstdc++"]
                    for p, root in reversed(deps):
                        build = p.get("build", {})
                        link += [f"-L{root / d}" for d in build.get("libDirs", [])]
                    for p, root in reversed(deps):
                        build = p.get("build", {})
                        link += [f"-l{lib}" for lib in build.get("linkLibraries", [])] + build.get("linkFlags", [])
                    link.append("-lm")
                    ok = ok and run(link)
                    if ok:
                        symbols = subprocess.run([bin_dir / "llvm-nm", "-D", "--defined-only", library],
                                                 capture_output=True, text=True).stdout
                        ok = " ANativeActivity_onCreate" in symbols
                        if not ok:
                            print(f"{ident}: ANativeActivity_onCreate is not exported")
                    name = f"{ident} ({language.lstrip('.')}, {arch})"
                    print(("ok    " if ok else "FAIL  ") + name, flush=True)
                    if not ok:
                        failures.append(name)
    if failures:
        sys.exit(f"{len(failures)} example build(s) failed: {', '.join(failures)}")


if __name__ == "__main__":
    main()
