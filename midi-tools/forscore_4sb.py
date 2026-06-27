#!/usr/bin/env python3
"""
forscore_4sb.py - read, search and edit forScore .4sb backups (format <--4SBV02-->).

Container:  b"<--4SBV02-->" then repeated [namelen:16][bloblen:16][name][gzip blob]
            (16-byte length fields are right-justified ASCII integers).
Entry 0 is the metadata store: a flat binary-plist dict keyed by namespace, e.g.
    <file>|title  <file>|composer  <file>|keywords  <file>|labels  <file>|bpm
    <file>|midi   (NSKeyedArchiver array of {value,kind} dicts)
    <file>|added  <file>&BLU;<page>&BLU;bluePoints  (links)
    &SET;<name>   (setlist -> list of {FilePath,Identifier,Title})
    &SYS;<key>    (app settings)
All other archive members are cached page PNGs / vector-annotation blobs.

Edits only re-gzip the one member they change; every other member is copied
byte-for-byte. A no-op round trip is byte-identical (see `verify`).

CLI:  info | scores | search | midi | links | setlists | extract-plist | verify | inject-stop
Most commands accept the shared FILTER flags (see -h): --name --title --composer
--keyword --label --setlist --match(any field) --has-midi --has-links. Filters AND together;
regex, case-insensitive.
"""
import sys, gzip, plistlib, argparse, re
from plistlib import UID

MAGIC = b"<--4SBV02-->"


# ----------------------------- container ------------------------------------
class Archive:
    def __init__(self, header_name, entries):
        self.header_name = header_name
        self.entries = entries            # list of [name, gzip_blob]

    @classmethod
    def load(cls, path):
        data = open(path, "rb").read()
        if data[:12] != MAGIC:
            raise ValueError("not a 4SBV02 backup (bad magic)")
        pos, entries = 12, []
        while pos < len(data):
            nl = int(data[pos:pos + 16].decode().strip()); pos += 16
            bl = int(data[pos:pos + 16].decode().strip()); pos += 16
            name = data[pos:pos + nl].decode("utf-8"); pos += nl
            entries.append([name, data[pos:pos + bl]]); pos += bl
        return cls(entries[0][0], entries)

    def to_bytes(self):
        out = bytearray(MAGIC)
        for name, blob in self.entries:
            nb = name.encode("utf-8")
            out += str(len(nb)).rjust(16).encode()
            out += str(len(blob)).rjust(16).encode()
            out += nb + blob
        return bytes(out)

    def save(self, path):
        open(path, "wb").write(self.to_bytes())

    @property
    def store(self):
        return plistlib.loads(gzip.decompress(self.entries[0][1]))

    def set_store(self, pl):
        raw = plistlib.dumps(pl, fmt=plistlib.FMT_BINARY)
        self.entries[0][1] = gzip.compress(raw, mtime=0)   # forScore uses mtime=0


# ----------------------------- MIDI codec -----------------------------------
def decode_midi(blob):
    a = plistlib.loads(bytes(blob)); objs = a["$objects"]
    arr = objs[a["$top"]["root"].data]
    out = []
    for u in arr["NS.objects"]:
        d = objs[u.data]
        kk = [objs[x.data] for x in d["NS.keys"]]
        vv = [objs[x.data] for x in d["NS.objects"]]
        out.append(dict(zip(kk, vv)))
    return out


def encode_midi(cmds):
    objects = ["$null"]
    def add(o):
        objects.append(o); return UID(len(objects) - 1)
    arr_uid = add({"$class": None, "NS.objects": []})
    dict_cls = add({"$classes": ["NSDictionary", "NSObject"], "$classname": "NSDictionary"})
    arr_cls = add({"$classes": ["NSArray", "NSObject"], "$classname": "NSArray"})
    objects[arr_uid.data]["$class"] = arr_cls
    pool = {}
    def s(v):
        if v not in pool: pool[v] = add(v)
        return pool[v]
    cmd_uids = []
    for c in cmds:
        keys = list(c.keys())
        cmd_uids.append(add({"$class": dict_cls,
                             "NS.keys": [s(k) for k in keys],
                             "NS.objects": [s(c[k]) for k in keys]}))
    objects[arr_uid.data]["NS.objects"] = cmd_uids
    return plistlib.dumps({"$version": 100000, "$archiver": "NSKeyedArchiver",
                           "$top": {"root": arr_uid}, "$objects": objects},
                          fmt=plistlib.FMT_BINARY)


