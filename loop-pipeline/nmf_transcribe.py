#!/usr/bin/env python3
"""NMF-based percussion-loop transcription for the DTX-MULTI 12.

Decompose a percussion loop's magnitude spectrogram into K spectral TEMPLATES
(each ~= one instrument's fingerprint) + ACTIVATIONS (each ~= that instrument's
onset pattern over time). Unsupervised separation AND transcription in one pass —
replaces the old "3 fixed brightness lanes" with the instruments actually present.

  nmf_transcribe.py --demo                 synthesize a known 3-instrument loop, prove recovery
  nmf_transcribe.py <audio.wav> -k 4 --bpm 100 --bars 2

Per component: classify by spectral centroid (low/mid/high) to map -> M12 voice and
to drop kit-like parts; peak-pick the activation -> onset slots on a 16th grid.
"""
import sys, argparse
import numpy as np, librosa

SR = 44100

# ---------- synthesis (ground-truth demo) ----------
def hit_tone(f, dur, sr=SR, decay=40.0):
    t = np.arange(int(dur*sr))/sr
    return np.sin(2*np.pi*f*t) * np.exp(-decay*t)

def hit_noise(dur, lo, hi, sr=SR, decay=60.0):
    t = np.arange(int(dur*sr))/sr
    n = np.random.randn(len(t))
    # crude bandpass via FFT mask
    N = np.fft.rfft(n); fr = np.fft.rfftfreq(len(t), 1/sr)
    N[(fr<lo)|(fr>hi)] = 0
    return np.fft.irfft(N, len(t)) * np.exp(-decay*t)

