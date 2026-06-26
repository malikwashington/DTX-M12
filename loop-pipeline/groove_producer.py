#!/usr/bin/env python3
"""M12 groove producer — generate percussion loops in the style of reference loops.

Symbolic, closed-loop design:
  references (audio) --mine--> grid descriptors --> STYLE ENVELOPE (your taste)
  generate (grid) --score vs envelope+intent+constraints--> evolve --> best loops --> SMF

Descriptors are computed on a per-lane GRID (low/mid/high x slots), so they are the SAME
for audio-derived reference grids and symbolic generated grids — that's what lets the agent
compare its own output to your loops in feature space (NOT note-for-note).

  groove_producer.py build-envelope --list ../loops/loop_list.txt --cache <dir>
  groove_producer.py generate --envelope style_envelope.json --bpm 80 --meter 44 --bars 2
                              [--density .35] [--gens 60] [--pop 80] [--out best]
"""
import sys, os, json, argparse, subprocess
import numpy as np, librosa

SR = 44100
LANES = ["low", "mid", "high"]
DESC_NAMES = (["overall_density"] + [f"den_{l}" for l in LANES] + [f"bal_{l}" for l in LANES]
              + ["syncopation", "dynamics"] + [f"hist{i:02d}" for i in range(16)])

# ---------- grid + descriptors (common feature space) ----------
def slots_step(bpm, bars, meter):
    spb = 12 if meter == "68" else 16
    step = 60.0 / bpm / (6 if meter == "68" else 4)
    return bars * spb, step, spb

def grid_descriptors(grid, slots, spb):
    G = np.stack([grid[l] for l in LANES])          # 3 x slots, intensity 0..1
    active = G > 0.05
    total = G.sum() + 1e-9
    overall = active.mean()
    den = [active[i].mean() for i in range(3)]
    bal = [G[i].sum() / total for i in range(3)]
    beat = slots // spb * spb                         # guard
    onbeat_cols = np.arange(0, slots, 4 if spb == 16 else 3)
    onb = G[:, onbeat_cols].sum()
    sync = (total - onb) / total
    dyn = float(G[active].std()) if active.any() else 0.0
    hist = np.zeros(16)
    for s in range(slots):
        hist[int(s % 16)] += G[:, s].sum()
    hist = hist / (hist.sum() + 1e-9)
    return np.array([overall] + den + bal + [sync, dyn] + list(hist))

def audio_to_grid(path, bpm, bars, meter):
    y, _ = librosa.load(path, sr=SR, mono=True)
    _, y = librosa.effects.hpss(y)                    # percussive only
    slots, step, spb = slots_step(bpm, bars, meter)
    env = librosa.onset.onset_strength(y=y, sr=SR); et = librosa.times_like(env, sr=SR)
    onsets = librosa.onset.onset_detect(y=y, sr=SR, units="time", backtrack=True)
    grid = {l: np.zeros(slots) for l in LANES}
    for t in onsets:
        s = int(round(t / step)) % slots
        i = int(t * SR); w = y[i:i + int(0.05 * SR)]
        if len(w) < 64:
            continue
        cen = float(librosa.feature.spectral_centroid(y=w, sr=SR).mean())
        lane = "low" if cen < 500 else ("mid" if cen < 2500 else "high")
        lo = int(np.searchsorted(et, t))           # backtracked onset is BEFORE the peak
        inten = float(env[lo:lo + 6].max() / (env.max() + 1e-9)) if lo < len(env) else 0.0
        grid[lane][s] = max(grid[lane][s], min(1.0, inten))
    return grid, slots, spb

# ---------- envelope ----------
def yt_window(url, out, start=30, dur=14):
    e = start + dur
    subprocess.run(["/Users/velvetmotion/Desktop/DTX-M12/.venv/bin/yt-dlp", "-x", "--audio-format", "wav",
                    "--download-sections", f"*{start}-{e}", "--force-keyframes-at-cuts",
                    "--quiet", "--no-warnings", "-o", out, url], check=True)

def parse_list(path):
    rows = []
    for ln in open(path):
        ln = ln.strip()
        if not ln or ln.startswith("#"):
            continue
        p = [x.strip() for x in ln.split("|")]
        if len(p) >= 7 and p[1].startswith("http"):
            rows.append({"file": p[0], "url": p[1], "name": p[3],
                         "bpm": float(p[4]), "meter": "68" if "6/8" in p[5] else "44"})
    return rows