# ----------------------------- store helpers --------------------------------
TEXT_FIELDS = ("title", "composer", "keywords", "labels")

def score_names(store):
    return sorted({k[:-len("|added")] for k in store if k.endswith("|added")})

def midi_keys(store):
    return sorted(k for k in store if k.endswith("|midi"))

def link_charts(store):
    return {k.split("&BLU;")[0] for k in store if "bluePoints" in k}

def setlist_membership(store):
    m = {}
    for k in store:
        if k.startswith("&SET;") and isinstance(store[k], list):
            name = k[5:]
            for item in store[k]:
                if isinstance(item, dict) and item.get("FilePath"):
                    m.setdefault(item["FilePath"], set()).add(name)
    return m

def field(store, fn, suffix):
    return store.get(fn + "|" + suffix)


# ----------------------------- selector -------------------------------------
def add_filter_args(sp):
    g = sp.add_argument_group("filters (AND together; regex, case-insensitive)")
    g.add_argument("--name", help="match the PDF filename")
    g.add_argument("--title", help="match the title field")
    g.add_argument("--composer", help="match the composer field")
    g.add_argument("--keyword", help="match the keywords field")
    g.add_argument("--label", help="match the labels field")
    g.add_argument("--setlist", help="member of a setlist whose name contains this (substring)")
    g.add_argument("--match", dest="anyfield",
                   help="match ANY text field or the filename")
    g.add_argument("--has-midi", action="store_true", help="only scores with a MIDI Send")
    g.add_argument("--has-links", action="store_true", help="only scores that have links")

def select(store, args):
    """Return filenames matching all provided filters (none -> all scores)."""
    sl = setlist_membership(store)
    lk = link_charts(store)
    def rx(p): return re.compile(p, re.I) if p else None
    f = {n: rx(getattr(args, n, None)) for n in ("name", "title", "composer")}
    fkw, flab, fany = rx(getattr(args, "keyword", None)), rx(getattr(args, "label", None)), rx(getattr(args, "anyfield", None))
    setl = getattr(args, "setlist", None)
    want_midi, want_links = getattr(args, "has_midi", False), getattr(args, "has_links", False)
    out = []
    for fn in score_names(store):
        g = lambda suf: str(field(store, fn, suf) or "")
        if f["name"] and not f["name"].search(fn): continue
        if f["title"] and not f["title"].search(g("title")): continue
        if f["composer"] and not f["composer"].search(g("composer")): continue
        if fkw and not fkw.search(g("keywords")): continue
        if flab and not flab.search(g("labels")): continue
        if setl and not any(setl.lower() in s.lower() for s in sl.get(fn, ())): continue
        if want_midi and fn + "|midi" not in store: continue
        if want_links and fn not in lk: continue
        if fany:
            hay = " ".join([fn] + [g(x) for x in TEXT_FIELDS])
            if not fany.search(hay): continue
        out.append(fn)
    return out


# ----------------------------- commands -------------------------------------
def cmd_info(a, args):
    raw0 = gzip.decompress(a.entries[0][1])
    st = a.store
    pngs = sum(1 for n, _ in a.entries if n.endswith(".png"))
    print(f"backup label : {a.header_name}")
    print(f"members      : {len(a.entries)}  ({pngs} PNGs, {len(a.entries)-1-pngs} other)")
    print(f"store        : {len(raw0):,} bytes (binary plist)")
    print(f"scores       : {len(score_names(st))}   with |midi: {len(midi_keys(st))}   with links: {len(link_charts(st))}")
    print(f"setlists     : {[k[5:] for k in st if k.startswith('&SET;')]}")

def cmd_scores(a, args):
    st = a.store
    mk, lk = set(midi_keys(st)), link_charts(st)
    for fn in select(st, args):
        flags = []
        if fn + "|midi" in mk: flags.append("MIDI")
        if fn in lk: flags.append("links")
        print(f"  {('['+','.join(flags)+']') if flags else '':16} {fn}")

