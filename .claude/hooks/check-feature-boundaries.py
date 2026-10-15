#!/usr/bin/env python3
"""Lists imports from one feature into another (CLAUDE.md: features must not import from each other)."""
import os
import re
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
problems = []

# Backend: food is the shared package, everything else is a feature.
pkg = "com/example/pickyourfood"
for base in ("src/main/java", "src/test/java"):
    for f in (root / base / pkg).glob("*/**/*.java"):
        own = f.relative_to(root / base / pkg).parts[0]
        for i in set(re.findall(r"^import com\.example\.pickyourfood\.([a-z]+)\.", f.read_text(), re.M)):
            if i not in (own, "food"):
                problems.append(f"{f.relative_to(root)}: {own} imports {i}")

# Frontend: shared/ and App.tsx may import anything, features only themselves and shared/.
features = root / "frontend/src/features"
for f in features.glob("*/**/*.ts*"):
    own = f.relative_to(features).parts[0]
    for spec in re.findall(r"""(?:from|import)\s*\(?\s*['"](\.[^'"]+)['"]""", f.read_text()):
        target = Path(os.path.normpath(f.parent / spec))
        if target.is_relative_to(features) and target.relative_to(features).parts[0] != own:
            problems.append(f"{f.relative_to(root)}: {own} imports {spec}")

if problems:
    print("Features must not import from each other (move shared code to food/ or frontend/src/shared/):", file=sys.stderr)
    print("\n".join(problems), file=sys.stderr)
    sys.exit(2)
