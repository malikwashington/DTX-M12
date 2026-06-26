#!/usr/bin/env python3
"""Role-aware M12 percussion loop producer for GOSPEL / R&B — built around space & feel.

Design rules (from the player), 4/4:
  * no metronomic pulse on the downbeats
  * DEEP low pitch on beat 1 of every OTHER bar (the structural "1" of a 2-bar phrase)
  * HIGH pitch (maraca) ONLY on beat 4 of each bar -> anchors the "in 4" feeling
  * syncopation + SPACE: wide dynamics (ghosts->accents), pitch articulation (conga
    open/mute/slap), swing, voices entering/leaving, real negative space
  * keep the backbeat (2 & 4) clear for the live snare; keep the COUNTER (call-&-response)

Voices are real M12 samples (./samples); each carries the full cat-0x10 voice param set,
applied in a stereo offline render (no hardware needed). Exports .wav + .mid + .params.json.

  role_producer.py compose --bpm 80 --meter 44|68|98 [--bars 2] [--space 0..1] [--seed N]
"""
import sys, os, json, argparse, glob
import numpy as np, soundfile as sf

SR = 44100
SAMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "samples")

def find_sample(s):
    for f in sorted(glob.glob(os.path.join(SAMP, "*.wav"))):
        if s.upper() in os.path.basename(f).upper():
            return f
    return None

def V(tune=64, cutoff=120, res=12, atk=0, dec=64, rel=32, pan=100, vol=100, rev=12, cho=0, var=0):
    return dict(voice_tune=tune, cutoff=cutoff, resonance=res, attack=atk, decay=dec,
                release=rel, pan=pan, volume=vol, reverb_send=rev, chorus_send=cho, variation_send=var)

# role -> sample, M12 note, default voice params.  deep = low conga; anchor = mid conga talk.
ROLES = {
    "deep":    {"samp": "CONG",     "note": 83, "params": V(tune=52, cutoff=88, res=20, dec=98, rel=60, pan=98, vol=114, rev=26)},
    "anchor":  {"samp": "CONG",     "note": 84, "params": V(tune=66, cutoff=108, res=22, dec=66, rel=36, pan=100, vol=96, rev=18)},
    "pulse4":  {"samp": "MRCA",     "note": 88, "params": V(cutoff=124, res=8, dec=30, rel=14, pan=106, vol=92, rev=6)},
    "shaker":  {"samp": "HOSHAKER", "note": 89, "params": V(cutoff=120, res=10, dec=26, rel=12, pan=102, vol=78, rev=8)},
    "counter": {"samp": "CLAV",     "note": 87, "params": V(tune=70, cutoff=114, res=14, dec=42, rel=20, pan=108, vol=82, rev=14)},
    "color":   {"samp": "TAMB",     "note": 86, "params": V(cutoff=122, res=10, dec=104, rel=60, pan=110, vol=76, rev=30)},
    "clap":    {"samp": "HOCLAP1",  "note": 82, "params": V(cutoff=108, res=8, dec=64, rel=90, pan=100, vol=62, rev=72)},  # backbeat: subtle + WET
    "hihat":   {"samp": "CDHATC",   "note": 90, "params": V(tune=70, cutoff=126, res=6, dec=18, rel=10, pan=104, vol=66, rev=10)},  # bright top-end ticks
}

# Backbeat voice PALETTE — same role, different timbres built from samples + voice params.
# (Tone design: a "snap" is just a clap with a tight envelope + resonant filter pop, etc.)
BACKBEAT = {
    "clap":   {"samp": "HOCLAP1", "params": V(cutoff=108, res=8,  dec=64, rel=90, pan=100, vol=62, rev=72)},  # full, wet
    "snap":   {"samp": "HOCLAP1", "params": V(tune=70, cutoff=122, res=44, dec=14, rel=8, pan=100, vol=60, rev=34)},  # tight resonant pop
    "snaphi": {"samp": "CDCLAP",  "params": V(tune=76, cutoff=124, res=40, dec=12, rel=6, pan=102, vol=56, rev=42)},  # finger-snap, higher
    "rim":    {"samp": "CDRIM",   "params": V(tune=66, cutoff=124, res=20, dec=18, rel=12, pan=100, vol=58, rev=30)},  # cross-stick click
    "rimwet": {"samp": "DBRIM",   "params": V(tune=64, cutoff=120, res=16, dec=24, rel=20, pan=100, vol=58, rev=58)},  # wet rim
}