def cmd_search(a, args):
    st = a.store
    sl = setlist_membership(st); lk = link_charts(st)
    sel = select(st, args)
    print(f"{len(sel)} match\n")
    for fn in sel:
        g = lambda s: field(st, fn, s)
        print(f"  • {g('title') or fn}")
        print(f"      file: {fn}")
        meta = []
        for s in ("composer", "keywords", "labels", "bpm"):
            if g(s) not in (None, ""): meta.append(f"{s}={g(s)}")
        sets = sorted(sl.get(fn, ()))
        if sets: meta.append("setlists=" + ", ".join(sets))
        tags = []
        if fn + "|midi" in st: tags.append("MIDI")
        if fn in lk: tags.append("links")
        if tags: meta.append("[" + ",".join(tags) + "]")
        for m in meta: print(f"      {m}")

def cmd_midi(a, args):
    st = a.store
    sel = set(select(st, args))
    for k in midi_keys(st):
        if k[:-5] not in sel: continue
        vals = "; ".join(f"{c.get('value')} [{c.get('kind')}]" for c in decode_midi(st[k]))
        print(f"  {k[:-5][:46]:46} -> {vals}")

def cmd_links(a, args):
    st = a.store
    for chart in sorted(link_charts(st)):
        print(f"  {chart}")
        for k in sorted(x for x in st if x.startswith(chart + "&BLU;")):
            for s in st[k]:
                print(f"      {s}")

def cmd_setlists(a, args):
    st = a.store
    for k in sorted(x for x in st if x.startswith("&SET;")):
        v = st[k]
        print(f"  {k[5:]}  ({len(v) if isinstance(v,list) else '?'} items)")

def cmd_extract_plist(a, args):
    open(args.out, "wb").write(gzip.decompress(a.entries[0][1]))
    print(f"wrote metadata plist -> {args.out}")

def cmd_verify(a, args):
    ok = a.to_bytes() == open(args.file, "rb").read()
    print("byte-identical no-op round trip:", ok)
    sys.exit(0 if ok else 1)

def cmd_inject_stop(a, args):
    if args.out == args.file:
        sys.exit("refusing to overwrite the input; choose a different --out")
    st = a.store
    sel = set(select(st, args))
    stop = {"value": args.byte.upper(), "kind": "hex"}
    changed = []
    for k in midi_keys(st):
        fn = k[:-5]
        if fn not in sel: continue
        cmds = decode_midi(st[k])
        if cmds and cmds[0].get("value", "").upper() == stop["value"]:
            continue  # idempotent: already leads with the stop
        st[k] = encode_midi([stop] + cmds)
        changed.append(fn)
    if args.dry_run:
        print(f"[dry run] would prepend {stop['value']} to {len(changed)} Send(s)")
        for c in changed: print("   ", c)
        return
    a.set_store(st); a.save(args.out)
    chk = Archive.load(args.out)
    print(f"wrote {args.out}  ({len(changed)} Send(s) edited)")
    if changed:
        print("verify first edit ->", decode_midi(chk.store[changed[0] + "|midi"]))
    for c in changed: print("   ", c)


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("file")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("info")
    for name in ("scores", "search", "midi"):
        add_filter_args(sub.add_parser(name))
    sub.add_parser("links")
    sub.add_parser("setlists")
    ep = sub.add_parser("extract-plist"); ep.add_argument("--out", default="store.plist")
    sub.add_parser("verify")
    ij = sub.add_parser("inject-stop", help="prepend a stop byte to matching score Sends")
    ij.add_argument("--byte", default="FC", help="hex byte to prepend (default FC = MIDI Stop)")
    ij.add_argument("--out", required=True, help="output .4sb (must differ from input)")
    ij.add_argument("--dry-run", action="store_true")
    add_filter_args(ij)
    args = p.parse_args()

    a = Archive.load(args.file)
    {"info": cmd_info, "scores": cmd_scores, "search": cmd_search, "midi": cmd_midi,
     "links": cmd_links, "setlists": cmd_setlists, "extract-plist": cmd_extract_plist,
     "verify": cmd_verify, "inject-stop": cmd_inject_stop}[args.cmd](a, args)


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        try: sys.stdout.close()
        except Exception: pass