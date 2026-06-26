---
name: make-loop
description: Generate a DTX-MULTI 12 percussion loop from a natural-language request (e.g. "sparse 6/8 bell-and-shaker at 87, leave room for my snare"). Runs the role-based groove producer, renders a stereo preview with the real M12 samples + full voice params, and exports MIDI + cat-0x10 params ready to push to the unit. Use whenever the user asks to make / generate / build a loop, groove, or pattern for the M12.
argument-hint: <natural-language loop request>
---

# make-loop — generate an M12 percussion loop

Turn the user's request into a loop using the role-based producer at
`loop-pipeline/role_producer.py`. Percussion only (the drummer plays kick/snare/hat live);
all voices route hard-right per `loops/loop_list.txt`. Keep the **counter** role unless told to
drop it, and always carry the **full cat-0x10 voice params** (tune/cutoff/res/atk/dec/rel/pan/
vol/sends) — they're applied in the render and exported for the M12.

GENRE = gospel / R&B. **Feel rules baked into the producer (do not undo):** the TIMEKEEPER is a
STEADY pulse (no negative space on it — the loop must have a findable pulse + clear meter);
SPACE/diversity lives in the OTHER roles (conga/counter/color) over that steady pulse; no
hammered downbeat accents; DEEP low pitch on beat 1 every *other* bar; HIGH maraca on beat 4;
backbeats carry a subtle WET clap (empty backbeats are NOT a goal); keep the counter.

## 1. Parse the request into producer args
- **bpm**: a number in the request; OR if a reference song is named (Way Maker, Gracefully
  Broken, Chasing After You, You Deserve It, Shout), use its BPM from `loops/loop_list.txt`;
  else default 80.
- **meter**: "6/8" → `68`, "9/8" → `98`, otherwise `44` (almost always).
- **space** (0..1, higher = more space/sparser): "sparse/minimal/leave room" → ~0.7,
  default ~0.5, "busy/full/driving" → ~0.3.
- **out name**: short slug from the request, e.g. `gospel_80_44`.

## 2. Run the producer
```
cd loop-pipeline && ../.venv/bin/python3 role_producer.py compose \
  --bpm <bpm> --meter <44|68|98> --space <0..1> --seed <N> \
  --backbeat <clap|snap|snaphi|rim|rimwet> --out <name>
```
`--backbeat` picks the backbeat timbre (snap/rim/etc. are crafted from samples + voice params —
tone design, not separate samples). Honor requests like "snap on the backbeat".
For options, run it 2-3× with different `--seed` and present the contrasting results.
THE PLAYER IS THE SCORING FUNCTION — after presenting, invite specific feedback (sparser?
conga talking more? deeper 1? more swing?) and regenerate.

## 3. Present the result
Show the ascii role grid and the voices/params table from the output. Then point the user to:
- **preview**: `loops/generated/<name>.wav` (stereo, real M12 voices + params applied — listen)
- **M12 MIDI**: `loops/generated/<name>.mid`
- **voice params**: `loops/generated/<name>.params.json` (the cat-0x10 settings)

## 4. Offer next steps
- **Push voice params to the M12** (if connected): set each role's voice params via
  `midi-tools/m12_control.py` (cat 0x10). The pattern itself goes to the unit via SMF import
  (thumb drive) or record-arm — note this; it can't be written as data over MIDI.
- **Audition on the unit**: stream the MIDI live to hear it on real M12 voices.
- **Make variations / a contrasting option**: re-run with a different `--seed` or `--feel`.

## When it gets complex
For "give me several ranked options", "iterate this against my reference / the unit", or
"design a whole song's worth of loops", hand off to the **groove-producer** subagent (it carries
the full M12 + percussion-grammar context and runs the generate→render→score→refine loop).
