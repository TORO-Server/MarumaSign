#!/usr/bin/env python3
"""
ライブラリ (第三者のコード) を使っていないことを確かめる。

1. core と bootstrap のビルドの設定に、外から取ってくる依存 (group:artifact:version の形) がないこと。
   dist のビルドの設定にも、buildscript の依存 (ASM など) がないこと。
2. core と bootstrap のソースが、JDK (java.* / javax.* / org.w3c.dom / org.xml.sax) と
   この Mod 自身 (marumasa.*) のものしか import していないこと。
   bootstrap はローダーの入口のアノテーション (コンパイルにだけ使う stubs) も許す。
3. adapters のソースが、JDK と Mod 自身のほかは、Minecraft とローダーの API しか import していないこと。
   Minecraft に同梱されているライブラリ (Apache Commons、Guava、fastutil、Netty など) も使わない。

使い方: python3 .github/scripts/check-no-libraries.py (リポジトリの一番上で)
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

JDK = ("java.", "javax.", "org.w3c.dom.", "org.xml.sax.")
OWN = ("marumasa.",)
# ローダーのアノテーション (bootstrap の stubs にある、コンパイルにだけ使う定義)
LOADER_STUBS = ("cpw.mods.fml.", "net.minecraftforge.fml.", "net.neoforged.fml.")
# Minecraft とローダーの API (adapters はこれを使って Minecraft とつなぐ)
GAME_API = ("net.minecraft.", "com.mojang.", "org.lwjgl.", "org.joml.", "net.minecraftforge.", "net.neoforged.",
            "net.fabricmc.", "cpw.mods.", "com.mumfrey.")

IMPORT = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)", re.M)
DEPENDENCY = re.compile(r"""^\s*(\w+)\s*\(?\s*['"]([\w.\-]+):([\w.\-]+)(?::[^'"]*)?['"]""", re.M)

problems = []


def check_imports(directory, allowed):
    for path in sorted((ROOT / directory).rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        for name in IMPORT.findall(text):
            if not name.startswith(allowed):
                problems.append(f"{path.relative_to(ROOT)}: import {name}")


def check_gradle(path, allow_buildscript=False):
    text = (ROOT / path).read_text(encoding="utf-8")
    for configuration, group, artifact in DEPENDENCY.findall(text):
        if configuration in ("id", "classpath") and allow_buildscript:
            continue
        problems.append(f"{path}: {configuration} {group}:{artifact}")
    if not allow_buildscript and "buildscript" in text:
        problems.append(f"{path}: buildscript dependencies")


check_gradle("core/build.gradle")
check_gradle("bootstrap/build.gradle")
check_gradle("dist/build.gradle")
check_imports("core/src/main/java", JDK + OWN)
check_imports("core/src/test/java", JDK + OWN + ("com.sun.net.httpserver.",))
check_imports("bootstrap/src/main/java", JDK + OWN + LOADER_STUBS)
check_imports("adapters", JDK + OWN + GAME_API)

if problems:
    for problem in problems:
        print(f"::error::Third-party library use: {problem}")
    sys.exit(1)
print("No third-party libraries are used.")
