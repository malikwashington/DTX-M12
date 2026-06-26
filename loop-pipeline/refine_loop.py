#!/usr/bin/env python3
"""Extract a 2-bar percussion loop from a source snippet by ENVELOPE FOLDING.

Better than discrete onset-matching for percussion (esp. continuous shakers):
detect tempo+beat phase, fold the onset-strength envelope over every loop repeat
into a 32-slot intensity profile (accents reinforce, noise averages out), threshold
to a pattern, and classify each active slot low/mid/high by spectral centroid.
Reports per-slot intensity 0-9 and a 'groove clarity' score.
"""
import sys, numpy as np, librosa, json

def fold(env, et, tempo, ph, slots, dur):
    step=60.0/tempo/4
    acc=np.zeros(slots); cnt=np.zeros(slots)
    for k,t in enumerate(et):
        s=int(round((t-ph)/step))%slots; acc[s]+=env[k]; cnt[s]+=1
    return acc/np.maximum(cnt,1), step

def centroid(y,sr,t):
    i=int(t*sr); w=y[i:i+int(0.05*sr)]
    if len(w)<64: return 1500.0
    S=np.abs(np.fft.rfft(w*np.hanning(len(w)))); f=np.fft.rfftfreq(len(w),1/sr)
    return float((f*S).sum()/(S.sum()+1e-9))
def lane(c): return "low" if c<800 else ("mid" if c<2200 else "high")

def refine(path, bars=2):
    y,sr=librosa.load(path,sr=44100,mono=True); dur=len(y)/sr
    env=librosa.onset.onset_strength(y=y,sr=sr)
    et=librosa.times_like(env,sr=sr)
    tempo,beats=librosa.beat.beat_track(y=y,sr=sr,units="time")
    tempo=float(np.atleast_1d(tempo)[0])
    slots=bars*16
    # search tempo octave + phase; pick the grid where energy concentrates most on slots
    best=None
    for tp in sorted({round(tempo,1),round(tempo/2,1),round(tempo*2,1)}):
        if not (50<=tp<=220): continue
        step=60.0/tp/4
        if slots*step>dur: continue
        for ph in np.linspace(0,step,24,endpoint=False):
            prof,_=fold(env,et,tp,ph,slots,dur)
            p=prof/ (prof.max()+1e-9)
            # clarity = how bimodal: gap between active mean and rest mean
            srt=np.sort(p)[::-1]; k=max(1,int(0.4*slots))
            clarity=float(srt[:k].mean()-srt[k:].mean())
            if best is None or clarity>best["clarity"]:
                best=dict(clarity=clarity,tempo=tp,phase=float(ph),step=step)
    prof,step=fold(env,et,best["tempo"],best["phase"],slots,dur)
    prof=prof/(prof.max()+1e-9)
    thr=0.32
    pattern=[s for s in range(slots) if prof[s]>=thr]
    # velocity per slot (40..127) and lane via nearest onset centroid
    on=librosa.onset.onset_detect(y=y,sr=sr,units="time",backtrack=True)
    lanes={}; vel={}
    for s in pattern:
        slot_t=best["phase"]+s*step
        near=[t for t in on if abs(((t-best['phase'])%(slots*step))-s*step)<step*0.6]
        c=np.mean([centroid(y,sr,t) for t in near]) if near else 1500.0
        lanes[s]=lane(float(c)); vel[s]=int(40+87*min(1,prof[s]))
    best.update(slots=slots,pattern=pattern,lanes=lanes,vel=vel,
                prof=[round(float(x),2) for x in prof])
    return best

if __name__=="__main__":
    path=sys.argv[1] if len(sys.argv)>1 else "loop1_src.wav"
    b=refine(path)
    print(f"tempo={b['tempo']:.1f}  phase={b['phase']*1000:.0f}ms  groove-clarity={b['clarity']:.2f}")
    print("intensity per 16th (0-9):")
    print("  "+"".join(str(min(9,int(p*9))) for p in b["prof"]))
    LAB={"low":"L","mid":"M","high":"H"}
    for ln in ("low","mid","high"):
        row="".join(LAB[ln] if b["lanes"].get(s)==ln else "." for s in range(b["slots"]))
        if any(c!="." for c in row): print(f"  {ln:4} |{row}|")
    json.dump(b,open(path+".loop.json","w"))
    print("saved",path+".loop.json")
