"""Regenerate the md_theme_* color roles in colors.xml from the Cue seed.

Usage: .venv/bin/python design/regen_colors.py [#RRGGBB]
Requires the materialyoucolor package.
"""
import re
import sys

from materialyoucolor.dynamiccolor.material_dynamic_colors import MaterialDynamicColors as M
from materialyoucolor.hct import Hct
from materialyoucolor.scheme.scheme_fidelity import SchemeFidelity

seed = int((sys.argv[1] if len(sys.argv) > 1 else "#4F46E5").lstrip("#"), 16) | 0xFF000000
path = "app/src/main/res/values/colors.xml"
xml = open(path).read()
roles = sorted({n for _, n in re.findall(r'<color name="md_theme_(light|dark)_([A-Za-z]+)">', xml)})
values = {}
for dark in (False, True):
    scheme = SchemeFidelity(Hct.from_int(seed), dark, 0.0)
    for role in roles:
        values[("dark" if dark else "light", role)] = "#%06x" % (getattr(M, role).get_argb(scheme) & 0xFFFFFF)
xml = re.sub(
    r'<color name="md_theme_(light|dark)_([A-Za-z]+)">(#[0-9a-fA-F]+)</color>',
    lambda m: '<color name="md_theme_%s_%s">%s</color>' % (m.group(1), m.group(2), values[(m.group(1), m.group(2))]),
    xml,
)
open(path, "w").write(xml)
print("updated %d color roles" % len(values))