def meter_geom(meter):
    return {"44": (16, 4), "68": (12, 3), "98": (18, 3)}[meter]   # (slots_per_bar, slots_per_beat-ish)

def slots_step(bpm, bars, meter):
    spb, _ = meter_geom(meter)
    base = 16 if meter == "44" else (6 if meter == "68" else 9)
    step = 60.0 / bpm / (4 if meter == "44" else (3 if meter == "68" else 4.5))  # 16th-ish grid
    return bars * spb, step, spb

# ---------- grammar ----------
def gen_44(slots, rng, space):
    bar = 16; nb = slots // bar
    R = {k: [] for k in ROLES}
    # TIMEKEEPER (shaker): STEADY 16ths — the PULSE. No negative space here; present on every
    # 16th, consistent every bar, swung, with a gentle gospel dynamic groove (the '&' lilts a
    # touch, e/a are ghosts). Downbeats are MEDIUM, not hammered.
    for s in range(slots):
        pos = s % 4
        v = (rng.uniform(0.46, 0.58) if pos == 0 else
             rng.uniform(0.5, 0.64) if pos == 2 else rng.uniform(0.28, 0.4))
        R["shaker"].append((s, v, 0.0))
    for b in range(nb):
        s0 = b * bar
        if b % 2 == 0:                                   # DEEP "1" on every other bar
            R["deep"].append((s0, rng.uniform(0.9, 1.0), rng.uniform(-2, 1)))
        R["pulse4"].append((s0 + 12, rng.uniform(0.75, 0.95), 0.0))     # HIGH maraca on beat 4
        R["clap"].append((s0 + 4, rng.uniform(0.5, 0.62), 0.0))         # BACKBEAT clap on 2 & 4
        R["clap"].append((s0 + 12, rng.uniform(0.5, 0.62), 0.0))        # (subtle + wet, not empty)
        # conga talk = the SPACE/diversity layer (sparseness lives HERE, over a steady pulse)
        cand = [s0 + p for p in (3, 6, 7, 10, 11, 14, 15)]
        rng.shuffle(cand)
        for s in cand[:max(2, int(round((1 - space) * 4 + 2)))]:
            slap = rng.random() < 0.5
            R["anchor"].append((s, rng.uniform(0.55, 0.8) if slap else rng.uniform(0.32, 0.5),
                                rng.uniform(6, 10) if slap else rng.uniform(2, 5)))
    # counter (clave) + color (tambourine): ornaments — also the space layer
    occ = {s for s, _, _ in R["anchor"]} | {s for s, _, _ in R["deep"]}
    for s in range(slots):
        if s % 4 != 0 and s not in occ and rng.random() < (1 - space) * 0.22:
            R["counter"].append((s, rng.uniform(0.45, 0.7), rng.uniform(-1, 2)))
    for s in range(slots):
        if s % 2 == 1 and rng.random() < (1 - space) * 0.12:
            R["color"].append((s, rng.uniform(0.4, 0.65), 0.0))
    return R

def gen_compound(slots, rng, space, meter):
    """6/8 & 9/8: dotted-quarter beats (6 sixteenths each), 12/8-bell-derived, space ethos."""
    spb, _ = meter_geom(meter); nb = slots // spb
    R = {k: [] for k in ROLES}
    for b in range(nb):
        s0 = b * spb
        beats = list(range(0, spb, 6))               # 6/8: [0,6]  9/8: [0,6,12]
        # TIMEKEEPER: steady 8th-note hat pulse (every even 16th), consistent — the PULSE.
        for s in range(0, spb, 2):
            v = rng.uniform(0.5, 0.62) if s in beats else rng.uniform(0.34, 0.46)
            R["hihat"].append((s0 + s, v, 0.0))
        if b % 2 == 0:
            R["deep"].append((s0, rng.uniform(0.9, 1.0), rng.uniform(-2, 1)))   # deep 1, alt bars
        R["pulse4"].append((s0 + beats[-1], rng.uniform(0.7, 0.92), 0.0))       # maraca, last beat
        if len(beats) >= 2:                           # WET subtle clap on the backbeat (content)
            R["clap"].append((s0 + beats[1], rng.uniform(0.5, 0.64), 0.0))
        for p in ((3, 5, 8, 10) if meter == "68" else (3, 5, 8, 10, 14, 16)):   # conga talk = space layer
            if rng.random() < (1 - space) * 0.5:
                slap = rng.random() < 0.5
                R["anchor"].append((s0 + p, rng.uniform(0.5, 0.75) if slap else rng.uniform(0.32, 0.5),
                                    rng.uniform(6, 10) if slap else rng.uniform(2, 5)))
        for p in (5, 11):
            if rng.random() < (1 - space) * 0.3:
                R["counter"].append((s0 + p, rng.uniform(0.45, 0.65), rng.uniform(-1, 2)))
    return R

