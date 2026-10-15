#!/usr/bin/env python3
"""Дописывает в gradle/verification-metadata.xml checksum артефактов, которые Android Studio
запрашивает при sync, а `--write-verification-metadata` не записал.

Что инструмент делает и чего не делает.

* Список запросов берётся из данных Gradle: HTML-отчёты
  `build/reports/dependency-verification/*/` (надёжнее, `idea.log` ротируется) и, при их
  отсутствии, `idea.log`. Каждая строка — пара (имя файла, group:name:version) как её
  назвал сам Gradle. Имя файла инструмент не выводит из имени модуля: у AndroidX KMP
  платформенный модуль `animation-android` публикует `animation-1.12.1-samples-sources.jar`.
* Файл ищется только в каталоге точного модуля из отчёта. Поиска по всему кэшу нет.
* Checksum записывается, только если его подтверждает Gradle Module Metadata (`.module`)
  того же модуля: там для каждого файла указан `sha256`. Файл без такой записи (обычно
  Maven-only модуль) не записывается: его checksum может дать только Gradle при реальном
  скачивании, поэтому нужна задача `recordIdeIndexingArtifacts` из build.gradle.kts.
* Несовпадение checksum файла в кэше с `sha256` из `.module` — остановка: ничего не пишется.
* Бинарные варианты (`-all.jar`, `-gradle813.jar` и т.п.) не добавляются, если их не
  запросил сам Gradle. Угадывания классификаторов больше нет.
* Файл редактируется текстовыми вставками: существующие строки не переформатируются.

Использование:
    python3 tools/add-ide-artifacts-verification.py
    python3 tools/add-ide-artifacts-verification.py --report path/to/report.html
    python3 tools/add-ide-artifacts-verification.py --log path/to/idea.log

Коды возврата: 0 — всё записано или уже было; 1 — есть запросы, которые нельзя записать
(нет в кэше или нет подтверждения); 2 — checksum не совпал, файл не изменён.
"""
from __future__ import annotations

import argparse
import dataclasses
import glob
import hashlib
import html
import json
import os
import re
import sys

DEFAULT_META = "gradle/verification-metadata.xml"
DEFAULT_REPORTS = "build/reports/dependency-verification/*/dependency-verification-report.html"
DEFAULT_CACHE = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")
ORIGIN = "Gradle Module Metadata"
FILE_EXTENSIONS = r"(?:jar|aar|zip|klib|pom|module)"

EXIT_OK, EXIT_SKIPPED, EXIT_MISMATCH = 0, 1, 2


@dataclasses.dataclass(frozen=True)
class Request:
    """Запрос Gradle: какой файл, из какого модуля."""

    filename: str
    group: str
    name: str
    version: str

    @property
    def coords(self) -> str:
        return f"{self.group}:{self.name}:{self.version}"


@dataclasses.dataclass
class Summary:
    added: list[Request] = dataclasses.field(default_factory=list)
    already: list[Request] = dataclasses.field(default_factory=list)
    missing_in_cache: list[Request] = dataclasses.field(default_factory=list)
    unverifiable: list[Request] = dataclasses.field(default_factory=list)
    no_descriptor: list[Request] = dataclasses.field(default_factory=list)
    gradle_only: list[Request] = dataclasses.field(default_factory=list)
    mismatched: list[tuple[Request, str, str]] = dataclasses.field(default_factory=list)


def parse_coords(text: str) -> tuple[str, str, str] | None:
    parts = text.split(":")
    if len(parts) != 3 or not all(parts):
        return None
    return parts[0], parts[1], parts[2]


def parse_log(text: str) -> set[Request]:
    """Блоки `N artifacts failed verification:` из idea.log, по одному на неудачный sync."""
    requests: set[Request] = set()
    block = re.compile(r"\d+ artifacts failed verification:\n((?:\s+- .*\n)+)")
    line = re.compile(rf"-\s+(\S+\.{FILE_EXTENSIONS})\s+\(([^)]+)\)")
    for match in block.finditer(text):
        for entry in line.finditer(match.group(1)):
            coords = parse_coords(entry.group(2))
            if coords:
                requests.add(Request(entry.group(1), *coords))
    return requests


def parse_report(text: str) -> set[Request]:
    """Строки HTML-отчёта Gradle с проблемой «Checksums are missing».

    Несовпадение checksum («mismatch») здесь намеренно не разбирается: такой файл нельзя
    «починить» записью нового хеша, это разбор причины, а не запрос на запись.
    """
    requests: set[Request] = set()
    for row in re.findall(r"<tr>(.*?)</tr>", text, re.DOTALL):
        if "Checksums are missing" not in row:
            continue
        coords_match = re.search(r"<td[^>]*>\s*([\w.\-]+:[\w.\-]+:[\w.\-]+)\s*</td>", row)
        file_match = re.search(rf'<a href="[^"]*">([\w.\-+]+\.{FILE_EXTENSIONS})</a>', row)
        if not coords_match or not file_match:
            continue
        coords = parse_coords(coords_match.group(1))
        if coords:
            requests.add(Request(file_match.group(1), *coords))
    return requests


