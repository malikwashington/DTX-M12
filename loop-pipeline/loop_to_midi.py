#!/usr/bin/env python3
"""YouTube loop -> PERCUSSION MIDI transcription for the DTX-MULTI 12.

The user is a drummer: we transcribe the PERCUSSION layer (congas, shakers,
tambourine, bells, claves, woodblock...) and DROP the drum-kit core (kick/snare),
so the M12 plays the percussion overlay while the user drums kick/snare/hats live.

Pipeline: download -> tempo+beat grid -> onset detect -> per-hit spectral profile
-> drop kick/snare, bucket the rest into 3 percussion lanes by brightness
-> quantize to 16th grid -> looped .mid on ch10 mapped to M12 pad notes.

Usage: loop_to_midi.py "<url>" [--start 12] [--dur 8] [--bars 2] [--out loop.mid]
                        [--keep-kit]   # also include kick/snare (off by default)
Rhythm transcription (groove -> MIDI), not a copy of the recording.
"""
import sys, subprocess, argparse
import numpy as np, librosa, mido

# percussion lanes -> M12 pad notes (assign percussion voices to these pads).
LANE_NOTE = {"perc_low": 45, "perc_mid": 48, "perc_high": 53}
KIT_NOTE  = {"kick": 36, "snare": 38}     # only used with --keep-kit
DROP_DEFAULT = {"kick", "snare"}

def download(url, start, dur):
    # grab only the needed window directly (fast for long videos), with retries
    s=start or 0; e=s+(dur or 10)
    subprocess.run(["yt-dlp","-x","--audio-format","wav",
                    "--download-sections", f"*{s}-{e}", "--force-keyframes-at-cuts",
                    "--retries","10","--file-access-retries","10","--fragment-retries","10",
                    "-o","yt_seg.%(ext)s", url], check=True)
    return "yt_seg.wav"

def profile(y, sr, t):
    """3 percussion lanes by brightness (sources are percussion loops, no kit)."""
    i=int(t*sr); w=y[i:i+int(0.05*sr)]
    if len(w)<64: return "perc_mid"
    win=w*np.hanning(len(w)); S=np.abs(np.fft.rfft(win)); f=np.fft.rfftfreq(len(w),1/sr)
    band=lambda lo,hi: float(S[(f>=lo)&(f<hi)].sum())+1e-9
    low,mid,high=band(20,150),band(150,2000),band(2000,sr/2); tot=low+mid+high
    cen=float((f*S).sum()/(S.sum()+1e-9))
    if low/tot>0.45 or cen<800: return "perc_low"    # surdo / low conga / djembe bass
    if cen<2200:                return "perc_mid"    # conga / bongo / clave / cowbell
    return "perc_high"                               # shaker / tambourine / bell / hat

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("url"); ap.add_argument("--start",type=float,default=0)
    ap.add_argument("--dur",type=float,default=0); ap.add_argument("--bars",type=int,default=2)
    ap.add_argument("--out",default="loop.mid"); ap.add_argument("--keep-kit",action="store_true")
    a=ap.parse_args()
    drop=set() if a.keep_kit else DROP_DEFAULT
    notes={**LANE_NOTE, **KIT_NOTE}

    wav=download(a.url,a.start,a.dur)
    y,sr=librosa.load(wav,sr=44100,mono=True)
    tempo,beats=librosa.beat.beat_track(y=y,sr=sr,units="time"); tempo=float(np.atleast_1d(tempo)[0])
    onsets=librosa.onset.onset_detect(y=y,sr=sr,units="time",backtrack=True)
    print(f"tempo ~{tempo:.1f} BPM, {len(beats)} beats, {len(onsets)} onsets")

    spb=60.0/tempo; step=spb/4; t0=beats[0] if len(beats) else 0.0
    grid={}
    for t in onsets:
        slot=round((t-t0)/step)
        if slot>=0: grid.setdefault(slot, profile(y,sr,t))
    spb_steps=16; total=a.bars*spb_steps

    lanes=["perc_low","perc_mid","perc_high","kick","snare"]
    print(f"\nGROOVE ({a.bars} bars). [kept] vs (dropped: {sorted(drop)}):")
    for ln in lanes:
        row="".join("X" if grid.get(s%spb_steps)==ln else "." for s in range(total))
        if "X" in row:
            tag="DROP" if ln in drop else "keep"
            print(f"  {ln:9} [{tag}] |{row}|")

    mid=mido.MidiFile(); tr=mido.MidiTrack(); mid.tracks.append(tr)
    tr.append(mido.MetaMessage("set_tempo",tempo=mido.bpm2tempo(tempo)))
    t16=mid.ticks_per_beat//4
    events=[(s*t16, notes[grid[s%spb_steps]]) for s in range(total)
            if (lab:=grid.get(s%spb_steps)) and lab not in drop and lab in notes]
    last=0
    for tk,note in events:
        tr.append(mido.Message("note_on",channel=9,note=note,velocity=110,time=tk-last))
        tr.append(mido.Message("note_off",channel=9,note=note,velocity=0,time=t16//2))
        last=tk+t16//2
    mid.save(a.out)
    print(f"\nwrote {a.out}  ({len(events)} percussion hits, {tempo:.1f} BPM, {a.bars} bars)")

if __name__=="__main__": main()
