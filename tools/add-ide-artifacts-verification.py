#!/usr/bin/env python3
"""Дописывает в gradle/verification-metadata.xml хеши артефактов, которые Android Studio
запрашивает при sync, но которые `./gradlew --write-verification-metadata` не записывает.

Проблема: команда записывает только то, что реально скачал CLI-процесс. IDE при sync
разрешает дополнительные артефакты, поэтому файл, сгенерированный из `check` и
`assembleDebug`, оставляет IDE без возможности синхронизироваться:

    Dependency verification failed for configuration ':sample-android:detachedConfiguration2'
    180 artifacts failed verification:
      - kotlin-stdlib-2.4.20-sources.jar (org.jetbrains.kotlin:kotlin-stdlib:2.4.20) ...

Два источника списка, оба читаются автоматически:
  * HTML-отчёты `build/reports/dependency-verification/*/dependency-verification-report.html`,
    которые Gradle пишет при каждой неудачной верификации. Это надёжнее лога IDE: лог
    ротируется, и после перезапуска Studio блок с failed-артефактами из него исчезает,
    хотя sync продолжает падать.
  * `idea.log` — на случай, если отчёта нет.

Файлы уже лежат в кэше Gradle (IDE скачал их до того, как верификация их отклонила),
поэтому скрипт просто считает SHA-256 того, что есть на диске, и дописывает записи.

Использование:
    python3 tools/add-ide-artifacts-verification.py
    python3 tools/add-ide-artifacts-verification.py --log /path/to/idea.log
    python3 tools/add-ide-artifacts-verification.py --report /path/to/report.html

После запуска обязательно проверьте результат с обновлением кэша:
    ./gradlew --dependency-verification=strict --refresh-dependencies check
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import html
import os
import re
import sys
import xml.etree.ElementTree as ET

NS = "https://schema.gradle.org/dependency-verification"
DEFAULT_META = "gradle/verification-metadata.xml"
DEFAULT_REPORTS = "build/reports/dependency-verification/*/dependency-verification-report.html"
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


def read(path: str) -> str:
    with open(path, encoding="utf-8", errors="ignore") as handle:
        return handle.read()


def parse_log(text: str) -> set[tuple[str, str]]:
    """Достаёт из idea.log пары (имя файла, group:name:version).

    Лог накапливается за всю сессию, поэтому в нём может быть несколько блоков
    «N artifacts failed verification» — по одному на неудачный sync. Берём все:
    первый блок давно закрытого прогона не должен перекрывать свежий.
    """
    pairs = set()
    for _, block in re.findall(r"(\d+) artifacts failed verification:\n((?:\s+- .*\n)+)", text):
        for line in block.strip().split("\n"):
            filename = re.search(r"-\s+(\S+\.(?:jar|zip|klib))", line)
            coords = re.search(r"\(([^)]+:[^)]+)\)", line)
            if filename and coords:
                pairs.add((filename.group(1), coords.group(1)))
    return pairs


def parse_report(text: str) -> set[tuple[str, str]]:
    """Достаёт те же пары из HTML-отчёта Gradle.

    Строка отчёта — это ``<td>group:name:version</td>`` рядом со ссылкой на файл, а
    проблема лежит в третьей ячейке. Берём только ``Checksums are missing``: несовпадение
    хеша — это уже другая история, такой файл нельзя молча «зачинить», генерируя метаданные.
    """
    row = re.compile(r"<tr>(.*?)</tr>", re.DOTALL)
    pairs = set()
    for block in row.findall(text):
        if "Checksums are missing" not in block:
            continue
        coords = re.search(r"<td[^>]*>\s*([\w.\-]+:[\w.\-]+:[\w.\-]+)\s*</td>", block)
        filename = re.search(r'<a href="[^"]*">([\w.\-+]+\.(?:jar|zip|klib))</a>', block)
        if coords and filename:
            pairs.add((filename.group(1), coords.group(1)))
    return pairs


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


def counterpart_variants(components: dict) -> set[tuple[str, str]]:
    """Достраивает парный вариант (sources <-> javadoc) для уже известных компонент.

    IDE не запрашивает sources и javadoc одной волной: сначала прилетает один classifier,
    следующий — только после того, как первый записан, и каждая волна роняет sync отдельно.
    Для компонент, у которых хотя бы один из двух вариантов уже есть, имеет смысл дописать
    второй сразу, если файл лежит в кэше Gradle.
    """
    pairs = set()
    for (group, name, version), component in components.items():
        names = {a.get("name") for a in component.iter(f"{{{NS}}}artifact") if a.get("name")}
        if not any(n.endswith(("-sources.jar", "-javadoc.jar")) for n in names):
            continue
        for suffix in ("-sources.jar", "-javadoc.jar"):
            filename = f"{name}-{version}{suffix}"
            if filename not in names:
                pairs.add((filename, f"{group}:{name}:{version}"))
    return pairs


def record(components: dict, components_tag: ET.Element, filename: str, coords: str, cache: str) -> str:
    """Дописывает артефакт в его компоненту, создавая компоненту при необходимости.

    Возвращает ``added``, ``already`` или ``missing``: файла может не быть в кэше,
    и тогда запись пропускается, а вызывающий код решает, что с этим делать.
    """
    group, name, version = coords.split(":")
    component = components.get((group, name, version))
    if component is None:
        # Обязательно внутри <components>: Gradle отвергает <component> в корне файла.
        component = ET.SubElement(components_tag, f"{{{NS}}}component",
                                  {"group": group, "name": name, "version": version})
        components[(group, name, version)] = component
    if any(a.get("name") == filename for a in component.iter(f"{{{NS}}}artifact")):
        return "already"
    path = locate(cache, filename, coords)
    if path is None:
        return "missing"
    artifact = ET.SubElement(component, f"{{{NS}}}artifact", {"name": filename})
    ET.SubElement(artifact, f"{{{NS}}}sha256",
                  {"value": digest(path), "origin": "Generated by Gradle"})
    return "added"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", help="путь к idea.log (по умолчанию ищется сама)")
    parser.add_argument("--report", action="append", default=[],
                        help="HTML-отчёт Gradle; можно указать несколько раз")
    parser.add_argument("--reports", default=DEFAULT_REPORTS,
                        help="glob для отчётов, если --report не задан")
    parser.add_argument("--metadata", default=DEFAULT_META)
    parser.add_argument("--cache", default=DEFAULT_CACHE)
    parser.add_argument("--artifact", action="append", default=[],
                        help="ручная запись group:name:version:filename")
    parser.add_argument("--close-gaps", action="store_true",
                        help="дописать парный вариант (sources <-> javadoc) для уже известных компонент")
    args = parser.parse_args()

    if not os.path.isfile(args.metadata):
        sys.exit(f"{args.metadata} не найден — он в корне репозитория.")

    wanted: set[tuple[str, str]] = set()
    sources = list(args.report) or sorted(glob.glob(args.reports))
    for path in sources:
        if not os.path.isfile(path):
            print(f"отчёт не найден, пропускаю: {path}")
            continue
        found = parse_report(html.unescape(read(path)))
        print(f"отчёт {path}: {len(found)} непроверенных артефактов")
        wanted |= found

    log_path = args.log or find_ide_log()
    if log_path and os.path.isfile(log_path):
        found = parse_log(read(log_path))
        print(f"лог {log_path}: {len(found)} непроверенных артефактов")
        wanted |= found

    for item in args.artifact:
        coords, _, filename = item.partition(":javadoc@")
        if not filename:
            coords, _, filename = item.rpartition(":")
        if not coords or not filename:
            sys.exit(f"--artifact ожидает group:name:version:filename, получено {item!r}")
        wanted.add((filename, coords))

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

    if args.close_gaps:
        speculative = counterpart_variants(components) - wanted
    else:
        speculative = set()

    if not wanted and not speculative:
        print("Непроверенных артефактов не найдено: sync, вероятно, уже проходит.")
        return 0

    added, already, missing, skipped = 0, 0, [], 0
    for filename, coords in sorted(wanted):
        outcome = record(components, components_tag, filename, coords, args.cache)
        if outcome == "added":
            added += 1
        elif outcome == "already":
            already += 1
        else:
            missing.append(f"{filename} ({coords})")

    # Парные варианты добавляем только из кэша: то, чего IDE ни разу не качала, хешировать
    # нечем, и отсутствие такого артефакта в metadata — не проблема.
    for filename, coords in sorted(speculative):
        outcome = record(components, components_tag, filename, coords, args.cache)
        if outcome == "added":
            added += 1
        elif outcome == "missing":
            skipped += 1

    # Файл должен остаться в том же виде, в котором его пишет сам Gradle, иначе каждый
    # следующий прогон даёт diff из-за форматирования, а не из-за новых записей.
    # Именно здесь, а не до добавления: отступ ставится к уже готовому дереву, иначе
    # свежие <artifact> снова оказываются слитыми с </component> в одну строку.
    ET.indent(tree, space="   ")

    # ElementTree не ставит завершающий перевод строки, а Gradle ставит.
    with open(args.metadata, "wb") as handle:
        tree.write(handle, encoding="UTF-8", xml_declaration=True)
        handle.write(b"\n")
    print(f"добавлено: {added}, уже было: {already}, не найдено в кэше: {len(missing)}"
          + (f", парных вариантов пропущено: {skipped}" if speculative else ""))
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