def compose(slots, meter, rng, space):
    swing = rng.uniform(0.08, 0.2)                        # gospel/R&B shuffle feel
    R = gen_44(slots, rng, space) if meter == "44" else gen_compound(slots, rng, space, meter)
    return R, swing

# ---------- DSP render (applies the voice params) ----------
def pitch_shift(x, semis):
    if abs(semis) < 0.01: return x
    r = 2 ** (semis / 12.0); idx = np.arange(0, len(x), r)
    return np.interp(idx, np.arange(len(x)), x)

def lowpass(x, cut, res):
    if cut >= 126: return x
    fc = min(200 * (2 ** (cut / 18.0)), SR / 2 * 0.98)
    X = np.fft.rfft(x); f = np.fft.rfftfreq(len(x), 1 / SR)
    H = 1.0 / (1.0 + (f / fc) ** 2) + (res / 20.0) * np.exp(-((f - fc) / (fc * 0.25 + 1)) ** 2)
    return np.fft.irfft(X * H, len(x))

def amp_env(x, atk, dec):
    n = len(x); t = np.arange(n) / SR; e = np.ones(n)
    if atk > 0: e *= np.clip(t / max(atk / 127 * 0.05, 1e-4), 0, 1)
    return x * e * np.exp(-t / (0.02 + dec / 127 * 0.8))

def voiced(s, p, pit, vel):
    x = pitch_shift(s, (p["voice_tune"] - 64) + pit)
    return amp_env(lowpass(x, p["cutoff"], p["resonance"]), p["attack"], p["decay"]) * (p["volume"] / 127) * vel

def reverb_ir(seconds=0.5):
    n = int(seconds * SR); t = np.arange(n) / SR
    ir = np.random.default_rng(0).standard_normal(n) * np.exp(-t / (seconds * 0.45))
    return ir / (np.abs(ir).sum() + 1e-9)

