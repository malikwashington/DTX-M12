# Feature 6 & 7 firmware patch scoping — concrete sites (2026-06-23)

Image: `firmware/code.bin` (SH-2A BE, base 0x0C000000). **For code.bin, `file_off = RAM − 0x0C000000`**
(the `+0x80` in RE_LOG applies only to payload.bin/.PGM, NOT to code.bin — verified by byte match
at FUN_0c04898a). Ghidra project: `firmware/ghidra_proj_re/re_proj` (own project, main proj untouched).
Evidence dumps: `feature6.txt`, `feature7.txt`, `paddispatch.txt`, `hook.txt`, `trace.txt`,
`trace2.txt`, `final.txt`, `freespace.txt`.

---

## FUN_0c04898a invocation convention — RESOLVED (and it's a problem)

`FUN_0c04898a` (RAM 0x0C04898A, 344 B). Structure (decompile in feature6.txt):
- **Unconditional head**: a few `jsr @r12 / jsr @r10` calls using **inherited** r10/r11/r12/r13.
- **Main loop** `do{…}while(n<100)` stride **0xF1C** clearing 100 voice slots (the all-voices clear).
- **Flag-gated tails**, flag byte read from **stack offset +3** (`bld.b #k,@(0x3,r15)`):
  - `&4`  → calls **FUN_0c0440c6** (sequencer all-stop / transport reset). [the documented AllSoundOff bit]
  - `&0x40` → All-Note-Off broadcast loop (10×), via FUN_0c04088a.
  - `&0x80` → Reset-Controllers / channel reset.

**Calling convention (the deliverable):** FUN_0c04898a is **NOT a clean callable**. `final.txt`
confirms `CLEAN=false [unaff regs][stack params]`. It requires:
- **r10, r12** = function pointers (a "send MIDI byte" / "lookup" pair), inherited from the MIDI
  dispatch context — NOT set inside the function.
- **r11, r13** = base/context for the per-channel struct.
- the **behavior selector byte at stack[+3]** (`&4`/`&0x40`/`&0x80`).
- The 4 real call sites are the MIDI channel-message handlers **FUN_0c03d374 / 428 / 500 / 92a**,
  each reached by `bsr` from the MIDI status-byte dispatcher at **~0x0C03CD60** (jump table at
  0x0C03CDC8). Those handlers set r10/r11/r12/r13 = channel/note/velocity-derived values and the
  dispatcher passes r10→r4, r12→r5. i.e. r10/r12 are live MIDI context, not freshly set per call.
⇒ **A patch cannot safely `jsr FUN_0c04898a` from arbitrary code** (pad dispatcher) — the register
context won't be valid. Use a clean primitive instead (below).

### Clean, self-contained primitives usable by a patch (from final.txt):
| addr | name | size | role | CLEAN |
|------|------|------|------|-------|
| 0x0C0440C6 | FUN_0c0440c6 | 20 ins | **sequencer all-stop + transport reset** (the `&4` action) | ✅ |
| 0x0C043ED6 | FUN_0c043ed6 | 212 B | **pattern stop(0) / start(1)** primitive | ✅ |
| 0x0C043FFC | FUN_0c043ffc | 2 ins | pattern **START** (`PTR_FUN_0c044160(); FUN_0c043ed6(1);`) | ✅ |

---

## FEATURE 6 — stop-all-sounds on pad strike

### Pad-strike / pad-function dispatch
- The per-pad **"Pad Function" enum dispatch** is **FUN_0c0C9E60** (paddispatch.txt/hook.txt): a clean
  `switch(*(char*)(iVar3+3))` over function codes **0..0x10+** (1-per-pad-function), each arm calling
  a distinct handler `FUN_0c0Ca0xx`. `iVar3 = *(param_1+4)` is the pad's assignment record; the
  function code is at record+3 and its value at record+7. **This is the feature-6 hook point**: add a
  new function-code arm (or repurpose an unused one) that calls the stop primitive.
- The MIDI-out message senders (FUN_0c03d374 family) are the *transmit* path (pad→MIDI OUT), reached
  via the 0x0C03CD60 dispatcher. These already invoke FUN_0c04898a for channel-mode CCs — confirming
  FUN_0c04898a = the channel-mode/AllSoundOff handler, exactly as documented.

### Hook + patch plan (feature 6)
**Goal:** a specially-marked pad strike silences the M12's own voices.
1. **Hook site:** add an arm in **FUN_0c0C9E60** (the pad-function switch) for a chosen function code,
   OR redirect one existing arm. The switch is a clean compare-ladder, easy to extend.
2. **Action:** because FUN_0c04898a isn't safely callable, the trampoline should either:
   - (a) call **FUN_0c0440c6** (clean) to stop the sequencer/transport (silences pattern playback), and/or
   - (b) replicate FUN_0c04898a's **100-slot clear loop** (stride 0xF1C, the literal-pool pointers at
     0x0C048B7C/80/84/88/8C/90 give the per-slot clear/voice-off calls) to silence sustained voices.
     Pointers resolved (paddispatch.txt): clear fn FUN_0c069cf8, voice-off fn FUN_0c06a0f8, slot base
     DAT_0c048b80→0x0C69E7BC, etc.
