#!/usr/bin/env python3
"""Carry the verified production yaci-core patch into a freshly built Boot JAR."""

import argparse
import hashlib
from pathlib import Path
import tempfile
import zipfile


CORE_ENTRY = "BOOT-INF/lib/yaci-core-0.4.6.jar"
# Production 3.0.0-beta4-cf1b0d4-y1: TransactionBodyExtractor.getLength's
# ONE_BYTE branch uses symbols[0] & 0xFF, preserving definite arrays of 128-255 txs.
PATCHED_CORE_SHA256 = "e48f263bfc09df714de1945a0c35496570161b82ec3ccac20204b745bc9f4669"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def unique_entry(archive, name):
    matches = [entry for entry in archive.infolist() if entry.filename == name]
    if len(matches) != 1:
        raise ValueError(f"Expected one {name}, found {len(matches)}")
    return matches[0]


def preserve(build_jar, deployed_jar, output_jar):
    if output_jar.resolve() in (build_jar.resolve(), deployed_jar.resolve()):
        raise ValueError("Output must be separate from both input JARs")
    with zipfile.ZipFile(deployed_jar) as deployed:
        patched_core = deployed.read(unique_entry(deployed, CORE_ENTRY))
    if sha256(patched_core) != PATCHED_CORE_SHA256:
        raise ValueError("Deployed JAR does not contain the verified unsigned-count patch")

    output_jar.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output_jar.parent) as staging:
        staged_jar = Path(staging) / "yaci-store.jar"
        with zipfile.ZipFile(build_jar) as built, zipfile.ZipFile(staged_jar, "w") as output:
            unique_entry(built, CORE_ENTRY)
            output.comment = built.comment
            for entry in built.infolist():
                data = built.read(entry)
                if entry.filename == CORE_ENTRY:
                    data = patched_core
                    # Spring Boot nested JARs must be stored, not compressed.
                    entry.compress_type = zipfile.ZIP_STORED
                output.writestr(entry, data)

        with zipfile.ZipFile(build_jar) as built, zipfile.ZipFile(staged_jar) as output:
            core = unique_entry(output, CORE_ENTRY)
            if core.compress_type != zipfile.ZIP_STORED or sha256(output.read(core)) != PATCHED_CORE_SHA256:
                raise ValueError("Output failed patched-core validation")
            if built.namelist() != output.namelist():
                raise ValueError("Output changed archive contents")
            for entry in built.infolist():
                if entry.filename != CORE_ENTRY and built.read(entry) != output.read(entry.filename):
                    raise ValueError(f"Output changed {entry.filename}")
        staged_jar.replace(output_jar)
    print(f"Verified patched core: {PATCHED_CORE_SHA256}")
    print(f"Output: {output_jar} (sha256 {sha256(output_jar.read_bytes())})")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-jar", type=Path, required=True)
    parser.add_argument("--deployed-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    preserve(args.build_jar, args.deployed_jar, args.output)


if __name__ == "__main__":
    main()
