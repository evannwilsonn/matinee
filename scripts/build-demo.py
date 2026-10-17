#!/usr/bin/env python3
"""Builds docs/index.html from web/template.html with the exported model and the test
results embedded, so the page works on its own (GitHub Pages, or opened from disk)."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
page = (root / "web" / "template.html").read_text(encoding="utf-8")
for marker, path in [("MODEL", "web/model.json"), ("RESULTS", "results/test.json"), ("SPARK", "results/spark-check.json")]:
    data = json.dumps(json.loads((root / path).read_text(encoding="utf-8")), separators=(",", ":"), ensure_ascii=False)
    page = page.replace(f"/*{marker}*/null", data.replace("</", "<\\/"))
assert "/*MODEL*/" not in page and "/*RESULTS*/" not in page and "/*SPARK*/" not in page, "a placeholder was not filled"
out = root / "docs" / "index.html"
out.parent.mkdir(exist_ok=True)
out.write_text(page, encoding="utf-8")
print(f"Wrote docs/index.html ({len(page.encode()):,} bytes)")
