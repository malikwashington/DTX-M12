#!/usr/bin/env python3
"""Autonomous streaming matcher (no thumb drive, no human per iteration).
Target = source loop's rhythm (envelope-fold at locked tempo).
Loop: stream candidate -> M12 plays voices -> capture audio -> score vs target ->
repair (boost dropped hits, trim extras) -> repeat until match.
  stream_match.py <src.wav> <bpm> <note> [--meter 44|68] [--bars 2] [--iters 5]
"""
import sys, argparse, subprocess, time
import numpy as np, librosa, soundfile as sf, mido

ap=argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("bpm",type=float); ap.add_argument("note",type=int)
ap.add_argument("--meter",default="44"); ap.add_argument("--bars",type=int,default=2)
ap.add_argument("--iters",type=int,default=5)
a=ap.parse_args()
spb=12 if a.meter=="68" else 16
step=(60.0/a.bpm/6) if a.meter=="68" else (60.0/a.bpm/4)
SLOTS=a.bars*spb; loop_dur=SLOTS*step
pout=[n for n in mido.get_output_names() if "DTX-MULTI 12 Port 1" in n][0]

def target_slots(src):
    y,sr=librosa.load(src,sr=44100,mono=True)
    env=librosa.onset.onset_strength(y=y,sr=sr); et=librosa.times_like(env,sr=sr)
    best=None
    for ph in np.linspace(0,step,24,endpoint=False):
        acc=np.zeros(SLOTS)
        for k,t in enumerate(et): acc[int(round((t-ph)/step))%SLOTS]+=env[k]
        p=acc/(acc.max()+1e-9); srt=np.sort(p)[::-1]; kk=max(1,int(.4*SLOTS))
        c=srt[:kk].mean()-srt[kk:].mean()
        if best is None or c>best[0]: best=(c,p)
    p=best[1]; return set(int(s) for s in range(SLOTS) if p[s]>=0.32)

def stream_capture(cand_vel, reps):
    """stream candidate (slot->velocity) for `reps` loops while recording; return per-slot hit-rate."""
    out=mido.open_output(pout)
    dur=loop_dur*reps+1.5
    ff=subprocess.Popen(["ffmpeg","-y","-f","avfoundation","-i",":3","-t",f"{dur:.1f}",
        "-ac","2","-ar","44100","sm.wav"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    time.sleep(0.8)
    t0=time.perf_counter()
    events=[]
    for r in range(reps):
        for s,v in cand_vel.items():
            events.append((r*loop_dur+s*step, v))
    events.sort()
    for when,v in events:
        dt=(t0+when)-time.perf_counter()
        if dt>0: time.sleep(dt)
        out.send(mido.Message("note_on",channel=9,note=a.note,velocity=int(v)))
        out.send(mido.Message("note_off",channel=9,note=a.note,velocity=0))
    out.close(); ff.wait()
    y,sr=sf.read("sm.wav")
    if y.ndim>1: y=y.mean(1)
    capL=loop_dur
    on=librosa.onset.onset_detect(y=y,sr=sr,units="time",backtrack=True,delta=0.06)
    # fold captured onsets into slots; align phase to start (first onset ~ first streamed hit)
    if len(on)==0: return np.zeros(SLOTS), 0.0
    ph=on[0]-0  # first onset = first hit
    hits=np.zeros(SLOTS); 
    for t in on:
        s=int(round((t-ph)/step))%SLOTS; hits[s]+=1
    rate=hits/max(1,reps)
    return rate, float(np.abs(y).max())

def f1(cap_set, tgt):
    tp=len(cap_set&tgt); fp=len(cap_set-tgt); fn=len(tgt-cap_set)
    pr=tp/(tp+fp+1e-9); rc=tp/(tp+fn+1e-9); return 2*pr*rc/(pr+rc+1e-9),pr,rc

tgt=target_slots(a.src)
print(f"target: {len(tgt)} hits on {SLOTS}-slot grid @ {a.bpm}bpm {a.meter}; loop={loop_dur:.2f}s")
reps=max(2,int(12/loop_dur))
cand={s:100 for s in tgt}        # candidate starts = target
for it in range(a.iters):
    rate,peak=stream_capture(cand, reps)
    cap_set=set(int(s) for s in range(SLOTS) if rate[s]>=0.5)
    sc,pr,rc=f1(cap_set,tgt)
    print(f"  iter{it}: peak={peak:.2f} captured={sorted(cap_set)} score={sc:.2f} (P{pr:.2f}/R{rc:.2f})")
    if peak<0.03: print("   !! M12 silent on this note — check kit/voice/state"); break
    if sc>=0.9: print("   ** MATCH **"); break
    # repair: boost velocity on target slots the M12 dropped; (extras handled by trusting target)
    for s in tgt-cap_set: cand[s]=min(127,cand.get(s,100)+20)
print("final candidate slots:", sorted(cand.keys()))
