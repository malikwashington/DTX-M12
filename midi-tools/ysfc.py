#!/usr/bin/env python3
"""ysfc.py — DTX-MULTI 12 / Yamaha YSFC save-file tool.

Reads the M12's USB save files (the `.MT*` family — `.MTA` all-data backup,
`.MTK` kit, `.MTP` pattern, `.MTW` wave). All are YSFC containers; this tool
parses the chunk directory and the catalog chunks, extracts waveforms to WAV,
and performs the proven (checksum-free) per-kit tempo edit.

Format (reverse-engineered + byte-verified against TEST.MTA — see docs/DOSSIER.md §6):
  0x00  " 8H39 <TYPE> ... Ver 01.00"   (TYPE = ALL / PATTERN / KIT / WAVE ...)
  0x30  magic "YSFC"
  0x80  top chunk directory: 8-byte entries  tag[4] + abs_offset(u32 BE)
        E*** = catalog/directory chunks, D*** = data chunks. No checksums.

  Catalog (E*) chunk:  tag[4] + size(u32) + 0x18 pad, then records @stride 0x20:
        name[16] + order(u32) + bytesize(u32) + diroff(u32) + id/index(u32)
        record count = (size - 0x20) // 0x20
  DWAV data chunk: tag[4] + size(u32), then a per-wave directory (stride 0x20,
        12 used bytes: id(u32) + resv(u32) + cumoffset(u32)), then wave blocks.
        wave block @ DWAV + cumoffset:  0x50 header (name[16]@+0, srate u16BE@+0x36)
        then PCM = signed 16-bit BIG-ENDIAN mono.  block size = EWAV bytesize.
  DKIT data chunk: tag[4] + size(u32), directory @+0x20 (stride 0x20:
        kitid(u32) + resv(u32) + reloffset(u32)); kit body @ DKIT + reloffset;
        body: name[16]@+0, tempo u16BE@+0x14  (bpm = MSB*128 + LSB).

This tool is READ-ONLY except `set-tempo`, which writes a NEW file (never the
input) unless --inplace is given. It never embeds or redistributes Yamaha data;
it only parses files you already own.
"""
import argparse, struct, sys, wave
from pathlib import Path

HDR_DIR = 0x80          # top chunk directory start
REC_STRIDE = 0x20       # catalog record stride
CHUNK_HDR = 0x20        # tag+size+pad before first record
WAVE_HDR = 0x50         # per-waveform header before PCM
SR_OFF = 0x36           # sample-rate u16 BE within the wave header
TEMPO_OFF = 0x14        # tempo u16 BE within a kit body


def u32(d, o): return struct.unpack(">I", d[o:o + 4])[0]
def u16(d, o): return struct.unpack(">H", d[o:o + 2])[0]


def name_at(d, o, n=16):
    raw = d[o:o + n]
    raw = raw.split(b"\xff")[0].split(b"\x00")[0]
    return raw.decode("latin1").rstrip()