def render(R, slots, bpm, meter, swing, reps=2):
    _, step, spb = slots_step(bpm, slots // meter_geom(meter)[0], meter)
    N = int((slots * step * reps + 1.5) * SR)
    buf = np.zeros((N, 2)); wet = np.zeros(N)            # wet = reverb send bus (mono)
    cache = {k: sf.read(find_sample(ROLES[k]["samp"]))[0] for k in R if R[k]}
    sub = 4 if meter == "44" else 3
    for k, hits in R.items():
        if not hits: continue
        p = ROLES[k]["params"]; pan = p["pan"] / 127; rev = p["reverb_send"] / 127
        gL, gR = np.cos(pan * np.pi / 2), np.sin(pan * np.pi / 2)
        for rep in range(reps):
            for (slot, vel, pit) in hits:
                sw = 0.0 if (slot % sub == 0 or k in ("deep", "pulse4")) else swing * step
                i = int(((rep * slots + slot) * step + sw) * SR)
                seg = voiced(cache[k], p, pit, vel); n = min(len(seg), N - i)
                if n <= 0: continue
                buf[i:i + n, 0] += gL * seg[:n]; buf[i:i + n, 1] += gR * seg[:n]
                if rev > 0.05: wet[i:i + n] += rev * seg[:n]
    if wet.any():
        wr = np.convolve(wet, reverb_ir())[:N] * 0.6
        buf[:, 0] += wr; buf[:, 1] += wr
    return buf / ((np.abs(buf).max() + 1e-9) * 1.02)

def to_smf(R, slots, bpm, meter, path):
    import mido
    mid = mido.MidiFile(type=1); tr = mido.MidiTrack(); mid.tracks.append(tr)
    ppq = mid.ticks_per_beat; _, step, spb = slots_step(bpm, slots // meter_geom(meter)[0], meter)
    tps = max(1, round(ppq * step * bpm / 60.0)); tr.append(mido.MetaMessage("set_tempo", tempo=mido.bpm2tempo(int(bpm))))
    ev = []
    for k, hits in R.items():
        for (slot, vel, _) in hits:
            ev.append((slot * tps, mido.Message("note_on", channel=9, note=ROLES[k]["note"],
                                                velocity=int(np.clip(vel * 110 + 17, 1, 127)))))
            ev.append((slot * tps + tps // 2, mido.Message("note_off", channel=9, note=ROLES[k]["note"])))
    ev.sort(key=lambda x: x[0]); t = 0
    for at, m in ev:
        m.time = at - t; t = at; tr.append(m)
    tr.append(mido.MetaMessage("end_of_track", time=tps)); mid.save(path)

def fmt(R, slots):
    out = []
    for k in ROLES:
        if R.get(k):
            row = {s: v for (s, v, _) in R[k]}
            out.append(f"  {k:8} " + "".join(" .:-=+*#%@"[min(9, int(row[s] * 9))] if s in row else "." for s in range(slots)))
    return "\n".join(out)

def cmd_compose(a):
    if a.backbeat in BACKBEAT:                            # swap the backbeat voice timbre
        ROLES["clap"]["samp"] = BACKBEAT[a.backbeat]["samp"]
        ROLES["clap"]["params"] = BACKBEAT[a.backbeat]["params"]
    slots, step, spb = slots_step(a.bpm, a.bars, a.meter)
    rng = np.random.default_rng(a.seed)
    R, swing = compose(slots, a.meter, rng, a.space)
    print(f"=== {a.bpm} BPM {a.meter} {a.bars}bar  space={a.space}  swing={swing:.2f} ===")
    print("  (beats: '|' every {})".format(spb // (4 if a.meter=='44' else 1)))
    print(fmt(R, slots))
    od = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "loops", "generated")
    os.makedirs(od, exist_ok=True); out = a.out or f"role_{int(a.bpm)}_{a.meter}"
    sf.write(os.path.join(od, out + ".wav"), render(R, slots, a.bpm, a.meter, swing), SR)
    to_smf(R, slots, a.bpm, a.meter, os.path.join(od, out + ".mid"))
    params = {k: {"sample": os.path.basename(find_sample(ROLES[k]["samp"])), "note": ROLES[k]["note"],
                  "voice_params": ROLES[k]["params"], "nhits": len(R[k])} for k in R if R[k]}
    json.dump({"bpm": a.bpm, "meter": a.meter, "bars": a.bars, "swing": round(swing, 3), "roles": params,
               "hits": {k: [[s, round(v, 2), round(p, 1)] for (s, v, p) in R[k]] for k in R if R[k]}},
              open(os.path.join(od, out + ".params.json"), "w"), indent=1)
    print(f"\npreview -> loops/generated/{out}.wav   MIDI -> {out}.mid   params -> {out}.params.json")
    for k in R:
        if R[k]:
            p = ROLES[k]["params"]
            print(f"  {k:8} {params[k]['sample']:14} note{ROLES[k]['note']}  tune{p['voice_tune']} cut{p['cutoff']} dec{p['decay']} pan{p['pan']} vol{p['volume']}")

def main():
    ap = argparse.ArgumentParser(); sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("compose")
    c.add_argument("--bpm", type=float, default=80); c.add_argument("--meter", default="44")
    c.add_argument("--bars", type=int, default=2); c.add_argument("--space", type=float, default=0.5)
    c.add_argument("--seed", type=int, default=0); c.add_argument("--out", default="")
    c.add_argument("--backbeat", default="clap", help="clap|snap|snaphi|rim|rimwet")
    c.set_defaults(fn=cmd_compose); a = ap.parse_args(); a.fn(a)

if __name__ == "__main__":
    main()
