#!/usr/bin/env python3
"""Prepare upstream XMicInject as a separate LSPosed module for Liqaa."""
from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "upstream")
APP = ROOT / "app"
BUILD = APP / "build.gradle.kts"
MANIFEST = APP / "src/main/AndroidManifest.xml"

if not BUILD.exists():
    raise SystemExit(f"Missing {BUILD}")
if not MANIFEST.exists():
    raise SystemExit(f"Missing {MANIFEST}")

text = BUILD.read_text(encoding="utf-8")
updated, count = re.subn(
    r'(applicationId\s*=\s*)"[^"]+"',
    r'\1"com.lgana.xmicinject"',
    text,
    count=1,
)
if count != 1:
    raise SystemExit("Could not find the app applicationId in app/build.gradle.kts")
BUILD.write_text(updated, encoding="utf-8")

manifest = MANIFEST.read_text(encoding="utf-8")
match = re.search(r'<application\b[^>]*>', manifest, re.S)
if not match:
    raise SystemExit("Could not locate <application> in AndroidManifest.xml")
tag = match.group(0)
if "android:label=" in tag:
    new_tag = re.sub(
        r'android:label\s*=\s*"[^"]*"',
        'android:label="Liqaa XMicInject"',
        tag,
        count=1,
    )
else:
    new_tag = tag[:-1] + '\n        android:label="Liqaa XMicInject">'
manifest = manifest[:match.start()] + new_tag + manifest[match.end():]
MANIFEST.write_text(manifest, encoding="utf-8")

print("Prepared XMicInject for Liqaa")
print("Module ID: com.lgana.xmicinject")
print("Target package: com.lgana.voip")
