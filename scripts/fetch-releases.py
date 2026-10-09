#!/usr/bin/env python3
"""Copies the GitHub release files into the documentation site and writes its Download page.

For every published release of the repository, the release assets are downloaded to
docs/downloads/<version>/, a SHA-256 checksum is computed for each, and
docs/downloads/releases.md is written: the latest version with download buttons and release
notes, a table of its files, and the older versions. docs/download.md includes that file, so the
site serves the files itself and users never need to visit GitHub.

    python scripts/fetch-releases.py

Run it before `mkdocs build`. Set GITHUB_TOKEN to avoid the API's rate limit for anonymous
requests (the docs workflow does). GITHUB_REPOSITORY overrides the repository.
"""
import hashlib
import json
import os
import pathlib
import shutil
import urllib.request

REPO = os.environ.get("GITHUB_REPOSITORY", "fsmutimeer/migrax")
ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "downloads"

DESCRIPTIONS = [
    ("migrax-spring-boot-starter-", "Spring Boot startup integration"),
    ("migrax-quarkus-", "Quarkus startup integration"),
    ("migrax-micronaut-", "Micronaut startup integration"),
    ("migrax-helidon-", "Helidon MP startup integration"),
    ("migrax-gradle-plugin-", "Gradle plugin"),
]


def request(url, accept="application/vnd.github+json"):
    headers = {"Accept": accept, "User-Agent": "migrax-docs"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120)


def describe(name):
    if name.endswith(".zip"):
        return "**Command line tool** with installers for Windows, macOS and Linux"
    for prefix, text in DESCRIPTIONS:
        if name.startswith(prefix):
            return text
    return "Core library and Maven plugin"


def size(n):
    return f"{n / 1024:.0f} KB" if n < 1024 * 1024 else f"{n / 1024 / 1024:.1f} MB"


def download(url, target):
    digest = hashlib.sha256()
    with request(url, "application/octet-stream") as response, open(target, "wb") as out:
        while chunk := response.read(1 << 16):
            digest.update(chunk)
            out.write(chunk)
    return digest.hexdigest()


def main():
    with request(f"https://api.github.com/repos/{REPO}/releases?per_page=100") as response:
        releases = [r for r in json.load(response) if not r["draft"]]
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)

    stable = [r for r in releases if not r["prerelease"]]
    latest = stable[0] if stable else (releases[0] if releases else None)
    lines = []
    if latest is None:
        lines.append('!!! info "No release yet"\n\n    The first release will appear here.\n')
    # The newest pre-release, when it is newer than the latest stable release, for people who
    # want to test it. Earlier pre-releases are listed with the older versions.
    testing = ([r for r in releases[:releases.index(latest)] if r["prerelease"]][:1]
               if latest else [])
    older = [r for r in releases if r is not latest and r not in testing]
    # The latest stable release first (it opens the page), then pre-releases, then the rest.
    ordered = ([latest] if latest else []) + testing + older
    for release in ordered:
        version = release["tag_name"].lstrip("v")
        folder = OUT / version
        folder.mkdir()
        files = []
        for asset in sorted(release["assets"], key=lambda a: (not a["name"].endswith(".zip"),
                                                             a["name"])):
            checksum = download(asset["url"], folder / asset["name"])
            files.append((asset["name"], asset["size"], checksum))
        (folder / "SHA256SUMS").write_text(
            "".join(f"{c}  {n}\n" for n, _, c in files), encoding="utf-8", newline="\n")
        date = release["published_at"][:10]
        zip_name = next((n for n, _, _ in files if n.endswith(".zip")), None)

        if release is latest:
            lines.append(f"## Migrax {version}\n")
            lines.append(f"Latest release, published {date}.\n")
            if zip_name:
                lines.append(f"[:material-download: Download {zip_name}](downloads/{version}/"
                             f"{zip_name}){{ .md-button .md-button--primary }}\n")
                latest_dir = OUT / "latest"
                latest_dir.mkdir(exist_ok=True)
                shutil.copyfile(folder / zip_name, latest_dir / "migrax.zip")
            lines.append("Then follow [Installation](getting-started/installation.md).\n")
            lines.append("| File | What it is | Size | SHA-256 |")
            lines.append("|---|---|---|---|")
            for name, n, checksum in files:
                lines.append(f"| [`{name}`](downloads/{version}/{name}) | {describe(name)} | "
                             f"{size(n)} | <small>`{checksum}`</small> |")
            lines.append(f"\nAll checksums: [`SHA256SUMS`](downloads/{version}/SHA256SUMS)\n")
            notes = (release.get("body") or "").strip()
            if notes:
                indented = "\n".join("    " + line if line else "" for line in notes.splitlines())
                lines.append(f'??? note "What\'s new in {version}"\n\n{indented}\n')
        elif release in testing:
            lines.append(f"## Pre-release {version}\n")
            lines.append(f"Published {date}, for testing the next version. It may still change "
                         "before the release; please [report problems]"
                         f"(https://github.com/{REPO}/issues).\n")
            if zip_name:
                lines.append(f"[:material-download: Download {zip_name}](downloads/{version}/"
                             f"{zip_name}){{ .md-button }}\n")
            lines.append("It installs like a release, over the version you have; install the "
                         "latest release again to go back.\n")
            lines.append("| File | What it is | Size | SHA-256 |")
            lines.append("|---|---|---|---|")
            for name, n, checksum in files:
                lines.append(f"| [`{name}`](downloads/{version}/{name}) | {describe(name)} | "
                             f"{size(n)} | <small>`{checksum}`</small> |")
            lines.append(f"\nAll checksums: [`SHA256SUMS`](downloads/{version}/SHA256SUMS)\n")
            notes = (release.get("body") or "").strip()
            if notes:
                indented = "\n".join("    " + line if line else "" for line in notes.splitlines())
                lines.append(f'??? note "What\'s new in {version}"\n\n{indented}\n')
        else:
            if release is older[0]:
                lines.append("## Older versions\n")
                lines.append("| Version | Published | Command line tool | All files |")
                lines.append("|---|---|---|---|")
            tool = f"[`{zip_name}`](downloads/{version}/{zip_name})" if zip_name else ""
            label = version + (" (pre-release)" if release["prerelease"] else "")
            lines.append(f"| {label} | {date} | {tool} | "
                         f"[`SHA256SUMS`](downloads/{version}/SHA256SUMS) |")

    (OUT / "releases.md").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(releases)} release(s) written to {OUT.relative_to(ROOT)}"
          + (f"; latest {latest['tag_name']}" if latest else ""))


if __name__ == "__main__":
    main()
