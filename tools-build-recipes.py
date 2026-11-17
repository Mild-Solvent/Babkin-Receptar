#!/usr/bin/env python3
"""Merge recipes/*.json into the app's bundled recipes.json and the over-the-air feed recepty.data.
Usage: python3 tools-build-recipes.py <version>   (version must go up every time you publish)"""
import json, glob, os, sys
here = os.path.dirname(os.path.abspath(__file__))
ver = int(sys.argv[1])
recipes, ids = [], set()
for f in sorted(glob.glob(os.path.join(here, "recipes", "*.json"))):
    for r in json.load(open(f, encoding="utf-8")):
        assert r["id"] not in ids, "duplicate id " + r["id"]
        assert r.get("name") and r.get("ingredients") and r.get("steps"), r["id"]
        ids.add(r["id"]); recipes.append(r)
out = json.dumps({"version": ver, "recipes": recipes}, ensure_ascii=False, indent=1)
open(os.path.join(here, "app/src/main/assets/www/recipes.json"), "w", encoding="utf-8").write(out + "\n")
open(os.path.join(here, "recepty.data"), "w", encoding="utf-8").write(out + "\n")
print(len(recipes), "recipes, version", ver)
