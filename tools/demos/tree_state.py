#!/usr/bin/env python3
"""Reason about an XFiles tree pane from a uiautomator XML dump.

Usage:
  tree_state.py DUMP collapse-deepest [MINX]
  tree_state.py DUMP expand-chevron TEXT
  tree_state.py DUMP center TEXT

Commands print "cx cy" when a matching target is found and print nothing when
there is no applicable target.
"""

import re
import sys


def parse(path):
    xml = open(path, encoding="utf-8", errors="replace").read()
    labels, chevrons = [], []
    for match in re.finditer(
        r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        xml,
    ):
        text = match.group(1)
        x1, y1, x2, y2 = map(int, match.groups()[1:])
        if y1 < 290 or not text.strip() or re.search(r"/|·|GB|:| apps$", text):
            continue
        labels.append({"text": text, "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2})

    for match in re.finditer(
        r'content-desc="(Expand|Collapse)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        xml,
    ):
        state = match.group(1)
        x1, y1, x2, y2 = map(int, match.groups()[1:])
        chevrons.append(
            {"state": state, "x1": x1, "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2},
        )
    return labels, chevrons


def chevron_for(chevrons, cy):
    nearby = [item for item in chevrons if abs(item["cy"] - cy) <= 40]
    return min(nearby, key=lambda item: abs(item["cy"] - cy)) if nearby else None


def main():
    path, command = sys.argv[1], sys.argv[2]
    labels, chevrons = parse(path)

    if command == "collapse-deepest":
        min_x = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        opened = [
            item
            for item in chevrons
            if item["state"] == "Collapse" and item["x1"] >= min_x
        ]
        if opened:
            item = max(opened, key=lambda value: (value["x1"], -value["cy"]))
            print(f'{item["cx"]} {item["cy"]}')
        return

    if command == "expand-chevron":
        text = sys.argv[3]
        label = next((item for item in labels if item["text"] == text), None)
        if label:
            item = chevron_for(chevrons, label["cy"])
            if item and item["state"] == "Expand":
                print(f'{item["cx"]} {item["cy"]}')
        return

    if command == "center":
        text = sys.argv[3]
        label = next((item for item in labels if item["text"] == text), None)
        if label:
            print(f'{label["cx"]} {label["cy"]}')
        return

    raise SystemExit(f"unknown command: {command}")


if __name__ == "__main__":
    main()