class YSFC:
    def __init__(self, path):
        self.path = Path(path)
        self.d = self.path.read_bytes()
        if self.d[0x30:0x34] != b"YSFC":
            raise ValueError(f"{path}: not a YSFC file (magic at 0x30 != 'YSFC')")
        self.kind = self.d[0:0x20].decode("latin1").strip()
        self.dir = self._read_dir()

    def _read_dir(self):
        """tag -> absolute offset, from the 0x80 chunk directory."""
        out, o = {}, HDR_DIR
        while o + 8 <= len(self.d):
            tag = self.d[o:o + 4]
            if not (len(tag) == 4 and tag.isalpha() and tag.isupper()
                    and tag[0:1] in (b"E", b"D")):
                break
            out[tag.decode()] = u32(self.d, o + 4)
            o += 8
        return out

    def catalog(self, tag):
        """Parse an E* catalog chunk -> list of record dicts."""
        if tag not in self.dir:
            return []
        base = self.dir[tag]
        size = u32(self.d, base + 4)
        n = max(0, (size - CHUNK_HDR) // REC_STRIDE)
        recs = []
        for i in range(n):
            r = base + CHUNK_HDR + i * REC_STRIDE
            if r + REC_STRIDE > len(self.d):
                break
            recs.append({
                "name": name_at(self.d, r),
                "order": u32(self.d, r + 16),
                "bytesize": u32(self.d, r + 20),
                "diroff": u32(self.d, r + 24),
                "id": u32(self.d, r + 28),
            })
        return recs

    def kit_tempos(self):
        """kit id -> (name, bpm) read from DKIT bodies."""
        out = {}
        if "DKIT" not in self.dir:
            return out
        dk = self.dir["DKIT"]
        for rec in self.catalog("EKIT"):
            # DKIT directory entry for this kit id (1-based order in the directory)
            de = dk + CHUNK_HDR + (rec["id"] - 1) * REC_STRIDE
            if de + 12 > len(self.d):
                continue
            reloff = u32(self.d, de + 8)
            body = dk + reloff
            if body + TEMPO_OFF + 2 > len(self.d):
                continue
            bpm = self.d[body + TEMPO_OFF] * 128 + self.d[body + TEMPO_OFF + 1]
            out[rec["id"]] = (name_at(self.d, body), bpm, body)
        return out

    def waves(self):
        """Yield (id, name, srate, pcm_be_bytes) for each waveform."""
        if "DWAV" not in self.dir:
            return
        dw = self.dir["DWAV"]
        for rec in self.catalog("EWAV"):
            de = dw + rec["diroff"]
            if de + 12 > len(self.d):
                continue
            cum = u32(self.d, de + 8)
            blk = dw + cum
            if blk + WAVE_HDR > len(self.d):
                continue
            srate = u16(self.d, blk + SR_OFF) or 44100
            pcm = self.d[blk + WAVE_HDR: blk + rec["bytesize"]]
            yield rec["id"], rec["name"], srate, pcm


# ----------------------------- subcommands -----------------------------------

def cmd_map(y, _):
    print(f"{y.path.name}: YSFC '{y.kind}'  ({len(y.d):,} bytes)")
    print("  chunk    abs-offset    size")
    for tag, off in y.dir.items():
        size = u32(y.d, off + 4) if off + 8 <= len(y.d) else 0
        print(f"  {tag}   0x{off:08x}   0x{size:x}")


def cmd_kits(y, _):
    temps = y.kit_tempos()
    named = [r for r in y.catalog("EKIT")
             if r["name"] and r["name"].lower() not in ("user kit", "")]
    print(f"{y.path.name}: {len(named)} named kit(s)")
    print("  slot  id   tempo  name")
    for r in named:
        t = temps.get(r["id"], (None, None, None))[1]
        tt = f"{t:>4}" if t else "  --"
        print(f"  {r['order']+1:>4}  {r['id']:>3}  {tt}   {r['name']}")


def cmd_waves(y, _):
    recs = y.catalog("EWAV")
    print(f"{y.path.name}: {len(recs)} waveform(s)")
    print("  id   bytesize   name")
    for r in recs:
        print(f"  {r['id']:>3}  0x{r['bytesize']:08x}  {r['name']}")


def cmd_patterns(y, _):
    recs = y.catalog("EPTN")
    print(f"{y.path.name}: {len(recs)} pattern(s)")
    for r in recs:
        print(f"  id {r['id']:>3}  0x{r['bytesize']:06x}  {r['name']}")


def cmd_info(y, _):
    cmd_map(y, _)
    for tag, fn in (("EKIT", cmd_kits), ("EWAV", cmd_waves), ("EPTN", cmd_patterns)):
        if tag in y.dir:
            print()
            fn(y, _)


def cmd_extract_waves(y, a):
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    count = 0
    for wid, name, srate, pcm in y.waves():
        if a.id and wid != a.id:
            continue
        # BE signed-16 -> LE for WAV
        ba = bytearray(pcm[: len(pcm) & ~1])
        ba[0::2], ba[1::2] = ba[1::2], ba[0::2]
        safe = "".join(c if c.isalnum() or c in "-_." else "_" for c in name) or f"wave{wid}"
        fn = out / f"{wid:03d}_{safe}.wav"
        with wave.open(str(fn), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(srate)
            w.writeframes(bytes(ba))
        count += 1
        print(f"  wrote {fn}  ({len(ba)//2} samples @ {srate} Hz)")
    print(f"extracted {count} waveform(s) -> {out}/")


def cmd_set_tempo(y, a):
    temps = y.kit_tempos()
    match = [(kid, nm, bpm, body) for kid, (nm, bpm, body) in temps.items()
             if nm.lower() == a.kit.lower()]
    if not match:
        names = ", ".join(sorted({nm for nm, _, _ in temps.values()}))
        sys.exit(f"kit '{a.kit}' not found. Kits with a body: {names or '(none)'}")
    if not (1 <= a.bpm <= 1000):
        sys.exit("bpm must be 1..1000")
    if not (30 <= a.bpm <= 300):
        print(f"  warning: {a.bpm} bpm is outside the usual 30..300 range")
    d = bytearray(y.d)
    for kid, nm, oldbpm, body in match:
        d[body + TEMPO_OFF] = a.bpm >> 7
        d[body + TEMPO_OFF + 1] = a.bpm & 0x7F
        print(f"  kit {kid} '{nm}': tempo {oldbpm} -> {a.bpm} bpm  (body 0x{body:x})")
    if a.inplace:
        outp = y.path
    else:
        outp = Path(a.out) if a.out else y.path.with_suffix(f".{a.bpm}bpm{y.path.suffix}")
    outp.write_bytes(d)
    print(f"wrote {outp}  (no checksum to fix; load it on the unit to apply)")


def main():
    p = argparse.ArgumentParser(description="DTX-MULTI 12 / Yamaha YSFC save-file tool")
    sub = p.add_subparsers(dest="cmd", required=True)
    for name, fn, help in (
        ("map", cmd_map, "list the top chunk directory"),
        ("kits", cmd_kits, "list named kits + their tempos"),
        ("waves", cmd_waves, "list the waveform catalog"),
        ("patterns", cmd_patterns, "list the pattern catalog"),
        ("info", cmd_info, "summary of everything"),
    ):
        s = sub.add_parser(name, help=help)
        s.add_argument("file")
        s.set_defaults(fn=fn)

    s = sub.add_parser("extract-waves", help="DWAV -> WAV files")
    s.add_argument("file")
    s.add_argument("--out", default="waves_out", help="output dir (default: waves_out)")
    s.add_argument("--id", type=int, default=0, help="only this wave id")
    s.set_defaults(fn=cmd_extract_waves)

    s = sub.add_parser("set-tempo", help="set a kit's tempo (proven checksum-free edit)")
    s.add_argument("file")
    s.add_argument("--kit", required=True, help="kit name (case-insensitive)")
    s.add_argument("--bpm", required=True, type=int)
    s.add_argument("--out", help="output file (default: <name>.<bpm>bpm.<ext>)")
    s.add_argument("--inplace", action="store_true", help="overwrite the input file")
    s.set_defaults(fn=cmd_set_tempo)

    a = p.parse_args()
    try:
        y = YSFC(a.file)
    except (ValueError, FileNotFoundError) as e:
        sys.exit(str(e))
    a.fn(y, a)


if __name__ == "__main__":
    main()