def synth_loop(bpm=100, bars=2, sr=SR, seed=0):
    rng = np.random.default_rng(seed)
    step = 60.0/bpm/4; slots = bars*16; dur = slots*step
    y = np.zeros(int(dur*sr)+sr//2)
    def place(sig, slot, gain):
        i = int(slot*step*sr); y[i:i+len(sig)] += gain*sig[:len(y)-i]
    # ground-truth patterns (slot indices within `slots`)
    conga  = [0,3,6,8,11,14,16,19,22,24,27,30]      # low tone ~180 Hz
    clave  = [0,6,10,16,22,26]                        # mid click ~1200 Hz (3-2 son-ish)
    shaker = list(range(0,slots,2))                  # high noise, steady 8ths
    truth = {"conga":set(conga), "clave":set(clave), "shaker":set(shaker)}
    for s in conga:  place(hit_tone(180,0.18,decay=35), s, 0.9*rng.uniform(.8,1))
    for s in clave:  place(hit_tone(1200,0.04,decay=120),s, 0.7*rng.uniform(.8,1))
    for s in shaker: place(hit_noise(0.05,4000,12000,decay=80), s, 0.5*rng.uniform(.7,1))
    return y, bpm, bars, truth

# ---------- NMF transcription ----------
def transcribe(y, sr, K, bpm, bars):
    S = np.abs(librosa.stft(y, n_fft=2048, hop_length=256))
    comps, acts = librosa.decompose.decompose(S, n_components=K, sort=True, random_state=0)
    freqs = librosa.fft_frequencies(sr=sr, n_fft=2048)
    times = librosa.times_like(acts, sr=sr, hop_length=256)
    step = 60.0/bpm/4; slots = bars*16
    out = []
    for k in range(K):
        cen = float((freqs*comps[:,k]).sum()/(comps[:,k].sum()+1e-9))
        lane = "low" if cen < 500 else ("mid" if cen < 2500 else "high")
        a = acts[k]/(acts[k].max()+1e-9)
        # peak-pick activation -> onset times -> quantize to grid slots
        pk = librosa.util.peak_pick(a, pre_max=3, post_max=3, pre_avg=3, post_avg=3, delta=0.12, wait=4)
        on_slots = sorted({int(round(times[p]/step)) % slots for p in pk if a[p] > 0.15})
        out.append({"k":k, "centroid":round(cen), "lane":lane, "slots":set(on_slots)})
    return out, slots

def fmt(slots, n): return "".join("X" if s in slots else "." for s in range(n))

# ---------- option 2: onset detection + timbre clustering ----------
def cluster_transcribe(y, sr, N, bpm, bars):
    """Detect each hit, feature-vector it (MFCC + noisiness + centroid), cluster into
    N timbre groups. Each cluster = an instrument; its onsets -> grid slots."""
    from sklearn.cluster import KMeans
    from sklearn.preprocessing import StandardScaler
    step = 60.0/bpm/4; slots = bars*16
    onsets = librosa.onset.onset_detect(y=y, sr=sr, units="time", backtrack=True,
                                         hop_length=256, delta=0.06)
    feats = []
    for t in onsets:
        i = int(t*sr); w = y[i:i+int(0.06*sr)]
        if len(w) < 128: w = np.pad(w, (0, 128-len(w)))
        mf = librosa.feature.mfcc(y=w, sr=sr, n_mfcc=13).mean(axis=1)
        cen = float(librosa.feature.spectral_centroid(y=w, sr=sr).mean())
        flat = float(librosa.feature.spectral_flatness(y=w).mean())   # noisiness
        zcr = float(librosa.feature.zero_crossing_rate(w).mean())
        feats.append(np.concatenate([mf, [cen/4000, flat*10, zcr*10]]))
    if not feats: return [], slots
    X = StandardScaler().fit_transform(np.array(feats))
    lab = KMeans(n_clusters=N, n_init=10, random_state=0).fit_predict(X)
    freqs_used = [f[13]*4000 for f in feats]
    out = []
    for c in range(N):
        idx = [k for k in range(len(onsets)) if lab[k] == c]
        if not idx: continue
        cen = float(np.mean([freqs_used[k] for k in idx]))
        lane = "low" if cen < 500 else ("mid" if cen < 2500 else "high")
        sl = sorted({int(round(onsets[k]/step)) % slots for k in idx})
        out.append({"k":c, "centroid":round(cen), "lane":lane, "slots":set(sl), "nhits":len(idx)})
    return out, slots

def best_match(slots, truth):
    return max(truth.items(), key=lambda kv: len(slots&kv[1])/max(1,len(slots|kv[1])))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("audio", nargs="?")
    ap.add_argument("--demo", action="store_true")
    ap.add_argument("--compare", action="store_true")
    ap.add_argument("-k","--components", type=int, default=3)
    ap.add_argument("--bpm", type=float, default=100)
    ap.add_argument("--bars", type=int, default=2)
    a = ap.parse_args()
    if a.compare:
        y, bpm, bars, truth = synth_loop(a.bpm, a.bars)
        slots = bars*16
        def score(comps):
            # for each ground-truth instrument, best Jaccard over recovered comps that map to it
            res={}
            for name, ts in truth.items():
                best=max((len(c['slots']&ts)/max(1,len(c['slots']|ts)) for c in comps), default=0.0)
                res[name]=best
            return res
        nmf,_ = transcribe(y, SR, max(3,a.components), bpm, bars)
        clu,_ = cluster_transcribe(y, SR, 3, bpm, bars)
        print(f"=== HEAD-TO-HEAD on synth {bars}-bar percussion loop @ {bpm} BPM (no kit) ===")
        print("ground truth:")
        for nm,ss in truth.items(): print(f"  {nm:7} {fmt(ss,slots)}")
        print(f"\nNMF (K={max(3,a.components)}) recovered:")
        for c in nmf: print(f"  k{c['k']} {c['lane']:4} {fmt(c['slots'],slots)}")
        print("clustering (N=3) recovered:")
        for c in clu: print(f"  c{c['k']} {c['lane']:4} {fmt(c['slots'],slots)}  ({c['nhits']} hits)")
        sn, sc = score(nmf), score(clu)
        print(f"\nper-instrument timing recall (Jaccard vs truth):")
        print(f"  {'instrument':9} {'NMF':>6} {'cluster':>8}")
        for nm in truth: print(f"  {nm:9} {sn[nm]:6.2f} {sc[nm]:8.2f}")
        print(f"  {'MEAN':9} {np.mean(list(sn.values())):6.2f} {np.mean(list(sc.values())):8.2f}")
        return
    if a.demo:
        y, bpm, bars, truth = synth_loop(a.bpm, a.bars)
        comps, slots = transcribe(y, SR, a.components, bpm, bars)
        print(f"=== NMF demo: synthesized {bars}-bar loop @ {bpm} BPM, {a.components} components ===\n")
        print("GROUND TRUTH:")
        for name, ss in truth.items(): print(f"  {name:7} {fmt(ss,slots)}")
        print("\nNMF-RECOVERED components (centroid Hz, lane, pattern):")
        for c in comps: print(f"  k{c['k']} {c['centroid']:5}Hz {c['lane']:4} {fmt(c['slots'],slots)}")
        # match each recovered comp to best ground-truth by slot overlap
        print("\nMATCH (recovered -> closest truth, Jaccard):")
        for c in comps:
            best=max(truth.items(), key=lambda kv: len(c['slots']&kv[1])/max(1,len(c['slots']|kv[1])))
            j=len(c['slots']&best[1])/max(1,len(c['slots']|best[1]))
            print(f"  k{c['k']} ({c['lane']}) -> {best[0]:7} Jaccard={j:.2f}")
    elif a.audio:
        y,_ = librosa.load(a.audio, sr=SR, mono=True)
        comps, slots = transcribe(y, SR, a.components, a.bpm, a.bars)
        print(f"=== NMF transcription of {a.audio}: {a.components} components ===")
        for c in comps: print(f"  k{c['k']} {c['centroid']:5}Hz {c['lane']:4} {fmt(c['slots'],slots)}  ({len(c['slots'])} hits)")
    else:
        sys.exit(__doc__)

if __name__ == "__main__":
    main()
