#!/usr/bin/env python3
"""capture_voices.py — render the M12's PRESET voices to WAV via the audio interface.

The 1,277 preset voices are NOT in any save file and not reachable over MIDI as sample
data (they live in the device's preset wave ROM). The only way to get them computer-side
is to render-and-capture: select each voice over SysEx, trigger a note, record the M12's
analog output, split + trim + normalize. This tool does that, one voice category at a time.

Pipeline per category:
  - set pad-1's voice slot (cat 0x10, idx 0x0C) to (category, number) over SysEx,
  - trigger note 25 (= idx 0x0C) on ch10, record the Scarlett (avfoundation dev :3),
  - one continuous take per category; split by the logged trigger times (calibrated to
    the first onset), trim to onset, peak-normalize to -3 dBFS, save named WAVs.

Category byte → name was calibrated live by the voice-number CLAMP (writing a high number
and reading back the category's voice count). Percussion + electronic set = bytes below.
Output is Yamaha-derived audio → samples/ is gitignored; never commit the WAVs.

Usage:  ../.venv/bin/python3 capture_voices.py [catbyte ...]   (default: percussion set)
        AUDIO_DEV=":3" overrides the avfoundation input.
"""
import mido, time, subprocess, wave, array, math, os, re, sys
import pypdf

PORT_MATCH = "DTX-MULTI 12 Port 1"
AUDIO_DEV = os.environ.get("AUDIO_DEV", ":3")
IDX = 0x0C          # pad-1 voice slot (cat 0x10 index)
NOTE = 25           # note that triggers idx 0x0C (idx = note-13)
VOL = 70            # headroom volume (vol 120 clipped); output is normalized anyway
SLOT = 1.2          # seconds per voice (trigger + ring)
DATALIST = os.path.join(os.path.dirname(__file__), "..", "docs", "dtxm12_data_list.pdf")

# byte -> (folder, pdf-page-index, Data-List category marker). Counts come live from the clamp.
CATS = {
    5:  ("electric_percussion",         9,  "Electric Percussion"),
    6:  ("cuban_percussion",            10, "Cuban Percussion"),
    7:  ("brazilian_percussion",        10, "Brazilian Percussion"),
    8:  ("india_percussion",            10, "India Percussion"),
    9:  ("japanese_percussion",         10, "Japanese Percussion"),
    10: ("percussion_cat10",            10, None),          # 62 voices, label TBD
    11: ("orchestral_misc_percussion",  11, "Orchestral/Misc Percussion"),
    13: ("african_arabic_percussion",   11, "African/ Arabic Percussion"),
}
PERCUSSION = [5, 6, 7, 8, 9, 10, 11, 13]


def open_midi():
    po = next(n for n in mido.get_output_names() if PORT_MATCH in n)
    pi = next(n for n in mido.get_input_names() if PORT_MATCH in n)
    return mido.open_output(po), mido.open_input(pi)


def wr(o, off, val):
    o.send(mido.Message("sysex", data=[0x43, 0x10, 0x7F, 0x0F, 0x10, IDX, off, val & 0x7F]))
    time.sleep(0.007)


def voice_count(o, i, cat):
    """Live: write a high voice number, read back the clamp = the category's voice count."""
    wr(o, 0x01, cat); wr(o, 0x02, 127)
    for _ in i.iter_pending():
        pass
    o.send(mido.Message("sysex", data=[0x43, 0x20, 0x7F, 0x0F, 0x10, IDX, 0x00]))
    t0 = time.monotonic()
    while time.monotonic() - t0 < 0.4:
        for m in i.iter_pending():
            if m.type == "sysex":
                d = list(m.data); cnt = (d[4] << 7) | d[5]
                return d[9:9 + cnt][2]
        time.sleep(0.004)
    return 0


_HDR = re.compile(r'([A-Z][A-Za-z0-9])\s+([A-Z][A-Za-z/&\(\)\.\- ]{2,30}?)\s+No\.\s*Name(?:\s+No\.\s*Name)?')


