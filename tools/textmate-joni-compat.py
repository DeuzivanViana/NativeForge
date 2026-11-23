#!/usr/bin/env python3
"""Rewrites TextMate grammar regexes that Joni (the Java regex engine sora-editor uses
on Android) rejects, keeping their meaning.

VS Code's grammars target Oniguruma 6.9, which accepts variable-length look-behinds
and `x+{1,0}`. Joni does not; tm4e then drops the rule, and when the dropped pattern
is the `end` of a comment, the comment never ends and the rest of the file is shown
as a comment. Run this after updating a grammar from upstream:

    tools/textmate-joni-compat.py feature/editor/src/main/assets/textmate/grammars/*.json

GrammarJoniTest checks that every pattern compiles.
"""
import json
import re
import sys

KEYS = {"match", "begin", "end", "while"}

LITERALS = [
    # Optional possessive whitespace, written as Oniguruma's reversed interval.
    (r"\s+{1,0}", r"\s*+"),
    # End of a line that is not continued with a backslash.
    (r"(?<=(?:^|[^\\])\n)", r"(?<=[^\\]\n|^\n)"),
    (r"(?<=(?:\W|^)for ?\()", r"(?<=\Wfor\(|\Wfor \(|^for\(|^for \()"),
    (r"(?<=\.\*?|->\*??)", r"(?<=\.|\.\*|->|->\*)"),
]

# (?<!(?:\W|^)(?:a|bc)) and (?<=(?:\W|^)word): alternatives of different lengths
# are only allowed at the top level of a look-behind.
WORD_LIST = re.compile(r"\(\?<([=!])\(\?:\\W\|\^\)(?:\(\?:([A-Za-z0-9_|]+)\)|([A-Za-z0-9_]+))\)")


def expand(m: re.Match) -> str:
    words = (m.group(2) or m.group(3)).split("|")
    alternatives = [f"\\W{w}" for w in words] + [f"^{w}" for w in words]
    return f"(?<{m.group(1)}" + "|".join(alternatives) + ")"


def fix(pattern: str) -> str:
    for old, new in LITERALS:
        pattern = pattern.replace(old, new)
    return WORD_LIST.sub(expand, pattern)


def walk(node, counter):
    if isinstance(node, dict):
        for key, value in node.items():
            if key in KEYS and isinstance(value, str):
                fixed = fix(value)
                if fixed != value:
                    node[key] = fixed
                    counter[0] += 1
            else:
                walk(value, counter)
    elif isinstance(node, list):
        for item in node:
            walk(item, counter)


for path in sys.argv[1:]:
    with open(path, encoding="utf-8") as f:
        grammar = json.load(f)
    counter = [0]
    walk(grammar, counter)
    if counter[0]:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(grammar, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{path}: {counter[0]} patterns rewritten")
