#!/usr/bin/env python3
"""Imports the string catalog of the iOS package into the library's string resources.

Reads `Sources/ProximiioGADKit/Resources/Localizable.xcstrings` of proximiio-gadkit-ios and writes
`proximiio-gadkit/src/main/res/values/strings_ios.xml` (English) and `values-ar/strings_ios.xml`
(Arabic). Run it again after the iOS catalog changes:

    python3 -I scripts/import_ios_strings.py [path/to/Localizable.xcstrings]

- The resource name is a slug of the English key; `scripts/ios_strings_names.json` keeps the names
  stable once assigned, so code that uses them does not break when the catalog grows.
- `%@` becomes `%s`, `%lld` / `%d` becomes `%d`; several placeholders become positional.
- Plural variations become <plurals>.
- An Arabic string in state `needs_review` (translated by the developers) is marked with a comment.
- A key with `shouldTranslate: false` (placeholders, debug copy) gets `translatable="false"`, so lint
  does not report it as missing in Arabic; iOS has no Arabic for it either.
- A key the iOS catalog has no Arabic for yet gets `tools:ignore="MissingTranslation"` and a comment;
  Arabic shows the English, as on iOS. Ask iOS for the translation.
- The files ignore `UnusedResources`: the catalog can hold copy that Android does not use yet.
  Release builds of the host app shrink the unused strings.
- The host app imports the iOS app's catalog with its own copy of this script and names file. A
  key in both catalogs gets the same name in both.
- Stale keys and the empty key are skipped.
"""
import json
import pathlib
import re
import sys
from xml.sax.saxutils import escape

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_CATALOG = pathlib.Path(
    "/Volumes/ExtremeSSD/proximiio/guggenheim-ios/Packages/ProximiioGADKit/Sources/ProximiioGADKit/Resources/Localizable.xcstrings"
)
NAMES = ROOT / "scripts" / "ios_strings_names.json"
OUT = {
    "en": ROOT / "proximiio-gadkit/src/main/res/values/strings_ios.xml",
    "ar": ROOT / "proximiio-gadkit/src/main/res/values-ar/strings_ios.xml",
}
JAVA_KEYWORDS = set(
    "abstract assert boolean break byte case catch char class const continue default do double else enum "
    "extends final finally float for goto if implements import instanceof int interface long native new "
    "package private protected public return short static strictfp super switch synchronized this throw "
    "throws transient try void volatile while true false null".split()
)
PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?(@|lld|ld|d|lf|f|%)")


def slug(text: str) -> str:
    words = re.findall(r"[a-z0-9]+", text.lower().replace("%@", " x ").replace("%lld", " n "))
    name = "_".join(words[:7]) or "text"
    if name[0].isdigit():
        name = "s_" + name
    name = name[:60].rstrip("_")
    return name + "_action" if name in JAVA_KEYWORDS else name


def android_format(text: str) -> str:
    specs = [m for m in PLACEHOLDER.finditer(text) if m.group(2) != "%"]
    positional = len(specs) > 1
    counter = 0

    def repl(m: re.Match) -> str:
        nonlocal counter
        kind = m.group(2)
        if kind == "%":
            return "%%"
        counter += 1
        index = m.group(1) or str(counter)
        letter = {"@": "s", "lld": "d", "ld": "d", "d": "d", "lf": "f", "f": "f"}[kind]
        return f"%{index}${letter}" if positional else f"%{letter}"

    return PLACEHOLDER.sub(repl, text)


def android_text(text: str) -> str:
    text = escape(android_format(text))
    text = text.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if text.startswith(("@", "?")):
        text = "\\" + text
    if text != text.strip(" "):
        # aapt trims leading and trailing spaces unless the string is quoted.
        text = f'"{text}"'
    return text


def unit(entry: dict, language: str):
    """Returns ("string", text, state) or ("plural", {quantity: text}, state) or None."""
    loc = entry.get("localizations", {}).get(language)
    if loc is None:
        return None
    if "stringUnit" in loc:
        return "string", loc["stringUnit"]["value"], loc["stringUnit"].get("state", "")
    plural = loc.get("variations", {}).get("plural")
    if plural:
        forms = {q: v["stringUnit"]["value"] for q, v in plural.items()}
        states = {v["stringUnit"].get("state", "") for v in plural.values()}
        return "plural", forms, "needs_review" if "needs_review" in states else "translated"
    return None


def main() -> None:
    catalog = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_CATALOG
    strings = json.loads(catalog.read_text())["strings"]
    names = json.loads(NAMES.read_text()) if NAMES.exists() else {}
    names = {k: (v + "_action" if v in JAVA_KEYWORDS else v) for k, v in names.items()}
    used = set(names.values())

    for key in sorted(strings):
        if key and key not in names and strings[key].get("extractionState") != "stale":
            base = slug(key)
            name, n = base, 2
            while name in used:
                name, n = f"{base}_{n}", n + 1
            names[key] = name
            used.add(name)

    for language, path in OUT.items():
        lines = [
            '<?xml version="1.0" encoding="utf-8"?>',
            "<!-- Generated by scripts/import_ios_strings.py from the string catalog of proximiio-gadkit-ios. Do not edit. -->",
            '<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="UnusedResources">',
        ]
        for key in sorted(strings, key=lambda k: names.get(k, "")):
            entry = strings[key]
            if not key or entry.get("extractionState") == "stale" or key not in names:
                continue
            found = unit(entry, language)
            if found is None and language == "en":
                found = ("string", key, "translated")
            if found is None:
                continue
            kind, value, state = found
            name = names[key]
            if language == "ar" and state == "needs_review":
                lines.append("    <!-- needs_review: developer translation, the client reviews it before release -->")
            fixed = ' translatable="false"' if entry.get("shouldTranslate") is False else ""
            if fixed and language != "en":
                continue
            if not fixed and language == "en" and unit(entry, "ar") is None:
                lines.append("    <!-- no Arabic in the iOS catalog yet: Arabic shows the English -->")
                fixed = ' tools:ignore="MissingTranslation"'
            if kind == "string":
                lines.append(f'    <string name="{name}"{fixed}>{android_text(value)}</string>')
            else:
                lines.append(f'    <plurals name="{name}"{fixed}>')
                for quantity in ("zero", "one", "two", "few", "many", "other"):
                    if quantity in value:
                        lines.append(f'        <item quantity="{quantity}">{android_text(value[quantity])}</item>')
                lines.append("    </plurals>")
        lines.append("</resources>")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(lines) + "\n")

    NAMES.write_text(json.dumps(dict(sorted(names.items())), ensure_ascii=False, indent=2) + "\n")
    print(f"{len(names)} names; wrote {', '.join(str(p.relative_to(ROOT)) for p in OUT.values())}")


if __name__ == "__main__":
    main()
