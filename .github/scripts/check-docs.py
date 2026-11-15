#!/usr/bin/env python3
"""
Checks that the Kotlin source explains itself in plain words (see CONTRIBUTING.md):

  1. Every file opens, after its package and imports, with a /** ... */
     description of what the file is for.
  2. Every class, object, interface and enum, and every function declared at
     the top level of a file, has a /** ... */ description right above it.
     (Companion objects and overrides are exempt; functions inside classes are
     encouraged but not checked, so one-line helpers don't need filler.)

Prints each problem as file:line and exits 1 if there are any.

    python3 .github/scripts/check-docs.py
"""
import glob
import re
import sys

TYPE = re.compile(
    r"^\s*(?:(?:public|internal|private|protected|open|abstract|data|sealed|enum|inner|value|annotation|fun)\s+)*"
    r"(class|object|interface)\s+\w")
TOP_FUN = re.compile(r"^(?:(?:public|internal|private|inline|suspend|operator|infix|tailrec)\s+)*fun\s")


def documented(lines: list[str], i: int) -> bool:
    """Is there a doc comment directly above line i (above any annotations)?"""
    j = i - 1
    while j >= 0 and lines[j].strip().startswith("@"):
        j -= 1
    return j >= 0 and lines[j].strip().endswith("*/")


def check(path: str) -> list[str]:
    lines = open(path, encoding="utf-8").read().split("\n")
    problems = []
    first = next((i for i, l in enumerate(lines)
                  if l.strip() and not l.startswith(("package ", "import ", "//", "@file:"))), None)
    if first is None or not lines[first].lstrip().startswith("/**"):
        problems.append(f"{path}:{(first or 0) + 1}: the file should open with a /** description */ of what it is for")
    in_comment = False
    for i, line in enumerate(lines):
        s = line.strip()
        if in_comment:
            in_comment = "*/" not in s
            continue
        if s.startswith("/*"):
            in_comment = "*/" not in s
            continue
        if s.startswith("//") or "companion object" in s:
            continue
        m = TYPE.match(line)
        if m and not documented(lines, i):
            problems.append(f"{path}:{i + 1}: no description above `{s[:60]}`")
        elif TOP_FUN.match(line) and not documented(lines, i):
            problems.append(f"{path}:{i + 1}: no description above `{s[:60]}`")
    return problems


def main() -> int:
    problems = []
    for path in sorted(glob.glob("app/src/**/*.kt", recursive=True)):
        problems += check(path)
    for p in problems:
        print(p)
    if problems:
        print(f"\n{len(problems)} place(s) need a plain-language description. See CONTRIBUTING.md.")
        return 1
    print("Every Kotlin file, type and top-level function has a description.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
