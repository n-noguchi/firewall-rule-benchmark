#!/usr/bin/env python3
"""Deterministically generate firewall benchmark master data and access logs."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import shutil
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Iterator

try:
    import pyarrow as pa
    import pyarrow.parquet as pq
except ModuleNotFoundError as exc:  # pragma: no cover - setup failure path
    raise SystemExit("pyarrow is required; install data-generator/requirements.txt") from exc


MAX_FILE_BYTES = 48 * 1024 * 1024
TIMESTAMP = "2026-07-18T00:00:00Z"
CSV_TABLES = {
    "policies": [
        "policy_id", "tenant_id", "department_id", "default_action", "enabled", "created_at", "updated_at"
    ],
    "firewall_rules": [
        "rule_id", "policy_id", "priority", "rule_type", "action", "enabled", "regex_pattern",
        "source_ip_group_key", "start_time_utc", "end_time_utc", "created_at", "updated_at",
    ],
    "source_ip_groups": [
        "group_id", "tenant_id", "department_id", "group_key", "name", "created_at", "updated_at"
    ],
    "source_ip_group_members": ["group_id", "source_ipv4", "created_at", "updated_at"],
}


@dataclass(frozen=True)
class Profile:
    name: str
    tenant_count: int
    large_tenant_count: int
    large_rule_count: int
    normal_rule_count: int
    department_policy_count: int
    access_count: int


PROFILES = {
    "development": Profile("development", 24, 2, 60, 6, 12, 720),
    "release": Profile("release", 20_000, 20, 250_000, 6, 1_000, 25_000),
}


class SplitCSVWriter:
    """RFC 4180 CSV writer which starts a new part before the cap is exceeded."""

    def __init__(self, directory: Path, stem: str, fields: list[str], max_bytes: int = MAX_FILE_BYTES) -> None:
        self.directory = directory
        self.stem = stem
        self.fields = fields
        self.max_bytes = max_bytes
        self.part = -1
        self.rows_by_path: dict[Path, int] = {}
        self._file = None
        self._writer = None
        self._path: Path | None = None

    def _open(self) -> None:
        if self._file is not None:
            self._file.close()
        self.part += 1
        self._path = self.directory / f"{self.stem}-{self.part:05d}.csv"
        self._file = self._path.open("w", encoding="utf-8", newline="")
        self._writer = csv.DictWriter(self._file, fieldnames=self.fields, lineterminator="\n")
        self._writer.writeheader()
        self._file.flush()
        self.rows_by_path[self._path] = 0

    def write(self, row: dict[str, object]) -> None:
        # csv.writer has no dry-run mode. A conservative maximum row size keeps every file below the limit.
        serialized = ",".join(str(row.get(field, "")) for field in self.fields)
        if self._file is None:
            self._open()
        assert self._file is not None and self._writer is not None and self._path is not None
        if self._file.tell() + len(serialized.encode("utf-8")) + len(self.fields) * 2 + 8 > self.max_bytes:
            self._open()
        self._writer.writerow(row)
        self.rows_by_path[self._path] += 1

    def close(self) -> dict[Path, int]:
        if self._file is not None:
            self._file.close()
            self._file = None
        return self.rows_by_path


def tenant_id(number: int) -> str:
    return f"tenant-{number:05d}"


def department_id(number: int) -> str:
    return f"department-{number:05d}"


def policy_id(scope: str, number: int) -> str:
    return f"policy-{scope}-{number:05d}"


def ipv4_for_tenant(number: int, member: int) -> str:
    # 10/8 private range; all octets are valid and the mapping is deterministic.
    return f"10.{(number // 256) % 256}.{number % 256}.{member + 10}"


def default_action(number: int) -> str:
    return "ALLOW" if number % 2 else "DENY"


def policy_row(scope: str, number: int, department: str = "") -> dict[str, object]:
    return {
        "policy_id": policy_id(scope, number), "tenant_id": tenant_id(number), "department_id": department,
        "default_action": default_action(number), "enabled": "true", "created_at": TIMESTAMP, "updated_at": TIMESTAMP,
    }


def group_rows(number: int, include_department: bool) -> Iterator[tuple[dict[str, object], dict[str, object]]]:
    tenant_group_id = f"group-tenant-office-{number:05d}"
    yield (
        {"group_id": tenant_group_id, "tenant_id": tenant_id(number), "department_id": "", "group_key": "office",
         "name": f"Tenant {number} office", "created_at": TIMESTAMP, "updated_at": TIMESTAMP},
        {"group_id": tenant_group_id, "source_ipv4": ipv4_for_tenant(number, 0), "created_at": TIMESTAMP, "updated_at": TIMESTAMP},
    )
    # A second member verifies complete string matching, instead of a prefix-like group implementation.
    yield (
        None,
        {"group_id": tenant_group_id, "source_ipv4": ipv4_for_tenant(number, 1), "created_at": TIMESTAMP, "updated_at": TIMESTAMP},
    )
    if include_department:
        department_group_id = f"group-department-office-{number:05d}"
        yield (
            {"group_id": department_group_id, "tenant_id": tenant_id(number), "department_id": department_id(number),
             "group_key": "office", "name": f"Department {number} office", "created_at": TIMESTAMP, "updated_at": TIMESTAMP},
            {"group_id": department_group_id, "source_ipv4": f"172.16.{number % 256}.20", "created_at": TIMESTAMP, "updated_at": TIMESTAMP},
        )
        # This group makes the existence of a department group *set* observable even for an unknown key.
        yield (
            {"group_id": f"group-department-vip-{number:05d}", "tenant_id": tenant_id(number),
             "department_id": department_id(number), "group_key": "vip", "name": f"Department {number} VIP",
             "created_at": TIMESTAMP, "updated_at": TIMESTAMP},
            {"group_id": f"group-department-vip-{number:05d}", "source_ipv4": f"172.16.{number % 256}.21",
             "created_at": TIMESTAMP, "updated_at": TIMESTAMP},
        )


def normal_rules(number: int) -> Iterator[dict[str, object]]:
    policy = policy_id("tenant", number)
    suffix = number % 256
    values = [
        ("SOURCE_IPV4_REGEX", "DENY", rf"^192\.0\.2\.{suffix}$", "", "", ""),
        ("SOURCE_IPV4_GROUP", "ALLOW", "", "office", "", ""),
        ("URL_PATH_REGEX", "DENY", rf"^/{tenant_id(number)}/{department_id(number)}/api/private/.*$", "", "", ""),
        ("ACCESS_TIME_RANGE", "DENY", "", "", "22:00:00", "06:00:00"),
        ("REFERER_REGEX", "ALLOW", r"^https://trusted\.example/", "", "", ""),
        ("USER_AGENT_REGEX", "DENY", r"BenchmarkBot", "", "", ""),
    ]
    for priority, (rule_type, action, pattern, group_key, start, end) in enumerate(values, 1):
        yield rule_row(f"rule-tenant-{number:05d}-{priority:06d}", policy, priority, rule_type, action, pattern, group_key, start, end)


def department_rules(number: int) -> Iterator[dict[str, object]]:
    if number % 5 == 0:
        return
    policy = policy_id("department", number)
    values = [
        ("USER_AGENT_REGEX", "ALLOW", r"DepartmentBot", "", "", ""),
        ("SOURCE_IPV4_GROUP", "DENY", "", "office", "", ""),
        ("ACCESS_TIME_RANGE", "ALLOW", "", "", "22:00:00", "06:00:00"),
    ]
    for priority, (rule_type, action, pattern, group_key, start, end) in enumerate(values, 1):
        yield rule_row(f"rule-department-{number:05d}-{priority:06d}", policy, priority, rule_type, action, pattern, group_key, start, end)


def large_rules(number: int, count: int) -> Iterator[dict[str, object]]:
    """Keep rule one an early match; later rules exercise all six supported rule types."""
    policy = policy_id("tenant", number)
    yield rule_row(
        f"rule-tenant-{number:05d}-000001", policy, 1, "SOURCE_IPV4_REGEX", "DENY",
        rf"^198\.51\.100\.{number}$", "", "", "",
    )
    kinds = ("SOURCE_IPV4_GROUP", "URL_PATH_REGEX", "ACCESS_TIME_RANGE", "REFERER_REGEX", "USER_AGENT_REGEX", "SOURCE_IPV4_REGEX")
    for priority in range(2, count + 1):
        kind = kinds[(priority - 2) % len(kinds)]
        unique = priority
        if kind == "SOURCE_IPV4_GROUP":
            values = ("", "office", "", "")
        elif kind == "URL_PATH_REGEX":
            values = (rf"^/{tenant_id(number)}/{department_id(number)}/never/{unique}$", "", "", "")
        elif kind == "ACCESS_TIME_RANGE":
            values = ("", "", "12:34:00", "12:35:00")
        elif kind == "REFERER_REGEX":
            values = (rf"^https://never\.example/{unique}$", "", "", "")
        elif kind == "USER_AGENT_REGEX":
            values = (rf"^NeverAgent/{unique}$", "", "", "")
        else:
            values = (rf"^203\.0\.113\.{unique % 256}$", "", "", "")
        yield rule_row(
            f"rule-tenant-{number:05d}-{priority:06d}", policy, priority, kind,
            "ALLOW" if priority % 2 else "DENY", *values,
        )


def rule_row(rule_id: str, policy: str, priority: int, rule_type: str, action: str, pattern: str, group_key: str, start: str, end: str) -> dict[str, object]:
    return {
        "rule_id": rule_id, "policy_id": policy, "priority": priority, "rule_type": rule_type, "action": action,
        "enabled": "true", "regex_pattern": pattern, "source_ip_group_key": group_key,
        "start_time_utc": start, "end_time_utc": end, "created_at": TIMESTAMP, "updated_at": TIMESTAMP,
    }


def access_rows(profile: Profile) -> Iterator[dict[str, object]]:
    for offset in range(profile.access_count):
        number = (offset % profile.tenant_count) + 1
        is_department = number <= profile.department_policy_count and offset % 3 != 0
        # A non-department-policy tenant still uses its own department identifier, so URL_PATH_REGEX
        # rules can match it. For a tenant with a department policy, select another department only
        # when intentionally testing tenant-policy fallback.
        department = department_id(number)
        if number <= profile.department_policy_count and not is_department:
            department = department_id((number % profile.tenant_count) + 1)
        case = offset % 8
        source = f"203.0.113.{(offset % 240) + 1}"
        path_suffix = f"api/users/{offset}"
        timestamp = "2026-07-18T12:00:00Z"
        referer = ""
        user_agent = "Mozilla/5.0 Benchmark/1.0"

        if number <= profile.large_tenant_count:
            # Most accesses prove first-match short circuiting; one in eight forces a late/default path.
            source = f"198.51.100.{number}" if case else "203.0.113.250"
        elif is_department:
            if case == 0:
                user_agent = "DepartmentBot/1.0"
            elif case == 1:
                source = f"172.16.{number % 256}.20"
            elif case == 2:
                timestamp = "2026-07-18T23:30:00Z"
        else:
            if case == 0:
                source = f"192.0.2.{number % 256}"
            elif case == 1:
                source = ipv4_for_tenant(number, 0)
            elif case == 2:
                path_suffix = f"api/private/{offset}"
            elif case == 3:
                timestamp = "2026-07-18T23:30:00Z"
            elif case == 4:
                referer = "https://trusted.example/from-test"
            elif case == 5:
                user_agent = "BenchmarkBot/2.0"
        yield {
            "access_id": f"access-{offset + 1:07d}", "source_ipv4": source,
            "url_path": f"/{tenant_id(number)}/{department}/{path_suffix}", "access_timestamp_utc": timestamp,
            "referer": referer, "user_agent": user_agent,
        }


def write_lf_text(path: Path, content: str) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write(content)


def write_dataset_readme(destination: Path, profile: Profile) -> None:
    write_lf_text(destination / "README.md", f"""# Firewall benchmark dataset: {profile.name}-v1

