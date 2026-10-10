"""Writes llms.txt and llms-full.txt into the built docs site, for AI assistants.

llms.txt is an index: one line per page with its link and first sentence, grouped like the
site's navigation (the format described at https://llmstxt.org). llms-full.txt is every page's
Markdown in navigation order, so an assistant can read the whole documentation at once.

Run after 'mkdocs build' (docs.yml does): python scripts/build-llms-txt.py [site-dir]
"""

import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
SUMMARY = ("Migrax generates SQL migrations from JPA/Hibernate entities and applies them, with "
           "locking, checksums, rollback scripts, linting and verification. It runs as a "
           "command line tool, a Maven or Gradle plugin, a container image, and at startup in "
           "Spring Boot, Quarkus, Micronaut and Helidon, on PostgreSQL, CockroachDB, MySQL, "
           "MariaDB, SQL Server, Oracle, H2 and SQLite.")


HOME = "What Migrax is, a short example, and how to get started."


class _Loader(yaml.SafeLoader):
    """Reads mkdocs.yml, ignoring its !!python/name tags."""


_Loader.add_multi_constructor("tag:yaml.org,2002:python/", lambda loader, suffix, node: None)


def pages(nav, section=None):
    """(section, title, path) for every page in the navigation, in order."""
    for entry in nav:
        if isinstance(entry, str):
            yield section, None, entry
            continue
        for title, value in entry.items():
            if isinstance(value, list):
                yield from pages(value, title)
            else:
                yield section, title, value


def url(site_url, path):
    page = path[:-len(".md")]
    if page == "index":
        page = ""
    elif page.endswith("/index"):
        page = page[:-len("index")]
    else:
        page += "/"
    return site_url.rstrip("/") + "/" + page


def markdown(path):
    """The page's Markdown without front matter, with snippet includes filled in."""
    text = (DOCS / path).read_text(encoding="utf-8")
    text = re.sub(r"\A---\n.*?\n---\n", "", text, flags=re.S)

    def include(match):
        snippet = ROOT / match.group(1)
        return snippet.read_text(encoding="utf-8") if snippet.exists() else ""

    text = re.sub(r'^--8<-- "([^"]+)"$', include, text, flags=re.M)
    if path == "index.md":
        # The home page is mostly HTML; keep its words only. Other pages keep placeholders
        # such as <file>.
        text = re.sub(r"<[^>]+>", " ", text)
    return re.sub(r"[ \t]+\n", "\n", text).strip() + "\n"


def first_sentence(text):
    for paragraph in re.split(r"\n\s*\n", text):
        line = " ".join(paragraph.split()).replace("**", "")
        if not line or line[0] in "#!|`=-*>[<" or line.startswith("---"):
            continue
        line = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", line)
        # Short lead-ins such as "Schema model." take the next sentence along.
        sentence = ""
        for part in re.split(r"(?<=[.!?])\s", line):
            sentence = (sentence + " " + part).strip()
            if len(sentence) >= 40:
                break
        return sentence if len(sentence) <= 220 else sentence[:217] + "..."
    return ""


def title_of(text, fallback):
    match = re.search(r"^# (.+)$", text, flags=re.M)
    return match.group(1).strip() if match else fallback


def main():
    site = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "site"
    config = yaml.load((ROOT / "mkdocs.yml").read_text(encoding="utf-8"), Loader=_Loader)
    site_url = config["site_url"]
    index = ["# Migrax", "", "> " + SUMMARY, "",
             "Full text of every page: " + site_url.rstrip("/") + "/llms-full.txt"]
    full = ["# Migrax documentation", "", SUMMARY, ""]
    current = object()
    for section, title, path in pages(config["nav"]):
        text = markdown(path)
        title = title_of(text, title or path)
        if section != current:
            index += ["", "## " + (section or "Overview")]
            current = section
        about = HOME if path == "index.md" else first_sentence(text)
        index.append(f"- [{title}]({url(site_url, path)}): {about}")
        full += ["", "---", "", f"Source: {url(site_url, path)}", "", text]
    site.mkdir(parents=True, exist_ok=True)
    (site / "llms.txt").write_text("\n".join(index) + "\n", encoding="utf-8", newline="\n")
    (site / "llms-full.txt").write_text("\n".join(full), encoding="utf-8", newline="\n")
    print(f"llms.txt and llms-full.txt written to {site}")


if __name__ == "__main__":
    main()
