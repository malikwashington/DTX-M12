#!/usr/bin/env python3
"""setlist_sync.py - one-command live-rig setup for the DTX-MULTI 12 + forScore.

Given a forScore .4sb backup and a DTX-MULTI 12 YSFC save (.MTA) plus a setlist
name, this wires the two together so that turning to a chart in forScore selects
the matching M12 kit at the right tempo - hands-free, across a whole show:

  for each chart in the setlist (in order):
    - find the M12 kit named after the song (or, with --by-order, the Nth kit)
    - write that kit's Program Change into the chart's forScore MIDI Send
      (forScore fires it natively on page-open), optionally led by a Stop byte
    - set that kit's tempo in the YSFC save from the chart's stored |bpm

Writes NEW files (never the inputs). --dry-run prints the plan and writes nothing.

This is pure glue over two reverse-engineered formats - it programs the vendors'
own features (forScore's page-open MIDI, the M12's kit-select + tempo) in bulk so
you don't tap them in across dozens of charts by hand:
  forscore_4sb.py  - the .4sb container + the per-chart kit-select MIDI Sends
  ysfc.py          - the M12 YSFC save (kit catalog + the checksum-free tempo edit)
"""
import argparse, re, sys
from pathlib import Path

import forscore_4sb as fs
from ysfc import YSFC, TEMPO_OFF


def norm(s):
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


def kit_select(slot, stop):
    # B9 00 7D = Bank MSB 125 ; B9 20 01 = Bank LSB 1 ; C9 PC = select kit `slot` (ch 10)
    cmds = [{"value": f"B9 00 7D B9 20 01 C9 {slot & 0x7F:02X}", "kind": "hex"}]
    return ([{"value": "FC", "kind": "hex"}] + cmds) if stop else cmds


def setlist_charts(store, name):
    """Ordered chart filenames in setlist `name` (exact, else unique substring)."""
    key = "&SET;" + name
    if key not in store:
        cand = [k for k in store if k.startswith("&SET;") and name.lower() in k[5:].lower()]
        if len(cand) != 1:
            return None, [k[5:] for k in store if k.startswith("&SET;")]
        key = cand[0]
    items = store.get(key, [])
    return [it["FilePath"] for it in items if isinstance(it, dict) and it.get("FilePath")], None


def kit_index(y):
    """Named kits -> [{slot, id, name, body, bpm}] sorted by slot (1-based)."""
    temps = y.kit_tempos()                       # id -> (name, bpm, body)
    rows = []
    for r in y.catalog("EKIT"):
        if not r["name"] or r["name"].lower() in ("user kit", ""):
            continue
        _, bpm, body = temps.get(r["id"], (r["name"], None, None))
        rows.append({"slot": r["order"] + 1, "id": r["id"], "name": r["name"],
                     "body": body, "bpm": bpm})
    rows.sort(key=lambda x: x["slot"])
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("backup", help="forScore .4sb backup")
    ap.add_argument("--save", required=True, help="DTX-MULTI 12 YSFC save (.MTA)")
    ap.add_argument("--setlist", required=True, help="setlist name (exact, else unique substring)")
    ap.add_argument("--out-4sb", help="output .4sb (default: <backup>.synced.4sb)")
    ap.add_argument("--out-save", help="output save (default: <save>.synced<ext>)")
    ap.add_argument("--by-order", action="store_true",
                    help="map charts to kits by setlist position instead of by name")
    ap.add_argument("--no-stop", action="store_true",
                    help="don't prepend the FC Stop byte to each Send")
    ap.add_argument("--dry-run", action="store_true", help="print the plan; write nothing")
    a = ap.parse_args()

    arc = fs.Archive.load(a.backup)
    store = arc.store
    charts, available = setlist_charts(store, a.setlist)
    if charts is None:
        sys.exit(f"setlist '{a.setlist}' not found. Available: {available}")
    if not charts:
        sys.exit(f"setlist '{a.setlist}' has no charts with a FilePath.")

    y = YSFC(a.save)
    kits = kit_index(y)
    by_norm = {norm(k["name"]): k for k in kits}

    plan, unmatched = [], []
    for i, fn in enumerate(charts):
        title = fs.field(store, fn, "title") or Path(fn).stem
        bpm = fs.field(store, fn, "bpm")
        try:
            bpm = int(bpm) if bpm not in (None, "") else None
        except (TypeError, ValueError):
            bpm = None
        kit = None
        if a.by_order:
            kit = kits[i] if i < len(kits) else None
        else:
            cn = norm(title)                     # kit names are <=16 chars, often truncated
            for kn, k in by_norm.items():
                if kn and (cn.startswith(kn) or kn.startswith(cn) or kn in cn):
                    kit = k
                    break
        if kit is None:
            unmatched.append((title, fn))
            continue
        plan.append({"i": i + 1, "title": title, "fn": fn, "bpm": bpm,
                     "slot": kit["slot"], "kit": kit["name"], "body": kit["body"],
                     "old_bpm": kit["bpm"]})

    print(f"setlist '{a.setlist}': {len(charts)} chart(s); matched {len(plan)}, "
          f"unmatched {len(unmatched)}\n")
    print(f"  {'#':>2}  {'chart':26}  {'-> kit (slot)':22}  tempo")
    for p in plan:
        t = (f"{p['old_bpm']}->{p['bpm']}" if (p["bpm"] and p["body"])
             else (str(p["bpm"]) if p["bpm"] else "-"))
        kitcol = f"{p['kit'][:16]} ({p['slot']})"
        print(f"  {p['i']:>2}  {p['title'][:26]:26}  {kitcol:22}  {t}")
    for title, fn in unmatched:
        print(f"   !  {title[:26]:26}  (no kit named like this - skipped; try --by-order)")

    if a.dry_run:
        print("\n[dry run] nothing written.")
        return

    # forScore: replace each matched chart's Send with its kit-select (+ optional Stop)
    for p in plan:
        store[p["fn"] + "|midi"] = fs.encode_midi(kit_select(p["slot"], not a.no_stop))
    arc.set_store(store)
    out4 = a.out_4sb or str(Path(a.backup).with_suffix("")) + ".synced.4sb"
    if Path(out4).resolve() == Path(a.backup).resolve():
        sys.exit("refusing to overwrite the input .4sb")
    arc.save(out4)

    # YSFC: set each matched kit's tempo from its chart's bpm (no checksum to fix)
    d = bytearray(y.d)
    tempo_writes = 0
    for p in plan:
        if p["bpm"] and p["body"] and 1 <= p["bpm"] <= 1000:
            d[p["body"] + TEMPO_OFF] = p["bpm"] >> 7
            d[p["body"] + TEMPO_OFF + 1] = p["bpm"] & 0x7F
            tempo_writes += 1
    outs = a.out_save or str(Path(a.save).with_suffix("")) + ".synced" + Path(a.save).suffix
    if Path(outs).resolve() == Path(a.save).resolve():
        sys.exit("refusing to overwrite the input save")
    Path(outs).write_bytes(d)

    print(f"\nwrote {out4}   ({len(plan)} kit-select Send(s))")
    print(f"wrote {outs}   ({tempo_writes} kit tempo(s) set)")
    print("Restore the .4sb in forScore (Tools -> Backups) and load the save on the M12; "
          "keep the originals until you've verified the show.")


if __name__ == "__main__":
    main()
