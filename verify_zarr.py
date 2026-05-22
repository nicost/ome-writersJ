"""
Cross-language validation: reads the fixture written by VerificationFixtureTest.java
and checks OME-Zarr v0.5 metadata + pixel values.

Run from ome-writersJ directory:
    uv run --directory ../ome-writers python verify_zarr.py
"""

import json
import sys
from pathlib import Path

FIXTURE = Path(__file__).parent / "target" / "verify-fixture.ome.zarr"

PASS = "PASS"
FAIL = "FAIL"

errors = []


def check(label, condition, detail=""):
    if condition:
        print(f"  {PASS}  {label}")
    else:
        msg = f"  {FAIL}  {label}" + (f": {detail}" if detail else "")
        print(msg)
        errors.append(label)


# ---------------------------------------------------------------------------
# 1. zarr.json exists and has correct top-level fields
# ---------------------------------------------------------------------------

print("\n--- zarr.json structure ---")

zarr_json_path = FIXTURE / "zarr.json"
check("zarr.json exists", zarr_json_path.exists())

with zarr_json_path.open() as f:
    root_doc = json.load(f)

check("zarr_format == 3", root_doc.get("zarr_format") == 3,
      f"got {root_doc.get('zarr_format')}")
check("node_type == group", root_doc.get("node_type") == "group",
      f"got {root_doc.get('node_type')}")

attrs = root_doc.get("attributes", {})
check("attributes present", isinstance(attrs, dict) and len(attrs) > 0)

# ---------------------------------------------------------------------------
# 2. multiscales block
# ---------------------------------------------------------------------------

print("\n--- multiscales ---")

ms_list = attrs.get("multiscales", [])
check("multiscales key present", len(ms_list) > 0)

ms = ms_list[0] if ms_list else {}
check("version == 0.5", ms.get("version") == "0.5", f"got {ms.get('version')}")

axes = ms.get("axes", [])
check("4 axes (TCYX)", len(axes) == 4, f"got {len(axes)}")

if len(axes) >= 4:
    t_ax, c_ax, y_ax, x_ax = axes

    check("axis[0] name == t",    t_ax.get("name") == "t",       f"got {t_ax.get('name')}")
    check("axis[0] type == time", t_ax.get("type") == "time",    f"got {t_ax.get('type')}")
    check("axis[0] unit == second", t_ax.get("unit") == "second",f"got {t_ax.get('unit')}")

    check("axis[1] name == c",       c_ax.get("name") == "c",       f"got {c_ax.get('name')}")
    check("axis[1] type == channel", c_ax.get("type") == "channel", f"got {c_ax.get('type')}")

    check("axis[2] name == y",           y_ax.get("name") == "y",           f"got {y_ax.get('name')}")
    check("axis[2] type == space",       y_ax.get("type") == "space",       f"got {y_ax.get('type')}")
    check("axis[2] unit == micrometer",  y_ax.get("unit") == "micrometer",  f"got {y_ax.get('unit')}")

    check("axis[3] name == x",           x_ax.get("name") == "x",           f"got {x_ax.get('name')}")
    check("axis[3] type == space",       x_ax.get("type") == "space",       f"got {x_ax.get('type')}")
    check("axis[3] unit == micrometer",  x_ax.get("unit") == "micrometer",  f"got {x_ax.get('unit')}")

datasets = ms.get("datasets", [])
check("datasets has entry for '0'", any(d.get("path") == "0" for d in datasets))

if datasets:
    ds0 = next((d for d in datasets if d.get("path") == "0"), datasets[0])
    cts = ds0.get("coordinateTransformations", [])
    scale_ct = next((c for c in cts if c.get("type") == "scale"), None)
    check("coordinateTransformations has scale", scale_ct is not None)
    if scale_ct:
        scale = scale_ct.get("scale", [])
        check("scale has 4 values", len(scale) == 4, f"got {len(scale)}")
        if len(scale) == 4:
            check("t scale == 2.0",   scale[0] == 2.0,  f"got {scale[0]}")
            check("c scale == 1.0",   scale[1] == 1.0,  f"got {scale[1]}")
            check("y scale == 0.1",   abs(scale[2] - 0.1) < 1e-9, f"got {scale[2]}")
            check("x scale == 0.1",   abs(scale[3] - 0.1) < 1e-9, f"got {scale[3]}")

# ---------------------------------------------------------------------------
# 3. omero channels
# ---------------------------------------------------------------------------

print("\n--- omero channels ---")

omero = attrs.get("omero", {})
check("omero key present", bool(omero))

channels = omero.get("channels", [])
check("2 channels", len(channels) == 2, f"got {len(channels)}")

if len(channels) >= 2:
    ch0, ch1 = channels[0], channels[1]
    check("channel[0] label == DAPI", ch0.get("label") == "DAPI", f"got {ch0.get('label')}")
    check("channel[1] label == GFP",  ch1.get("label") == "GFP",  f"got {ch1.get('label')}")
    check("channel[0] color == 0000FF", ch0.get("color") == "0000FF",
          f"got {ch0.get('color')}")
    check("channel[1] color == 00FF00", ch1.get("color") == "00FF00",
          f"got {ch1.get('color')}")
    check("channel[0] active == True",  ch0.get("active") is True,
          f"got {ch0.get('active')}")

# ---------------------------------------------------------------------------
# 4. array shape and dtype
# ---------------------------------------------------------------------------

print("\n--- array shape and dtype ---")

try:
    import zarr
    arr = zarr.open_array(str(FIXTURE / "0"), mode="r")
    check("shape == [3, 2, 8, 8]", list(arr.shape) == [3, 2, 8, 8],
          f"got {list(arr.shape)}")
    check("dtype is uint16", str(arr.dtype) == "uint16", f"got {arr.dtype}")

    # ---------------------------------------------------------------------------
    # 5. pixel values: frame (t, c) should be all t*10+c
    # ---------------------------------------------------------------------------

    print("\n--- pixel values ---")

    all_ok = True
    for t in range(3):
        for c in range(2):
            expected = t * 10 + c
            frame = arr[t, c, :, :]
            if (frame == expected).all():
                print(f"  {PASS}  frame (t={t}, c={c}) == {expected}")
            else:
                bad = frame[frame != expected]
                msg = f"expected {expected}, got values: {bad[:5]}"
                print(f"  {FAIL}  frame (t={t}, c={c}): {msg}")
                errors.append(f"pixel (t={t}, c={c})")
                all_ok = False

except ImportError:
    print(f"  SKIP  zarr not importable — install zarr>=3.0")
    errors.append("zarr import failed")

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

print()
if not errors:
    print(f"All checks {PASS}")
    sys.exit(0)
else:
    print(f"{FAIL}: {len(errors)} check(s) failed: {', '.join(errors)}")
    sys.exit(1)