3. **Trampoline space:** the code image tail **RAM 0x0C13E000 – 0x0C140000 (~8 KB)** is all-zero,
   unreferenced padding past the last function prologue (0x0C13D5D8). Multiple ≥1 KB free runs
   (freespace.txt: 0x0C13E1BB, 0x0C13E5CE, …, 0x0C13F9E2). Ideal, in-image trampoline area.
4. **Patch shape:** in the chosen FUN_0c0C9E60 arm, emit `mov.l #tramp,rN; jsr @rN; nop` (or a `bsr`
   if in range) to the tail trampoline; the trampoline does the stop/clear then `rts`.
- **Size:** hook ≈ 6–10 bytes in the switch + ~40–120 byte trampoline. **Risk: medium** — depends on
  re-using clean primitives (good) and on correctly identifying which pad-record field carries the
  function code (record+3, confidence medium-high; verify against a pad-function dump diff before flashing).
- **Confidence the dispatch is FUN_0c0C9E60: medium.** It is the cleanest 1..N pad-function switch in
  the image, but it was not traced end-to-end from the physical trigger ISR (RAM-table dispatch blocks
  static call-graph). A live diff (change one pad's Pad Function, re-dump cat 0x16/0x21) would confirm
  the record layout.

---

## FEATURE 7 — loop restart on 2nd strike — PINNED ✅

### The toggle (exact)
**FUN_0c044022** (RAM 0x0C044022) is the pad-pattern toggle (full disasm in trace2.txt). Reached from
the pad path **FUN_0c04f306 → FUN_0c044022** (final.txt). Logic:
```
0c044034  mov.w @(0xe,r14),r0     ; r0 = "is this pattern playing?" state (pattern obj r14 + 0xE)
0c044036  tst r0,r0
0c044038  bf  0x0c044074          ; 8b1c : if PLAYING (r0!=0) -> STOP block
          [0c04403a..0c044072]    ; NOT playing -> START: FUN_0c043ffc(...) + note-on
0c044074  bsr 0x0c043ed6          ; bf2f : STOP block
0c044076  _mov #0x0,r4            ; e400 : delay slot -> FUN_0c043ed6(0) == STOP
0c044078..0c044082                ; send transport msg via FUN_0c040dd0
0c044084  rts/n
```
- **Toggle branch = `0x0C044038` (`8b1c`, bf→0x0C044074).**
- **Stop call = `0x0C044074` (`bf2f` bsr FUN_0c043ed6) with `r4=0`.**
- "Is playing" check = `*(short*)(r14+0xE)` at `0x0C044034`.
- A **restart primitive already exists in two pieces**: STOP = `FUN_0c043ed6(0)`, START =
  `FUN_0c043ffc()` (which does init + `FUN_0c043ed6(1)`, i.e. start-from-beginning — `FUN_0c043ed6`
  param 1 re-reads pattern data from buffer base, so it seeks to start). So **restart = stop + start**,
  both already present and clean.

### Patch plan (feature 7)
**Goal:** re-strike while playing → STOP+seek0+START instead of STOP.
- **Minimal trampoline approach (recommended):** repoint the STOP block. At `0x0C044074`, instead of
  only `FUN_0c043ed6(0)` then exit, branch to a tail trampoline (0x0C13E000 region) that runs:
  `FUN_0c043ed6(0)` (stop) → then the same re-init+START sequence the NOT-playing arm uses
  (`FUN_0c043ffc` + note-on), → `rts`. Because START path already seeks to start, this yields restart.
- **Even simpler in-place option to test first:** change the toggle branch at `0x0C044038` from
  `bf 0x0c044074` (8b1c) to a `nop`/fall-through so a re-strike ALWAYS takes the START arm
  (0x0C04403A) — i.e. re-strike re-starts from top without an explicit stop. The START arm calls
  `FUN_0c043ffc` (which calls `FUN_0c043ed6(1)`), re-initializing playback. This is a **2-byte change**
  (8b1c→0009) and is the lowest-risk first experiment, though it skips an explicit stop (may double-
  trigger a note; verify on hardware/emulator).
- **Size:** 2 bytes (in-place fall-through) up to ~6 bytes hook + ~30–60 byte trampoline (clean restart).
- **Confidence: HIGH** for the toggle location and the stop/start primitives. **Risk: medium-low** —
  the branch and both primitives are clean and concrete.

---

## Free / trampoline space (freespace.txt)
- **Best: code-image tail `0x0C13E000 – 0x0C140000` (~8 KB)**, all 0x00, unreferenced, past the last
  prologue (0x0C13D5D8). Multiple ≥1 KB runs. In-image, so it's flashed with the patch.
- Smaller scattered zero/0xFF runs throughout 0x0C09xxxx–0x0C0Bxxxx (menu/label data gaps) if a
  trampoline must be near the hook for short-branch range.

## Honest gaps
- **FUN_0c04898a is not directly callable** from a patch (register-context dependent) — feature 6 must
  use the clean primitives (FUN_0c0440c6 / the replicated clear loop) instead. This is solid.
- **Pad-strike→dispatch end-to-end not traced through the trigger ISR** (RAM function-pointer tables
  populated at boot defeat static call-graph from the physical trigger). FUN_0c0C9E60 is the strongest
  pad-function switch but the exact "function-code" field offset in the pad record (rec+3) should be
  confirmed by a live cat-0x16/0x21 diff before committing a flash.
- All addresses are static/decompiler-derived; **emulate before flashing** (per FIRMWARE_PATCH_SCOPING.md).
