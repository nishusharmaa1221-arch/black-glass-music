#!/usr/bin/env python3
"""Tiny uiautomator-dump helper.

Usage:
  uitap.py find  <xmlfile> <needle> [--desc] [--exact] [--class SUBSTR] [--index N]
      -> prints "x y" (center of the Nth matching node) or exits 1
  uitap.py texts <xmlfile>
      -> prints every visible text / content-desc, one per line (debug)
Matching is case-insensitive substring on the `text` attribute (or
`content-desc` with --desc) unless --exact is given.
"""
import re
import sys
import xml.etree.ElementTree as ET


def parse_bounds(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b or "")
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def nodes(root):
    for el in root.iter("node"):
        yield el


def main():
    if len(sys.argv) < 3:
        print("usage", file=sys.stderr)
        return 2
    mode, path = sys.argv[1], sys.argv[2]
    tree = ET.parse(path)
    root = tree.getroot()

    if mode == "texts":
        seen = []
        for el in nodes(root):
            t = (el.get("text") or "").strip()
            d = (el.get("content-desc") or "").strip()
            if t:
                seen.append("T: " + t)
            if d:
                seen.append("D: " + d)
        print("\n".join(dict.fromkeys(seen)))
        return 0

    if mode == "find":
        args = sys.argv[3:]
        needle = args[0].lower()
        use_desc = "--desc" in args
        exact = "--exact" in args
        cls = None
        idx = 0
        for i, a in enumerate(args):
            if a == "--class" and i + 1 < len(args):
                cls = args[i + 1]
            if a == "--index" and i + 1 < len(args):
                idx = int(args[i + 1])
        attr = "content-desc" if use_desc else "text"
        hits = []
        for el in nodes(root):
            if cls and cls not in (el.get("class") or ""):
                continue
            val = (el.get(attr) or "").strip().lower()
            if needle and not val:
                continue
            if (val == needle) if exact else (needle in val):
                c = parse_bounds(el.get("bounds"))
                if c:
                    hits.append(c)
        if len(hits) > idx:
            print(f"{hits[idx][0]} {hits[idx][1]}")
            return 0
        return 1

    print("unknown mode", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