def cmd_build_envelope(a):
    rows = parse_list(a.list)
    os.makedirs(a.cache, exist_ok=True)
    descs, per = [], {}
    for r in rows:
        wav = os.path.join(a.cache, r["file"] + ".wav")
        if not os.path.exists(wav):
            try:
                yt_window(r["url"], wav)
            except Exception as e:
                print(f"  download failed {r['file']}: {e}"); continue
        grid, slots, spb = audio_to_grid(wav, r["bpm"], 2, r["meter"])
        d = grid_descriptors(grid, slots, spb)
        descs.append(d); per[r["file"]] = {"name": r["name"], "bpm": r["bpm"], "meter": r["meter"],
                                            "desc": d.tolist()}
        print(f"  mined {r['file']:6} ({r['name']}) density={d[0]:.2f} sync={d[8]:.2f} "
              f"bal=[{d[4]:.2f},{d[5]:.2f},{d[6]:.2f}]")
    D = np.array(descs)
    env = {"names": DESC_NAMES, "mean": D.mean(0).tolist(), "std": (D.std(0) + 1e-6).tolist(),
           "n": len(D), "per_loop": per}
    json.dump(env, open(a.out, "w"), indent=1)
    print(f"\nstyle envelope ({len(D)} loops) -> {a.out}")

# ---------- generate (evolutionary, score vs envelope) ----------
def fmt_grid(grid, slots):
    out = []
    for l in LANES:
        out.append(f"  {l:4} " + "".join(
            (" .:-=+*#%@"[min(9, int(grid[l][s] * 9))]) if grid[l][s] > 0.05 else "." for s in range(slots)))
    return "\n".join(out)