This directory is a versioned, immutable input dataset. It is generated by `data-generator/generate.py` and is intended to be committed to Git. Every payload file is kept below 48 MiB.

## Format decisions

* Master data is UTF-8, BOM-free, LF-terminated RFC 4180 CSV. A blank nullable field represents NULL.
* Access logs are Apache Parquet, one or more `access_logs-*.parquet` files. All columns are required UTF-8 strings: `access_id`, `source_ipv4`, `url_path`, `access_timestamp_utc`, `referer`, `user_agent`.
* Input rows are the union of all Parquet parts, in lexicographic filename order.
* `expected_results` is not created by this generator. It is produced by the independent reference implementation and uses the required four-column CSV layout.
* `enabled=false` is deliberately absent because its evaluation semantics remain undecided in README section 28. All rows have `enabled=true`.
* URL paths are `/{{tenant_id}}/{{department_id}}/...`; identifiers use lowercase ASCII letters, digits, and hyphens.
* Times use RFC 3339 UTC timestamps and `HH:MM:SS` time-of-day fields.

## Directory layout

```text
policies/policies-*.csv
firewall_rules/firewall_rules-*.csv
source_ip_groups/source_ip_groups-*.csv
source_ip_group_members/source_ip_group_members-*.csv
access_logs/access_logs-*.parquet
expected_results/expected_results-*.csv  # supplied after reference evaluation
manifest.json
```

