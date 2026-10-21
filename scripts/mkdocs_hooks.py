"""MkDocs hooks for the docs site (registered under `hooks:` in mkdocs.yml).

The home page lists the newest blog articles. The blog plugin only knows its articles while it
builds the blog pages, so this reads their front matter directly. Every article sets `slug:`
(its address is blog/<slug>/); one without it stops the build here.
"""

import datetime
import pathlib
import re

import yaml

LATEST_POSTS = 3


def _front_matter(text):
    match = re.match(r"^---\s*\n(.*?)\n---\s*\n(.*)$", text, re.S)
    if not match:
        return {}, text
    return yaml.safe_load(match.group(1)) or {}, match.group(2)


def _created(meta):
    created = meta.get("date")
    if isinstance(created, dict):
        created = created.get("created")
    if isinstance(created, datetime.datetime):
        # Times are UTC (mkdocs.yml); a zone written anyway must not break the sorting.
        return created.replace(tzinfo=None)
    if isinstance(created, datetime.date):
        return datetime.datetime.combine(created, datetime.time())
    return None


def _latest_posts(docs_dir):
    blog = pathlib.Path(docs_dir) / "blog"
    authors = {}
    authors_file = blog / ".authors.yml"
    if authors_file.exists():
        authors_yml = yaml.safe_load(authors_file.read_text(encoding="utf-8")) or {}
        authors = authors_yml.get("authors", {})
    posts = []
    for path in (blog / "posts").glob("*.md"):
        meta, body = _front_matter(path.read_text(encoding="utf-8"))
        if meta.get("draft"):
            continue
        if not meta.get("slug"):
            raise ValueError(f"{path}: set 'slug:' in the front matter")
        created = _created(meta)
        heading = re.search(r"^# (.+)$", body, re.M)
        posts.append({
            "title": meta.get("title") or (heading.group(1).strip() if heading else path.stem),
            "description": " ".join(str(meta.get("description", "")).split()),
            "url": "blog/" + str(meta["slug"]) + "/",
            "created": created,
            "date": f"{created:%b} {created.day}, {created:%Y}" if created else "",
            "categories": meta.get("categories") or [],
            "tags": meta.get("tags") or [],
            "authors": [authors[name] for name in meta.get("authors") or [] if name in authors],
        })
    posts.sort(key=lambda post: post["created"] or datetime.datetime.min, reverse=True)
    return posts[:LATEST_POSTS]


def on_page_context(context, page, config, nav):
    if page.file.src_uri == "index.md":
        context["mx_latest_posts"] = _latest_posts(config["docs_dir"])
    return context
