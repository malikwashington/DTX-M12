#!/usr/bin/env python3
"""Fold a source at a LOCKED tempo (from the YouTube title) and emit an SMF on a
given note. Resolves the octave errors from auto-detection.
  make_loop.py <src.wav> <bpm> <note> <out.mid> [--meter 44|68] [--bars 2]
"""
import sys, argparse, numpy as np, librosa, mido
ap=argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("bpm",type=float); ap.add_argument("note",type=int)
ap.add_argument("out"); ap.add_argument("--meter",default="44"); ap.add_argument("--bars",type=int,default=2)
ap.add_argument("--name",default=None)
a=ap.parse_args()
y,sr=librosa.load(a.src,sr=44100,mono=True); dur=len(y)/sr
env=librosa.onset.onset_strength(y=y,sr=sr); et=librosa.times_like(env,sr=sr)
if a.meter=="68":
    spb=12; step=60.0/a.bpm/6           # 6/8: 12 sixteenths per bar
else:
    spb=16; step=60.0/a.bpm/4           # 4/4: 16 sixteenths per bar
slots=a.bars*spb; loop=slots*step
# phase search: maximize energy concentration on grid
best=None
for ph in np.linspace(0,step,24,endpoint=False):
    acc=np.zeros(slots); cnt=np.zeros(slots)
    for k,t in enumerate(et):
        s=int(round((t-ph)/step))%slots; acc[s]+=env[k]; cnt[s]+=1
    prof=acc/np.maximum(cnt,1); p=prof/(prof.max()+1e-9)
    srt=np.sort(p)[::-1]; k=max(1,int(0.4*slots)); clar=srt[:k].mean()-srt[k:].mean()
    if best is None or clar>best[0]: best=(clar,ph,prof.copy())
_,ph,prof=best; prof=prof/(prof.max()+1e-9)
pat=[s for s in range(slots) if prof[s]>=0.32]
mid=mido.MidiFile(type=0); tr=mido.MidiTrack(); mid.tracks.append(tr)
tr.append(mido.MetaMessage("set_tempo",tempo=mido.bpm2tempo(a.bpm)))
tr.append(mido.MetaMessage("track_name",name=(a.name or a.out[:-4])[:11]))
t16=mid.ticks_per_beat//4
last=0
for s in pat:
    on=s*t16; v=int(40+87*min(1,prof[s]))
    tr.append(mido.Message("note_on",channel=9,note=a.note,velocity=v,time=on-last))
    tr.append(mido.Message("note_off",channel=9,note=a.note,velocity=0,time=t16//2))
    last=on+t16//2
mid.save(a.out)
print(f"  {a.out}: {int(a.bpm)}bpm {a.meter} note{a.note} -> {len(pat)}/{slots} slots, {mid.length:.2f}s loop")
print("   grid:", "".join(str(min(9,int(p*9))) for p in prof))