def module_dir(cache: str, request: Request) -> str:
    return os.path.join(cache, request.group, request.name, request.version)


def locate(cache: str, request: Request) -> str | None:
    """Файл только внутри каталога точного модуля (каталог хеша — один уровень)."""
    for path in glob.glob(os.path.join(module_dir(cache, request), "*", request.filename)):
        if os.path.isfile(path):
            return path
    return None


def module_checksums(cache: str, request: Request) -> dict[str, str] | None:
    """Карта `имя файла -> sha256` из Gradle Module Metadata модуля, либо None.

    None — модуль не имеет `.module` в кэше. Пустая карта или отсутствие имени в ней
    тоже означают «подтверждения нет».
    """
    modules = glob.glob(
        os.path.join(module_dir(cache, request), "*", f"{request.name}-{request.version}.module")
    )
    if not modules:
        return None
    checksums: dict[str, str] = {}
    for path in modules:
        with open(path, encoding="utf-8") as handle:
            metadata = json.load(handle)
        for variant in metadata.get("variants", []):
            for entry in variant.get("files", []):
                name, sha = entry.get("name"), entry.get("sha256")
                if not name or not sha:
                    continue
                if checksums.get(name, sha) != sha:
                    raise ValueError(f"В .module противоречивые sha256 для {name}")
                checksums[name] = sha
    return checksums


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


COMPONENT_OPEN = re.compile(
    r'^(?P<indent>\s*)<component group="(?P<g>[^"]+)" name="(?P<n>[^"]+)" version="(?P<v>[^"]+)">\s*$'
)
COMPONENT_ONE_LINE = re.compile(r"^\s*<component .*</component>\s*$")
ARTIFACT_OPEN = re.compile(r'^\s*<artifact name="([^"]+)">\s*$')


def _component_blocks(lines: list[str]):
    """Yields (start, end, key) for each component block; end is the closing-tag index."""
    index = 0
    while index < len(lines):
        match = COMPONENT_OPEN.match(lines[index])
        if match:
            end = index + 1
            while end < len(lines) and lines[end].strip() != "</component>":
                end += 1
            if end >= len(lines):
                raise ValueError(f"Не закрыт <component> в строке {index + 1}")
            key = (match.group("g"), match.group("n"), match.group("v"))
            yield index, end, key, match.group("indent")
            index = end + 1
        else:
            if COMPONENT_ONE_LINE.match(lines[index]):
                raise ValueError(
                    f"Компонента в одну строку (строка {index + 1}): формат не поддерживается, "
                    "перегенерируйте файл через --write-verification-metadata."
                )
            index += 1


def is_descriptor_file(filename: str) -> bool:
    return filename.endswith((".module", ".pom"))


def is_descriptor(name: str, request: Request) -> bool:
    """Дескриптор модуля: `<name>-<version>.module` или `.pom` этого же модуля."""
    return name in {f"{request.name}-{request.version}.module", f"{request.name}-{request.version}.pom"}


def recorded_names(lines: list[str], start: int, end: int) -> set[str]:
    return {m.group(1) for line in lines[start:end] if (m := ARTIFACT_OPEN.match(line))}


def artifact_lines(indent: str, filename: str, sha: str) -> list[str]:
    inner = indent + "   "
    return [
        f'{indent}<artifact name="{filename}">',
        f'{inner}<sha256 value="{sha}" origin="{ORIGIN}"/>',
        f"{indent}</artifact>",
    ]


def insert_artifact(lines: list[str], request: Request, sha: str) -> list[str]:
    """Вставляет артефакт в уже существующую компоненту текстом, не трогая остальные строки.

    Компоненты без дескриптора сюда не доходят (см. plan), поэтому создание новой компоненты
    намеренно не реализовано: её формирует Gradle.
    """
    key = (request.group, request.name, request.version)
    for start, end, component_key, indent in _component_blocks(lines):
        if component_key == key:
            return lines[:end] + artifact_lines(indent + "   ", request.filename, sha) + lines[end:]
    raise KeyError(f"Компоненты {request.coords} нет в metadata: запись невозможна")