def score(grid, slots, spb, env, intent):
    d = grid_descriptors(grid, slots, spb)
    mean = np.array(env["mean"]); std = np.maximum(np.array(env["std"]), 0.03)  # floor: stop degenerate dims exploding
    z = (d - mean) / std
    # weight the MEANINGFUL descriptors: density(0), syncopation(8), rhythmic-shape histogram(9:25);
    # de-emphasize the lane balance/per-lane density(1:8) which is degenerate for this bright material.
    style = -(z[0] ** 2) - 0.6 * (z[8] ** 2) - 0.4 * np.mean(z[9:] ** 2) - 0.12 * np.mean(z[1:8] ** 2)
    # intent: hit target overall density
    intent_pen = -3.0 * (d[0] - intent["density"]) ** 2
    # constraint: percussion-only references have NO kick -> drive the low lane toward empty
    low_pen = -2.5 * d[1]
    # constraint: leave the live-kit pocket — penalize hits on beats 2 & 4 (backbeat) in low/mid
    bb = [slots // 4, 3 * slots // 4] if spb == 16 else [slots // 2]
    pocket = -sum(grid["low"][b] + grid["mid"][b] for b in bb if b < slots) + low_pen
    # musicality: reward a steady high-lane timekeeper, penalize empty
    nonempty = 0 if (np.stack([grid[l] for l in LANES]) > 0.05).any() else -5
    return style + intent_pen + 0.5 * pocket + nonempty

def mutate(grid, slots, rng, rate=0.12):
    g = {l: grid[l].copy() for l in LANES}
    for l in LANES:
        for s in range(slots):
            if rng.random() < rate:
                if g[l][s] > 0.05:
                    g[l][s] = 0.0 if rng.random() < 0.4 else min(1.0, g[l][s] + rng.uniform(-.3, .3))
                else:
                    g[l][s] = rng.uniform(0.4, 1.0)
    return g

def crossover(a, b, slots, rng):
    cut = rng.integers(1, slots)
    return {l: np.concatenate([a[l][:cut], b[l][cut:]]) for l in LANES}

def rand_grid(slots, spb, rng, dens):
    g = {}
    for l in LANES:
        row = np.zeros(slots)
        for s in range(slots):
            onb = (s % (4 if spb == 16 else 3) == 0)
            if rng.random() < dens * (1.4 if onb else 0.8):
                row[s] = rng.uniform(0.5, 1.0) if onb else rng.uniform(0.4, 0.8)
        g[l] = row
    return g

# lanes -> M12 loop notes (top-of-range, panned HARD RIGHT per loop_list.txt routing)
LANE_NOTE = {"low": 84, "mid": 86, "high": 88}

def to_smf(grid, slots, bpm, meter, path):
    import mido
    mid = mido.MidiFile(type=1); tr = mido.MidiTrack(); mid.tracks.append(tr)
    ppq = mid.ticks_per_beat
    _, step, spb = slots_step(bpm, slots // (12 if meter == "68" else 16), meter)
    tps = max(1, round(ppq * step * bpm / 60.0))      # ticks per slot
    tr.append(mido.MetaMessage("set_tempo", tempo=mido.bpm2tempo(int(bpm)), time=0))
    ev = []                                           # (abs_tick, msg)
    for s in range(slots):
        for l in LANES:
            v = grid[l][s]
            if v > 0.05:
                n = LANE_NOTE[l]; vel = int(np.clip(v * 110 + 17, 1, 127))
                ev.append((s * tps, mido.Message("note_on", channel=9, note=n, velocity=vel)))
                ev.append((s * tps + tps // 2, mido.Message("note_off", channel=9, note=n, velocity=0)))
    ev.sort(key=lambda x: x[0])
    t = 0
    for at, m in ev:
        m.time = at - t; t = at; tr.append(m)
    tr.append(mido.MetaMessage("end_of_track", time=tps))   # pad to loop length
    mid.save(path)

def cmd_generate(a):
    env = json.load(open(a.envelope))
    slots, step, spb = slots_step(a.bpm, a.bars, a.meter)
    rng = np.random.default_rng(a.seed)
    intent = {"density": a.density}
    pop = [rand_grid(slots, spb, rng, a.density) for _ in range(a.pop)]
    history = []
    for gen in range(a.gens):
        scored = sorted(pop, key=lambda g: -score(g, slots, spb, env, intent))
        best = score(scored[0], slots, spb, env, intent)
        history.append(best)
        elite = scored[:max(2, a.pop // 5)]
        newpop = list(elite)
        while len(newpop) < a.pop:
            pa, pb = elite[rng.integers(len(elite))], elite[rng.integers(len(elite))]
            child = mutate(crossover(pa, pb, slots, rng), slots, rng)
            newpop.append(child)
        pop = newpop
    best = sorted(pop, key=lambda g: -score(g, slots, spb, env, intent))[0]
    print(f"=== generated loop @ {a.bpm} BPM {a.meter} {a.bars}bar, density~{a.density} ===")
    print(f"fitness: gen0 {history[0]:.2f} -> gen{a.gens-1} {history[-1]:.2f}  "
          f"(improved {history[-1]-history[0]:+.2f} over {a.gens} generations)")
    d = grid_descriptors(best, slots, spb)
    print(f"descriptors: density {d[0]:.2f} (target {a.density}), sync {d[8]:.2f}, "
          f"balance low/mid/high [{d[4]:.2f},{d[5]:.2f},{d[6]:.2f}]")
    env_d = np.array(env["mean"])
    print(f"   envelope:  density {env_d[0]:.2f},              sync {env_d[8]:.2f}, "
          f"balance [{env_d[4]:.2f},{env_d[5]:.2f},{env_d[6]:.2f}]")
    print(fmt_grid(best, slots))
    if a.out:
        json.dump({"bpm": a.bpm, "meter": a.meter, "bars": a.bars,
                   "grid": {l: best[l].tolist() for l in LANES},
                   "fitness": history[-1], "descriptors": d.tolist()},
                  open(a.out + ".json", "w"), indent=1)
        to_smf(best, slots, a.bpm, a.meter, a.out + ".mid")
        print(f"saved -> {a.out}.json + {a.out}.mid")

def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build-envelope"); b.add_argument("--list", required=True)
    b.add_argument("--cache", default="ref_cache"); b.add_argument("--out", default="style_envelope.json")
    b.set_defaults(fn=cmd_build_envelope)
    g = sub.add_parser("generate"); g.add_argument("--envelope", default="style_envelope.json")
    g.add_argument("--bpm", type=float, default=80); g.add_argument("--meter", default="44")
    g.add_argument("--bars", type=int, default=2); g.add_argument("--density", type=float, default=0.35)
    g.add_argument("--gens", type=int, default=60); g.add_argument("--pop", type=int, default=80)
    g.add_argument("--seed", type=int, default=0); g.add_argument("--out", default="")
    g.set_defaults(fn=cmd_generate)
    a = ap.parse_args(); a.fn(a)

if __name__ == "__main__":
    main()
