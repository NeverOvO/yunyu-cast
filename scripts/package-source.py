#!/usr/bin/env python3
"""Package committed project sources and pinned native dependencies for a Release."""
import argparse
import io
import subprocess
import tarfile
from pathlib import Path


def git(*args, cwd=None):
    return subprocess.check_output(["git", *args], cwd=cwd).decode().strip()


def append_git_archive(target, repo, revision, prefix):
    process = subprocess.Popen(
        ["git", "archive", "--format=tar", f"--prefix={prefix}", revision],
        cwd=repo, stdout=subprocess.PIPE,
    )
    try:
        with tarfile.open(fileobj=process.stdout, mode="r|") as source:
            for member in source:
                target.addfile(member, source.extractfile(member) if member.isfile() else None)
    finally:
        process.stdout.close()
    if process.wait() != 0:
        raise RuntimeError(f"Could not archive {repo} at {revision}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default="HEAD")
    parser.add_argument("--version", required=True)
    parser.add_argument("--openssl-archive", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.openssl_archive.is_file():
        parser.error("The original OpenSSL 3.4.4 source archive is required.")
    if args.output.exists():
        parser.error("Output already exists; choose a new file.")
    if any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-" for c in args.version):
        parser.error("Version must contain only letters, digits, dots and hyphens.")
    root = Path(__file__).resolve().parent.parent
    commit = git("rev-parse", args.ref, cwd=root)
    prefix = f"yunyu-cast-{args.version}/"
    modules = []
    for row in git("ls-tree", "-r", commit, cwd=root).splitlines():
        metadata, path = row.split("\t", 1)
        mode, kind, sha = metadata.split()
        if mode == "160000":
            modules.append((path, sha))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(args.output.name + ".partial")
    try:
        with tarfile.open(temporary, mode="w:gz", compresslevel=6) as target:
            append_git_archive(target, root, commit, prefix)
            for path, sha in modules:
                print(f"Including pinned source: {path}", flush=True)
                append_git_archive(target, root / path, sha, prefix + path + "/")
            target.add(
                args.openssl_archive,
                arcname=prefix + "third_party-sources/openssl-3.4.4.tar.gz",
                recursive=False,
                filter=lambda member: normalize_extra(member),
            )
            versions = [f"Project commit: {commit}"] + [f"{path}: {sha}" for path, sha in modules]
            versions += ["OpenSSL: 3.4.4; original archive in third_party-sources/", "See docs/SOURCE_BUILD.md before building this source archive."]
            data = ("\n".join(versions) + "\n").encode()
            entry = tarfile.TarInfo(prefix + "SOURCE_VERSION.txt")
            entry.size = len(data)
            entry.mode = 0o644
            target.addfile(entry, io.BytesIO(data))
        temporary.replace(args.output)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
    print(f"Source archive: {args.output} ({args.output.stat().st_size} bytes)")


def normalize_extra(member):
    member.uid = member.gid = 0
    member.uname = member.gname = ""
    member.mode = 0o644
    return member


if __name__ == "__main__":
    main()