This profile has {profile.tenant_count:,} tenant policies, {profile.large_tenant_count:,} largest tenants, and {profile.large_rule_count:,} rules in each largest tenant. The development profile is intentionally small enough to review and execute in CI, while exercising the same semantics.
""")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def emit_manifest(destination: Path, profile: Profile, rows: dict[Path, int]) -> None:
    files = []
    for path in sorted(destination.rglob("*")):
        if path.is_file() and path.name not in {"manifest.json", "README.md"}:
            files.append({
                "path": path.relative_to(destination).as_posix(), "bytes": path.stat().st_size,
                "sha256": sha256(path), "rows": rows.get(path),
            })
    manifest = {
        "dataset_version": f"{profile.name}-v1", "generator": "data-generator/generate.py",
        "generated_at": TIMESTAMP, "profile": profile.__dict__, "max_file_bytes": MAX_FILE_BYTES, "files": files,
    }
    write_lf_text(destination / "manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")


def collect_row_counts(destination: Path) -> dict[Path, int]:
    counts: dict[Path, int] = {}
    for path in destination.rglob("*"):
        if not path.is_file() or path.name in {"manifest.json", "README.md"}:
            continue
        if path.suffix == ".csv":
            with path.open("r", encoding="utf-8", newline="") as stream:
                counts[path] = max(0, sum(1 for _ in csv.reader(stream)) - 1)
        elif path.suffix == ".parquet":
            counts[path] = pq.ParquetFile(path).metadata.num_rows
    return counts


def verify_dataset(destination: Path) -> None:
    manifest_path = destination / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError(f"manifest does not exist: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    listed = {entry["path"]: entry for entry in manifest.get("files", [])}
    actual = {
        path.relative_to(destination).as_posix(): path
        for path in destination.rglob("*")
        if path.is_file() and path.name not in {"manifest.json", "README.md"}
    }
    if set(listed) != set(actual):
        missing = sorted(set(listed) - set(actual))
        extra = sorted(set(actual) - set(listed))
        raise ValueError(f"manifest paths differ (missing={missing}, extra={extra})")
    row_counts = collect_row_counts(destination)
    for relative, entry in listed.items():
        path = actual[relative]
        if path.stat().st_size > MAX_FILE_BYTES:
            raise ValueError(f"file exceeds size limit: {relative}")
        if entry.get("bytes") != path.stat().st_size or entry.get("sha256") != sha256(path):
            raise ValueError(f"manifest hash or size mismatch: {relative}")
        if entry.get("rows") != row_counts.get(path):
            raise ValueError(f"manifest row count mismatch: {relative}")


def generate(profile: Profile, destination: Path, force: bool) -> None:
    if destination.exists():
        if not force:
            raise SystemExit(f"output already exists: {destination} (use --force to replace it)")
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    rows: dict[Path, int] = {}
    writers = {
        name: SplitCSVWriter(destination / name, name, fields)
        for name, fields in CSV_TABLES.items()
    }
    for name in writers:
        (destination / name).mkdir()

    try:
        for number in range(1, profile.tenant_count + 1):
            writers["policies"].write(policy_row("tenant", number))
            if number <= profile.department_policy_count:
                writers["policies"].write(policy_row("department", number, department_id(number)))
            for group, member in group_rows(number, number <= profile.department_policy_count):
                if group is not None:
                    writers["source_ip_groups"].write(group)
                writers["source_ip_group_members"].write(member)
            rule_iter = large_rules(number, profile.large_rule_count) if number <= profile.large_tenant_count else normal_rules(number)
            for rule in rule_iter:
                writers["firewall_rules"].write(rule)
            if number <= profile.department_policy_count:
                for rule in department_rules(number):
                    writers["firewall_rules"].write(rule)
    finally:
        for writer in writers.values():
            rows.update(writer.close())

    access_dir = destination / "access_logs"
    access_dir.mkdir()
    access = list(access_rows(profile))
    # Fixed row groups make Parquet output stable for a pinned pyarrow version and easy to read in chunks.
    table = pa.Table.from_pylist(access)
    parquet_path = access_dir / "access_logs-00000.parquet"
    pq.write_table(table, parquet_path, compression="zstd", row_group_size=10_000, version="2.6")
    if parquet_path.stat().st_size > MAX_FILE_BYTES:
        parquet_path.unlink()
        for part, start in enumerate(range(0, len(access), 10_000)):
            path = access_dir / f"access_logs-{part:05d}.parquet"
            pq.write_table(pa.Table.from_pylist(access[start:start + 10_000]), path, compression="zstd", version="2.6")
            rows[path] = min(10_000, len(access) - start)
    else:
        rows[parquet_path] = len(access)
    (destination / "expected_results").mkdir()
    write_dataset_readme(destination, profile)
    emit_manifest(destination, profile, rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=sorted(PROFILES), default="development")
    parser.add_argument("--output", type=Path, required=True, help="dataset directory to create")
    parser.add_argument("--force", action="store_true", help="replace an existing output directory")
    parser.add_argument("--refresh-manifest", action="store_true", help="re-hash an existing dataset after expected results are added")
    parser.add_argument("--verify", action="store_true", help="verify manifest hashes, row counts, and size limits")
    args = parser.parse_args()
    output = args.output.resolve()
    if args.refresh_manifest and args.verify:
        raise SystemExit("--refresh-manifest and --verify cannot be used together")
    if args.verify:
        verify_dataset(output)
    elif args.refresh_manifest:
        manifest_path = output / "manifest.json"
        if not manifest_path.is_file():
            raise SystemExit(f"manifest does not exist: {manifest_path}")
        prior = json.loads(manifest_path.read_text(encoding="utf-8"))
        profile = Profile(**prior["profile"])
        emit_manifest(output, profile, collect_row_counts(output))
    else:
        generate(PROFILES[args.profile], output, args.force)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
