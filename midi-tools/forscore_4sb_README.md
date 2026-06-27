# forscore_4sb.py

Read, search, and edit **forScore `.4sb` backups** from the command line — and inject
MIDI stop/transport bytes into a chart's Send commands without opening forScore.

Built for the DTX-M12 rig: forScore on the iPad sends the kit-change MIDI on each
chart open; this tool lets you batch-edit that MIDI in the backup file instead of
tapping it in by hand across 70+ charts.

- **No dependencies** beyond the Python standard library. Runs under `../.venv/bin/python3`.
- **Non-destructive.** Edits re-compress only the one member they change; every other
  member is copied byte-for-byte. A no-op round trip is byte-identical (`verify`).

---

## The `.4sb` format

A `.4sb` is a flat container, not a zip:

```
b"<--4SBV02-->"                         # 12-byte magic
then, repeated for each member:
    [namelen : 16 bytes]                # right-justified ASCII integer
    [bloblen : 16 bytes]                # right-justified ASCII integer
    [name    : namelen bytes]           # UTF-8
    [blob    : bloblen bytes]           # gzip stream (mtime=0)
```

**Member 0** is the metadata store: a **binary plist** (a flat dict). Every other
member is a cached page **PNG** or a vector-annotation blob — those are never touched.

In a typical backup: ~130 PNGs, 1 store, a couple of annotation blobs.

### Store keys

The store is a single dict keyed by namespace. The ones that matter:

| Key pattern | Meaning |
|---|---|
| `<file>|title` | display title (string) |
| `<file>|composer` | composer (string) |
| `<file>|keywords` | keywords (string; this is where the show name usually lives) |
| `<file>|labels` | labels (string) |
| `<file>|bpm` | tempo (int) |
| `<file>|added` | import timestamp — present on **every** score, so it's the canonical score list |
| `<file>|midi` | **MIDI Send commands** — fires on chart open (see below) |
| `<file>|shortcut` | incoming MIDI trigger that opens this chart |
| `<file>&BLU;<page>&BLU;bluePoints` | links (repeats): blue source point + orange destination + page numbers |
| `&SET;<name>` | a setlist — a list of `{FilePath, Identifier, Title}` |
| `&SYS;<key>` | app settings (e.g. `&SYS;midiSend`) |

### The `|midi` value

Each `<file>|midi` is an **`NSKeyedArchiver`** archive of an array of
`{value, kind}` dicts. On this rig the kit changes are uniform, channel 10:

```
{value: "B9 00 7D  B9 20 01  C9 0X", kind: "hex"}
        └ Bank MSB 125 ┘ └ LSB 1 ┘ └ PC X ┘     -> selects DTX kit X
```

`inject-stop` prepends a one-byte command — `{value: "FC", kind: "hex"}` — so the chart
**stops the running pattern, then selects the kit**, in that order, on open.

> `FC` is the MIDI System Real-Time **Stop** byte. The M12 only acts on it with
> **UTIL6-8 `SeqCtrl = in`** enabled. This covers stops that land on a page/chart
> change; a mid-song stop with no page turn is handled on the M12 side, not here.

---

## Usage

```
python3 forscore_4sb.py BACKUP.4sb <command> [filters]
```

### Commands

| Command | What it does |
|---|---|
| `info` | container + store summary (member counts, scores, setlists) |
| `scores` | list scores, flagged `[MIDI,links]` |
| `search` | list matching scores with their metadata |
| `midi` | decode the kit-change map (the `|midi` Sends) |
| `links` | list link/`bluePoints` data per chart |
| `setlists` | list setlists with item counts |
| `extract-plist --out FILE` | dump the raw decompressed store plist |
| `verify` | self-test: assert a no-op round trip is byte-identical |
| `inject-stop --out FILE [filters]` | prepend a stop byte to matching Sends |

### Filters (shared by `scores`, `search`, `midi`, `inject-stop`)

All filters **AND** together; values are **regex, case-insensitive**.

| Flag | Matches against |
|---|---|
| `--name` | PDF filename |
| `--title` | title field |
| `--composer` | composer field |
| `--keyword` | keywords field |
| `--label` | labels field |
| `--setlist` | setlist **membership** (substring on the setlist name) |
| `--match` | **any** text field or the filename |
| `--has-midi` | only scores with a MIDI Send |
| `--has-links` | only scores with links |

> Two ways to find a show's charts: `--keyword` (looser, reads the keywords tag) and
> `--setlist` (exact, reads real setlist membership). For **editing**, trust `--setlist`.
> Comparing the two surfaces charts that should be in a show but aren't filed in it.

---

## Examples

```bash
# What's in here?
python3 forscore_4sb.py SLP-automation.4sb info

# The kit-change map for one show
python3 forscore_4sb.py SLP-automation.4sb midi --setlist "Sula and the Joyful Noise"

# Find everything by a composer, with metadata
python3 forscore_4sb.py SLP-automation.4sb search --composer "Suzan-Lori"

# Preview a stop-byte injection scoped to one setlist (writes nothing)
python3 forscore_4sb.py SLP-automation.4sb inject-stop \
    --setlist "Sula and the Joyful Noise" --out /dev/null --dry-run

# Do it for real -> new file; the other charts are left untouched
python3 forscore_4sb.py SLP-automation.4sb inject-stop \
    --setlist "Sula and the Joyful Noise" --out SLP-sula-stop.4sb

# A different byte instead of FC
python3 forscore_4sb.py SLP-automation.4sb inject-stop --byte FA --out test.4sb
```

After editing, copy the new `.4sb` to the iPad and restore it in forScore
(Tools → Backups). Keep the original until you've confirmed the kits and stop
behave on the M12.

---

## Safety guarantees

- `inject-stop` **never overwrites the input** (requires a different `--out`).
- It is **idempotent**: charts already leading with the stop byte are skipped.
- Only **member 0** (the store) is re-compressed; all PNGs and other blobs are
  byte-identical to the original. Confirmed via `verify` and a per-member diff.
- `--dry-run` reports exactly which Sends would change before anything is written.

---

## Library API

The CLI is a thin wrapper; import it from other repo scripts (e.g. `setlist_sync`):

```python
from forscore_4sb import Archive, decode_midi, encode_midi

a = Archive.load("SLP-automation.4sb")
store = a.store                                   # the decoded plist dict

cmds = decode_midi(store["Actual Miracle V1 - Drums.pdf|midi"])
# -> [{'value': 'B9 00 7D B9 20 01 C9 07', 'kind': 'hex'}]

store["Actual Miracle V1 - Drums.pdf|midi"] = encode_midi(
    [{"value": "FC", "kind": "hex"}] + cmds
)
a.set_store(store)                                # re-gzips member 0 only
a.save("out.4sb")
```

Helpers: `score_names(store)`, `midi_keys(store)`, `link_charts(store)`,
`setlist_membership(store)`, `field(store, filename, suffix)`.