def plan(requests: set[Request], cache: str, lines: list[str]) -> tuple[Summary, list[tuple[Request, str]]]:
    """Решает, что делать с каждым запросом, не меняя файл."""
    summary = Summary()
    to_insert: list[tuple[Request, str]] = []
    existing = {}
    for start, end, key, _indent in _component_blocks(lines):
        existing[key] = recorded_names(lines, start, end)

    for request in sorted(requests, key=lambda r: (r.group, r.name, r.version, r.filename)):
        names = existing.get((request.group, request.name, request.version), set())
        if request.filename in names:
            summary.already.append(request)
            continue
        # Дескриптор (.module/.pom) сам себя подтвердить не может: в нём нет sha256 его
        # собственного содержимого. Такой checksum даёт только Gradle при скачивании из
        # репозитория, поэтому инструмент его не пишет вовсе.
        if is_descriptor_file(request.filename):
            summary.gradle_only.append(request)
            continue
        # Gradle при резолве документации сначала проверяет дескриптор модуля (.module/.pom).
        # Записать sources без него — получить падение верификации на дескрипторе, а не на
        # исходниках. Дескриптор сам инструмент записать не может (у него нет источника
        # подтверждения), поэтому такие запросы отдаём Gradle целиком.
        if not any(is_descriptor(n, request) for n in names):
            summary.no_descriptor.append(request)
            continue
        path = locate(cache, request)
        if path is None:
            summary.missing_in_cache.append(request)
            continue
        checksums = module_checksums(cache, request)
        if not checksums or request.filename not in checksums:
            summary.unverifiable.append(request)
            continue
        actual = sha256_of(path)
        expected = checksums[request.filename]
        if actual != expected:
            summary.mismatched.append((request, expected, actual))
            continue
        to_insert.append((request, actual))
        summary.added.append(request)
    return summary, to_insert


def read(path: str) -> str:
    with open(path, encoding="utf-8", errors="ignore") as handle:
        return handle.read()


def find_ide_log() -> str | None:
    """Самый свежий idea.log среди установленных версий Android Studio (macOS и Linux)."""
    candidates = glob.glob(os.path.expanduser("~/Library/Logs/Google/AndroidStudio*/idea.log"))
    candidates += glob.glob(os.path.expanduser("~/.cache/Google/AndroidStudio*/log/idea.log"))
    candidates = [path for path in candidates if os.path.isfile(path)]
    return max(candidates, key=os.path.getmtime) if candidates else None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--log", help="путь к idea.log (по умолчанию ищется сам)")
    parser.add_argument("--report", action="append", default=[], help="HTML-отчёт Gradle; можно несколько")
    parser.add_argument("--reports", default=DEFAULT_REPORTS, help="glob отчётов, если --report не задан")
    parser.add_argument("--metadata", default=DEFAULT_META)
    parser.add_argument("--cache", default=DEFAULT_CACHE)
    parser.add_argument("--artifact", action="append", default=[],
                        help="вручную: group:name:version:filename")
    args = parser.parse_args(argv)

    if not os.path.isfile(args.metadata):
        print(f"{args.metadata} не найден: запускайте из корня репозитория.", file=sys.stderr)
        return EXIT_SKIPPED

    requests: set[Request] = set()
    report_paths = args.report or sorted(glob.glob(args.reports))
    for path in report_paths:
        found = parse_report(read(path))
        print(f"отчёт {path}: {len(found)} запросов")
        requests |= found

    log_path = args.log or find_ide_log()
    if log_path and os.path.isfile(log_path):
        found = parse_log(read(log_path))
        print(f"лог {log_path}: {len(found)} запросов")
        requests |= found

    for item in args.artifact:
        head, _, filename = item.rpartition(":")
        coords = parse_coords(head)
        if not coords or not filename:
            print(f"--artifact ожидает group:name:version:filename, получено {item!r}", file=sys.stderr)
            return EXIT_SKIPPED
        requests.add(Request(filename, *coords))

    if not requests:
        print("Запросов от Gradle/IDE не найдено: sync, вероятно, уже проходит.")
        return EXIT_OK

    lines = read(args.metadata).split("\n")
    summary, to_insert = plan(requests, args.cache, lines)

    for request, expected, actual in summary.mismatched:
        print(f"ОТКАЗ: checksum не совпал: {request.filename} ({request.coords})\n"
              f"  .module: {expected}\n  файл:    {actual}", file=sys.stderr)
    if summary.mismatched:
        print("Ничего не записано: разберитесь с расхождением до повторного запуска.", file=sys.stderr)
        return EXIT_MISMATCH

    if to_insert:
        for request, sha in to_insert:
            lines = insert_artifact(lines, request, sha)
        with open(args.metadata, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(lines))

    print(f"добавлено: {len(summary.added)}, уже было: {len(summary.already)}, "
          f"нет в кэше: {len(summary.missing_in_cache)}, без подтверждения: {len(summary.unverifiable)}, "
          f"без дескриптора модуля: {len(summary.no_descriptor)}, "
          f"дескрипторы только через Gradle: {len(summary.gradle_only)}")
    for request in summary.missing_in_cache:
        print(f"  - нет в кэше: {request.filename} ({request.coords})")
    for request in summary.unverifiable:
        print(f"  - нет подтверждения (.module/sha256): {request.filename} ({request.coords})")
    for request in summary.no_descriptor:
        print(f"  - нет дескриптора модуля в metadata: {request.filename} ({request.coords})")
    for request in summary.gradle_only:
        print(f"  - дескриптор записывает только Gradle: {request.filename} ({request.coords})")
    if summary.missing_in_cache or summary.unverifiable or summary.no_descriptor or summary.gradle_only:
        print("Такие артефакты запишет Gradle: ./gradlew --write-verification-metadata sha256 "
              "recordIdeIndexingArtifacts")
        return EXIT_SKIPPED
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
