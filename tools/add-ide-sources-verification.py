#!/usr/bin/env python3
"""Дописывает в gradle/verification-metadata.xml хеши артефактов, которые Android Studio
запрашивает при sync, но которые `./gradlew --write-verification-metadata` не записывает.

Проблема: команда записывает только то, что реально скачал CLI-процесс. IDE при sync создаёт
detached-конфигурацию с classifier "sources" (индексация исходников зависимостей), поэтому
файл, сгенерированный из `check` и `assembleDebug`, оставляет IDE без возможности
синхронизироваться:

    Dependency verification failed for configuration ':sample-android:detachedConfiguration2'
    180 artifacts failed verification:
      - kotlin-stdlib-2.4.20-sources.jar (org.jetbrains.kotlin:kotlin-stdlib:2.4.20) ...

Штатно эти артефакты в metadata не попадают: у native/klib-модулей sources не резолвятся
через `<group>:<name>:<version>:sources@jar`, варианты плагинов с classifier
(`-gradle96-sources.jar`) требуют другого координата, а sources дистрибутива Gradle не
запрашивается ни одной задачей.

Скрипт читает список непроверенных из лога IDE, находит каждый файл в кэше Gradle
(все они уже скачаны — иначе IDE не дошёл бы до проверки) и дописывает SHA-256 содержимого.

Использование:
    python3 tools/add-ide-sources-verification.py
    python3 tools/add-ide-sources-verification.py --log /path/to/idea.log

После запуска обязательно проверьте результат с обновлением кэша:
    ./gradlew --dependency-verification=strict --refresh-dependencies check
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import sys
import xml.etree.ElementTree as ET

NS = "https://schema.gradle.org/dependency-verification"
DEFAULT_META = "gradle/verification-metadata.xml"
DEFAULT_CACHE = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")

# Где Android Studio пишет idea.log: (база, префикс версии, путь до лога внутри версии).
LOG_LOCATIONS = [
    ("~/Library/Logs/Google", "AndroidStudio", "idea.log"),
    ("~/.cache/Google", "AndroidStudio", "log/idea.log"),
    ("~/Library/Caches/Google", "AndroidStudio", "log/idea.log"),
    ("%LOCALAPPDATA%/Google", "AndroidStudio", "log/idea.log"),
]


def find_ide_log() -> str | None:
    """Ищет самый свежий idea.log среди установленных версий Android Studio.

    База и префикс версии хранятся раздельно намеренно: подставлять ``*`` прямо в путь и
    брать ``dirname`` нельзя — dirname вычислится по шаблону и даст каталог с ``*`` внутри.
    """
    found: list[tuple[float, str]] = []
    for template, version_prefix, relative in LOG_LOCATIONS:
        base = os.path.expandvars(os.path.expanduser(template))
        if not os.path.isdir(base):
            continue
        for name in os.listdir(base):
            if not name.startswith(version_prefix):
                continue
            candidate = os.path.join(base, name, relative)
            if os.path.isfile(candidate):
                found.append((os.path.getmtime(candidate), candidate))
    if not found:
        return None
    return max(found)[1]


def parse_failed(text: str) -> list[tuple[str, str]]:
    """Достаёт из лога пары (имя файла, group:name:version)."""
    match = re.search(r"(\d+) artifacts failed verification:\n((?:\s+- .*\n)+)", text)
    if not match:
        return []
    pairs = set()
    for line in match.group(2).strip().split("\n"):
        filename = re.search(r"-\s+(\S+\.(?:jar|zip))", line)
        coords = re.search(r"\(([^)]+:[^)]+)\)", line)
        if filename and coords:
            pairs.add((filename.group(1), coords.group(1)))
    return sorted(pairs)


def locate(cache: str, filename: str, coords: str) -> str | None:
    group, name, version = coords.split(":")
    base = os.path.join(cache, group, name, version)
    if not os.path.isdir(base):
        return None
    for root, _dirs, files in os.walk(base):
        if filename in files:
            return os.path.join(root, filename)
    return None


def digest(path: str) -> str:
    sha = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            sha.update(chunk)
    return sha.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", help="путь к idea.log (по умолчанию ищется сама)")
    parser.add_argument("--metadata", default=DEFAULT_META)
    parser.add_argument("--cache", default=DEFAULT_CACHE)
    args = parser.parse_args()

    log_path = args.log or find_ide_log()
    if not log_path or not os.path.isfile(log_path):
        sys.exit("Лог Android Studio не найден. Синхронизируйте проект в IDE и повторите.")
    if not os.path.isfile(args.metadata):
        sys.exit(f"{args.metadata} не найден — он в корне репозитория.")

    wanted = parse_failed(open(log_path, encoding="utf-8", errors="ignore").read())
    if not wanted:
        print("В логе нет непроверенных артефактов: sync, вероятно, уже проходит.")
        return 0

    ET.register_namespace("", NS)
    tree = ET.parse(args.metadata)
    root = tree.getroot()

    components_tag = root.find(f"{{{NS}}}components")
    if components_tag is None:
        sys.exit("В файле нет тега <components>.")

    components = {
        (c.get("group"), c.get("name"), c.get("version")): c
        for c in components_tag.findall(f"{{{NS}}}component")
    }

    added, already, missing = 0, 0, []
    for filename, coords in wanted:
        group, name, version = coords.split(":")
        component = components.get((group, name, version))
        if component is None:
            # Обязательно внутри <components>: Gradle отвергает <component> в корне файла.
            component = ET.SubElement(components_tag, f"{{{NS}}}component",
                                      {"group": group, "name": name, "version": version})
            components[(group, name, version)] = component
        if any(a.get("name") == filename for a in component.iter(f"{{{NS}}}artifact")):
            already += 1
            continue
        path = locate(args.cache, filename, coords)
        if path is None:
            missing.append(f"{filename} ({coords})")
            continue
        artifact = ET.SubElement(component, f"{{{NS}}}artifact", {"name": filename})
        ET.SubElement(artifact, f"{{{NS}}}sha256",
                      {"value": digest(path), "origin": "Generated by Gradle"})
        added += 1

    tree.write(args.metadata, encoding="UTF-8", xml_declaration=True)
    print(f"добавлено: {added}, уже было: {already}, не найдено в кэше: {len(missing)}")
    for item in missing:
        print("  -", item)
    if missing:
        print("\nЭти артефакты нужно скачать вручную, иначе sync продолжит падать.")
        return 1
    print("\nПроверьте результат с обновлением кэша:")
    print("  ./gradlew --dependency-verification=strict --refresh-dependencies check")
    return 0


if __name__ == "__main__":
    sys.exit(main())