def datalist_names(page_idx, marker, count):
    """{num: name} for a category from the Data List PDF.

    Locate the category's header, take the text up to the next header, then split
    on the 3-digit voice numbers — the text between two numbers is one voice name
    (handles the 2-column layout and multi-word names like 'Madal Din1')."""
    if marker is None:
        return {}
    try:
        t = " ".join((pypdf.PdfReader(DATALIST).pages[page_idx].extract_text() or "").split())
    except Exception:
        return {}
    hs = list(_HDR.finditer(t))
    for k, m in enumerate(hs):
        cat = m.group(2).lower()
        if marker.lower() in cat or cat in marker.lower():
            end = hs[k + 1].start() if k + 1 < len(hs) else len(t)
            parts = re.split(r'\b(\d{3})\b', t[m.end():end])
            out = {}
            for j in range(1, len(parts) - 1, 2):
                n = int(parts[j])
                s = re.sub(r'\s{2,}.*$', '', parts[j + 1].strip())
                s = re.sub(r'[^A-Za-z0-9 \.\-/!#\+&]+$', '', s)[:11].strip()
                if 1 <= n <= count and s and s.lower() != 'no assign':
                    out.setdefault(n, s)
            return out
    return {}


def capture_category(o, i, cat):
    folder, page, marker = CATS[cat]
    count = voice_count(o, i, cat)
    names = datalist_names(page, marker, count)
    outdir = os.path.join(os.path.dirname(__file__), "..", "samples", "presets", folder)
    os.makedirs(outdir, exist_ok=True)
    print(f"[cat {cat}] {folder}: {count} voices -> {outdir}")

    raw = f"/private/tmp/claude-501/voicecap/cat{cat}_raw.wav"
    os.makedirs(os.path.dirname(raw), exist_ok=True)
    dur = int(2 + count * SLOT + 6)                 # generous: avoid the tail running off the end
    wr(o, 0x03, VOL); wr(o, 0x0C, 0x7F); wr(o, 0x0E, 0); wr(o, 0x0F, 0x7F); wr(o, 0x10, 0x7F)
    p = subprocess.Popen(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-f",
                          "avfoundation", "-i", AUDIO_DEV, "-t", str(dur), "-ac", "2",
                          "-ar", "48000", raw], stderr=subprocess.PIPE)
    time.sleep(1.6)
    t0 = time.monotonic(); times = []
    for n in range(1, count + 1):
        wr(o, 0x01, cat); wr(o, 0x02, n); time.sleep(0.12)
        times.append(time.monotonic() - t0)
        o.send(mido.Message("note_on", channel=9, note=NOTE, velocity=127)); time.sleep(0.2)
        o.send(mido.Message("note_off", channel=9, note=NOTE, velocity=0)); time.sleep(SLOT - 0.32)
    p.communicate()

    w = wave.open(raw, "rb"); sr = w.getframerate(); ch = w.getnchannels(); nf = w.getnframes()
    a = array.array("h"); a.frombytes(w.readframes(nf)); mono = a[0::ch] if ch > 1 else a
    win = int(sr * 0.01)
    rms = [math.sqrt(sum(x * x for x in mono[k:k + win]) / win) for k in range(0, len(mono) - win, win)]
    noise = sorted(rms)[len(rms) // 8] if rms else 0; thr = max(noise * 6, 200)
    first = next((j * 0.01 for j in range(1, len(rms)) if rms[j] > thr and rms[j - 1] <= thr), times[0])
    off = first - times[0]

    saved, silent, clipped = 0, [], 0
    for n in range(1, count + 1):
        s = int((times[n - 1] + off) * sr)
        seg = mono[s:s + int(1.4 * sr)]
        pk = max((abs(x) for x in seg), default=0)
        if pk < 800:
            silent.append(n); continue
        if pk >= 32767:
            clipped += 1
        st = next((k for k in range(len(seg)) if abs(seg[k]) > thr * 1.5), 0)
        seg = seg[max(0, st - int(0.005 * sr)):]
        g = min(23170 / (pk or 1), 8.0)
        out = array.array("h", (max(-32768, min(32767, int(x * g))) for x in seg))
        nm = re.sub(r"[^A-Za-z0-9]+", "_", names.get(n, f"v{n}")).strip("_") or f"v{n}"
        ww = wave.open(os.path.join(outdir, f"{n:03d}_{nm}.wav"), "wb")
        ww.setnchannels(1); ww.setsampwidth(2); ww.setframerate(sr); ww.writeframes(out.tobytes()); ww.close()
        saved += 1
    print(f"[cat {cat}] saved {saved}/{count}  silent={silent}  clipped_samples={clipped}")
    return saved, count


def main():
    cats = [int(x) for x in sys.argv[1:]] or PERCUSSION
    o, i = open_midi()
    total_s = total_c = 0
    try:
        for c in cats:
            if c not in CATS:
                print(f"[cat {c}] not a known percussion category; skipping"); continue
            s, n = capture_category(o, i, c)
            total_s += s; total_c += n
    finally:
        o.close(); i.close()
    print(f"\nDONE: {total_s}/{total_c} voices captured across {len(cats)} categories.")


if __name__ == "__main__":
    main()
