#!/usr/bin/env python3
"""v2 matcher: rhythm via TIME-ALIGNED energy (reliable) + sound via MFCC voice-search.
Targets: rhythm >=99%, sound >=88%.  stream_match2.py <src> <bpm> <note> [--meter][--bars]"""
import sys, argparse, subprocess, time
import numpy as np, librosa, soundfile as sf, mido
ap=argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("bpm",type=float); ap.add_argument("note",type=int)
ap.add_argument("--meter",default="44"); ap.add_argument("--bars",type=int,default=2)
ap.add_argument("--iters",type=int,default=6); ap.add_argument("--voicescan",type=int,default=40)
a=ap.parse_args()
spb=12 if a.meter=="68" else 16
step=(60.0/a.bpm/6) if a.meter=="68" else (60.0/a.bpm/4)
SLOTS=a.bars*spb; loop_dur=SLOTS*step
P1=[n for n in mido.get_output_names() if "DTX-MULTI 12 Port 1" in n][0]
P1i=[n for n in mido.get_input_names() if "DTX-MULTI 12 Port 1" in n][0]
IDX=a.note-13; SR=44100

def src_features():
    y,sr=librosa.load(a.src,sr=SR,mono=True)
    env=librosa.onset.onset_strength(y=y,sr=sr); et=librosa.times_like(env,sr=sr)
    best=None
    for ph in np.linspace(0,step,24,endpoint=False):
        acc=np.zeros(SLOTS)
        for k,t in enumerate(et): acc[int(round((t-ph)/step))%SLOTS]+=env[k]
        p=acc/(acc.max()+1e-9); srt=np.sort(p)[::-1]; kk=max(1,int(.4*SLOTS)); c=srt[:kk].mean()-srt[kk:].mean()
        if best is None or c>best[0]: best=(c,p)
    tgt=set(int(s) for s in range(SLOTS) if best[1][s]>=0.32)
    mf=librosa.feature.mfcc(y=y,sr=sr,n_mfcc=13).mean(axis=1)
    return tgt, mf/np.linalg.norm(mf)

def capture(setup, dur):
    ff=subprocess.Popen(["ffmpeg","-y","-f","avfoundation","-i",":3","-t",f"{dur:.1f}","-ac","2","-ar",str(SR),"c.wav"],
        stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL); time.sleep(0.8); setup(); ff.wait()
    y,sr=sf.read("c.wav"); 
    return (y.mean(1) if y.ndim>1 else y), sr

def rhythm_pass(cand_vel, reps):
    out=mido.open_output(P1)
    def setup():
        t0=time.perf_counter()
        out.send(mido.Message("note_on",channel=9,note=a.note,velocity=120)); out.send(mido.Message("note_off",channel=9,note=a.note,velocity=0)) # calib
        ev=sorted((0.6+r*loop_dur+s*step,v) for r in range(reps) for s,v in cand_vel.items())
        for when,v in ev:
            dt=(t0+when)-time.perf_counter()
            if dt>0: time.sleep(dt)
            out.send(mido.Message("note_on",channel=9,note=a.note,velocity=int(v))); out.send(mido.Message("note_off",channel=9,note=a.note,velocity=0))
    y,sr=capture(setup, 0.6+loop_dur*reps+1.0); out.close()
    env=np.abs(y); 
    # find calibration hit = first sample over thresh
    thr=0.05*env.max() if env.max()>0 else 1
    cal=next((i for i,v in enumerate(env) if v>max(thr,0.02)), None)
    if cal is None or env.max()<0.03: return None, env.max()
    W0=cal/sr+0.6
    def slot_energy(r,s):
        c=int((W0+r*loop_dur+s*step)*sr); seg=env[max(0,c-int(0.01*sr)):c+int(0.04*sr)]
        return seg.max() if len(seg) else 0
    ref=np.median([slot_energy(r,s) for r in range(reps) for s in cand_vel])  # typical hit level
    hit=np.zeros(SLOTS)
    for s in range(SLOTS):
        e=np.median([slot_energy(r,s) for r in range(reps)])
        hit[s]= e>0.4*ref
    return hit, env.max()

tgt, tmf = src_features()
print(f"target rhythm: {len(tgt)} hits/{SLOTS} @ {a.bpm}bpm; loop={loop_dur:.2f}s")
reps=max(3,int(14/loop_dur)); cand={s:105 for s in tgt}
# ---- RHYTHM to 99% ----
for it in range(a.iters):
    hit,peak=rhythm_pass(cand,reps)
    if hit is None: print(f"  iter{it}: SILENT (peak {peak:.2f}) — voice/state issue"); break
    capset=set(int(s) for s in range(SLOTS) if hit[s])
    correct=SLOTS-len(tgt^capset); acc=correct/SLOTS
    print(f"  rhythm iter{it}: peak={peak:.2f} acc={acc*100:.0f}%  miss={sorted(tgt-capset)} extra={sorted(capset-tgt)}")
    if acc>=0.99: print("  ** RHYTHM 99% **"); break
    for s in tgt-capset: cand[s]=min(127,cand.get(s,105)+15)   # boost dropped
    for s in capset-tgt: cand.pop(s,None)                       # trim spurious
# ---- SOUND voice-search to 88% ----
print("sound voice-search (cat 0x07 percussion):")
inp=mido.open_input(P1i); out=mido.open_output(P1)
def voice_sim(num):
    out.send(mido.Message("sysex",data=[0x43,0x10,0x7F,0x0F,0x10,IDX,0x02,num])); time.sleep(0.05)
    def setup():
        time.sleep(0.2); out.send(mido.Message("note_on",channel=9,note=a.note,velocity=110)); out.send(mido.Message("note_off",channel=9,note=a.note,velocity=0))
    y,sr=capture(setup,1.2)
    if np.abs(y).max()<0.02: return -1
    mf=librosa.feature.mfcc(y=y,sr=sr,n_mfcc=13).mean(axis=1); mf=mf/np.linalg.norm(mf)
    return float(np.dot(mf,tmf))
best=(-1,None)
for num in range(1,a.voicescan+1):
    sim=voice_sim(num)
    if sim>best[0]: best=(sim,num)
print(f"  best voice cat07/#{best[1]}: sound sim={best[0]*100:.0f}%")
# set the winning voice + pan hard right
if best[1]:
    out.send(mido.Message("sysex",data=[0x43,0x10,0x7F,0x0F,0x10,IDX,0x02,best[1]])); time.sleep(0.03)
    out.send(mido.Message("sysex",data=[0x43,0x10,0x7F,0x0F,0x10,IDX,0x04,0x7F]))
inp.close(); out.close()
print(f"RESULT note{a.note}: rhythm target {len(tgt)} hits, sound {best[0]*100:.0f}% (voice #{best[1]}), panned hard-right")
