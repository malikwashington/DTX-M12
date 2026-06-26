#!/usr/bin/env python3
"""
dtxm12_probe.py - SysEx exploration tool for the Yamaha DTX-MULTI 12.

PURPOSE
  Determine whether the M12 answers MIDI parameter/dump requests, and if so,
  locate the SysEx address of the per-kit TEMPO. We already know the tempo lives
  in the saved-file format at byte offset 0x4DD4-0x4DD5 as a 14-bit big-endian
  value (bpm = MSB*128 + LSB), confirmed by diffing three All-Kit saves at
  100 / 150 / 200 bpm. The live SysEx *address* is a different coordinate system
  and is NOT derivable from the file - that's what this tool hunts for.

WHAT WE KNOW (constants, all confirmed)
  - Yamaha manufacturer ID      : 0x43
  - M12 model ID                : 0x18         (from the service manual)
  - Command grammar (byte after 0x43):
        0x1n  Parameter Change   host -> device   "set this"
        0x0n  Bulk Dump          device -> host   the data
        0x2n  Dump Request       host -> device   "send me this block"
        0x3n  Parameter Request  host -> device   "tell me this value"
        (n = device number 0-F; M12 device number = "all" so any n is accepted)
  - Reply to 0x2n arrives as 0x0n ; reply to 0x3n arrives as 0x1n.
  - Tempo encoding              : 14-bit, bpm = MSB*128 + LSB
  - Identity request (universal): F0 7E 7F 06 01 F7
        documented M12 reply    : F0 7E 7F 06 02 43 00 41 3A 06 00 00 00 7F F7

WHAT WE DO NOT KNOW (the open questions this tool answers)
  1. Does the M12 honor 0x3n / 0x2n requests at all? (It has NO front-panel bulk
     dump, which hints Yamaha de-emphasized MIDI dumps on this 2009 unit. Silence
     to every probe is itself a definitive answer.)
  2. The address length and layout. Older short-form Yamaha (this era) typically
     uses a 3-byte address, but the service manual's test command
     `F0 43 10 18 5A 00 F7` shows only 2 bytes after the model ID, so address
     width is genuinely uncertain. The tool is parameterized for this.

CONNECTION CHECKLIST (do before running)
  - Connect M12 "USB TO HOST" -> Mac directly (NOT through the iPad).
  - On the M12: UTIL6-9 MIDI IN/OUT = USB ; device number = all ; firmware >= 1.11.
  - pip install mido python-rtmidi

USAGE
  python dtxm12_probe.py ports                 # list MIDI ports, pick the M12
  python dtxm12_probe.py identity              # sanity check: known-good round trip
  python dtxm12_probe.py probe                 # fire a small battery of 2n/3n tests
  python dtxm12_probe.py scan --target 175     # set CHURCH to 175 first, then scan
"""

import sys
import time
import json
import select
import argparse

try:
    import mido
except ImportError:
    sys.exit("mido not installed. Run: pip install mido python-rtmidi")

YAMAHA = 0x43
M12_MODEL = 0x18


# ----------------------------------------------------------------------------- 
# Port handling
# -----------------------------------------------------------------------------
def list_ports():
    print("INPUT ports:")
    for i, n in enumerate(mido.get_input_names()):
        print(f"  [{i}] {n}")
    print("OUTPUT ports:")
    for i, n in enumerate(mido.get_output_names()):
        print(f"  [{i}] {n}")


def pick_port(names, substr):
    """Return the first port whose name contains substr (case-insensitive)."""
    if substr:
        for n in names:
            if substr.lower() in n.lower():
                return n
    return None


def open_ports(args):
    in_names, out_names = mido.get_input_names(), mido.get_output_names()
    inp = args.inport or pick_port(in_names, args.match)
    out = args.outport or pick_port(out_names, args.match)
    if not inp or not out:
        print("Could not auto-pick M12 ports. Available:")
        list_ports()
        sys.exit("Pass --inport / --outport explicitly, or set --match.")
    print(f"IN : {inp}")
    print(f"OUT: {out}")
    return mido.open_input(inp), mido.open_output(out)


# ----------------------------------------------------------------------------- 
# Send / listen primitives
# -----------------------------------------------------------------------------
def send_sysex(out, data_bytes):
    """data_bytes is the payload BETWEEN F0 and F7 (mido adds the framing)."""
    out.send(mido.Message("sysex", data=data_bytes))


def collect(inp, window_s):
    """Collect all sysex messages arriving within window_s seconds."""
    out = []
    t_end = time.time() + window_s
    while time.time() < t_end:
        for msg in inp.iter_pending():
            if msg.type == "sysex":
                out.append(list(msg.data))
        time.sleep(0.002)
    return out


def hexs(bs):
    return " ".join(f"{b:02X}" for b in bs)


# ----------------------------------------------------------------------------- 
# Message builders  (payloads are WITHOUT the F0/F7 framing)
# -----------------------------------------------------------------------------
def identity_request():
    return [0x7E, 0x7F, 0x06, 0x01]


def parameter_request(addr, dev=0):
    return [YAMAHA, 0x30 | dev, M12_MODEL] + list(addr)


def dump_request(addr, dev=0):
    return [YAMAHA, 0x20 | dev, M12_MODEL] + list(addr)


def parameter_change(addr, data, dev=0):
    """1n Parameter Change - the WRITE. F0 43 1n 18 <addr...> <data...> F7."""
    return [YAMAHA, 0x10 | dev, M12_MODEL] + list(addr) + list(data)


def parse_hex_bytes(s):
    """'20 00 01' or '0x20,0x00' -> [0x20, 0x00, 0x01]. Tolerates commas/0x."""
    if not s:
        return []
    toks = s.replace(",", " ").split()
    return [int(t, 16) if not t.lower().startswith("0x") else int(t, 0) for t in toks]


# -----------------------------------------------------------------------------
# Commands
# -----------------------------------------------------------------------------
def cmd_monitor(args):
    """Passively print everything the M12 transmits, with timestamps. Play pads,
    twist knobs, edit the panel - learn the unit's OUTPUT vocabulary safely.
    This is read-only: it never sends anything to the device."""
    inputs = [(n, mido.open_input(n)) for n in mido.get_input_names()
              if args.match.lower() in n.lower()]
    if not inputs:
        sys.exit(f"No input ports matched --match={args.match!r}.")
    print(f"Monitoring {len(inputs)} port(s). Play pads / turn knobs / edit panel.")
    print("Ctrl-C to stop.\n")
    t0 = time.time()
    seen_sysex = []
    try:
        while True:
            for name, inp in inputs:
                for msg in inp.iter_pending():
                    # Skip active-sensing/clock spam unless --all
                    if not args.all and msg.type in ("active_sensing", "clock"):
                        continue
                    dt = time.time() - t0
                    port = name.split()[-1] if name.split() else name
                    if msg.type == "sysex":
                        h = hexs(list(msg.data))
                        print(f"[{dt:7.3f}] P{port} SYSEX  {h}")
                        seen_sysex.append(h)
                    else:
                        print(f"[{dt:7.3f}] P{port} {msg}")
            time.sleep(0.002)
    except KeyboardInterrupt:
        print(f"\nStopped. Distinct SysEx messages captured: {len(set(seen_sysex))}")


# -----------------------------------------------------------------------------
# Guided capture - walk a checklist of controls, auto-label each one's output
# -----------------------------------------------------------------------------
DEFAULT_PADS = [f"Pad {i}" for i in range(1, 13)]
DEFAULT_EXTRAS = ["Kick / external trigger", "Hi-hat controller", "Footswitch"]

# Message types we treat as noise during a capture window.
_NOISE = ("active_sensing", "clock", "start", "stop", "continue", "reset")


def _msg_fields(msg):
    """Flatten a mido message into a JSON-friendly dict of the parts we map by."""
    d = {"type": msg.type}
    for attr in ("channel", "note", "velocity", "control", "value",
                 "program", "pitch"):
        if hasattr(msg, attr):
            d[attr] = getattr(msg, attr)
    if msg.type == "sysex":
        d["sysex"] = hexs(list(msg.data))
    return d


def _open_inputs(match):
    inputs = [(n, mido.open_input(n)) for n in mido.get_input_names()
              if match.lower() in n.lower()]
    if not inputs:
        sys.exit(f"No input ports matched --match={match!r}.")
    return inputs


def _drain(inputs, secs):
    """Discard everything arriving for `secs` seconds (e.g. trailing note-offs)."""
    t_end = time.time() + secs
    while time.time() < t_end:
        for _, inp in inputs:
            for _ in inp.iter_pending():
                pass
        time.sleep(0.002)


def _capture_one(inputs, label, asense):
    """Wait for ONE meaningful message for `label`. Returns a record dict, or
    None if the user pressed Enter to skip. Active-sensing is logged into
    `asense` the first 2 times (to document idle traffic) then ignored; other
    real-time noise is dropped silently."""
    print(f"  -> {label}: hit/turn it now  (or press Enter to skip)...",
          end="", flush=True)
    while True:
        for name, inp in inputs:
            for msg in inp.iter_pending():
                if msg.type == "active_sensing":
                    if len(asense) < 2:
                        asense.append({"port": name, **_msg_fields(msg)})
                    continue
                if msg.type in _NOISE:
                    continue
                # A note-off (or note_on velocity 0) is the *release* - skip it
                # and keep waiting for the actual strike.
                if msg.type == "note_off":
                    continue
                if msg.type == "note_on" and getattr(msg, "velocity", 0) == 0:
                    continue
                rec = {"label": label, "port": name, **_msg_fields(msg)}
                print(f"  got: {msg}")
                return rec
        # Non-blocking check for an Enter keypress = skip this control.
        r, _, _ = select.select([sys.stdin], [], [], 0.01)
        if r:
            sys.stdin.readline()
            print("  (skipped)")
            return None


def cmd_guided(args):
    """Interactive, self-labeling capture. Walks the 12 pads, then optional
    extras, then a freeform loop for any other control (knobs, buttons, pedals).
    Each prompt waits for you to operate ONE control and records what it emitted,
    so the output is a clean label->MIDI map instead of a raw firehose."""
    inputs = _open_inputs(args.match)
    print(f"Guided capture on {len(inputs)} port(s). Active Sense is documented "
          f"twice then ignored.\n")
    asense = []
    records = []

    def walk(items):
        for label in items:
            rec = _capture_one(inputs, label, asense)
            if rec is not None:
                records.append(rec)
            _drain(inputs, 0.35)  # swallow the trailing note-off / chatter

    try:
        print("== Main pads (Enter to skip any pad your kit doesn't use) ==")
        walk(DEFAULT_PADS)
        print("\n== External inputs ==")
        walk(DEFAULT_EXTRAS)
        print("\n== Freeform: any other control (knob, slider, button, pedal) ==")
        print("   Type a name + Enter, then operate it. Empty Enter = finish.")
        while True:
            label = input("  label> ").strip()
            if not label:
                break
            rec = _capture_one(inputs, label, asense)
            if rec is not None:
                records.append(rec)
            _drain(inputs, 0.35)
    except KeyboardInterrupt:
        print("\n(interrupted - saving what we have)")

    out = {"active_sensing_samples": asense, "captures": records}
    with open(args.out, "w") as f:
        json.dump(out, f, indent=2)

    print(f"\nSaved {len(records)} capture(s) to {args.out}")
    print(f"{'LABEL':<26} {'PORT':<8} {'TYPE':<13} DETAIL")
    print("-" * 70)
    for r in records:
        port = r["port"].split()[-1]
        if r["type"] in ("note_on", "note_off"):
            detail = f"note={r.get('note')} vel={r.get('velocity')} ch={r.get('channel')}"
        elif r["type"] == "control_change":
            detail = f"cc={r.get('control')} val={r.get('value')} ch={r.get('channel')}"
        elif r["type"] == "program_change":
            detail = f"program={r.get('program')} ch={r.get('channel')}"
        elif r["type"] == "sysex":
            detail = r.get("sysex", "")
        else:
            detail = json.dumps({k: v for k, v in r.items()
                                 if k not in ("label", "port", "type")})
        print(f"{r['label']:<26} P{port:<7} {r['type']:<13} {detail}")


def cmd_write(args):
    """Send ONE live Parameter Change (1n) write and listen briefly for any reply.

    The M12 ignores read requests but may honor writes (the service manual's
    `F0 43 10 18 5A 00 F7` is a 1n write). Verification is by EYE: watch the unit's
    screen for the parameter to change, since we cannot read it back over MIDI.

    Example:  write --addr "20 00" --data "01 2F"   (placeholder address!)
    """
    inp, out = open_ports(args)
    addr = parse_hex_bytes(args.addr)
    data = parse_hex_bytes(args.data)
    msg = parameter_change(addr, data, dev=args.dev)
    print(f"Sending Parameter Change:")
    print(f"  F0 {hexs(msg)} F7")
    print(f"  (addr={hexs(addr)}  data={hexs(data)}  dev={args.dev})")
    if args.repeat > 1:
        print(f"  repeating {args.repeat}x, {args.gap}s apart")
    for i in range(args.repeat):
        send_sysex(out, msg)
        time.sleep(args.gap)
    replies = collect(inp, 0.3)
    if replies:
        for r in replies:
            print("  REPLY:", hexs(r))
    else:
        print("  (no reply - expected; writes are silent. Check the unit's screen.)")
def cmd_identity(args):
    inp, out = open_ports(args)
    send_sysex(out, identity_request())
    replies = collect(inp, 0.5)
    if not replies:
        print("No reply. Check cabling, MIDI IN/OUT=USB, and that IN/OUT ports are the M12.")
        return
    for r in replies:
        print("REPLY:", hexs(r))
        if r[:4] == [0x7E, 0x7F, 0x06, 0x02]:
            print("  -> Identity Reply. Family/code bytes:", hexs(r[4:]))
            print("  -> Pipe confirmed. The M12 sends and you receive. Good to probe.")


def cmd_probe(args):
    """Fire a small, honest battery of requests to learn whether 2n/3n work
    and what a reply looks like. Logs everything."""
    inp, out = open_ports(args)

    print("\n[1] Identity (known-good baseline)")
    send_sysex(out, identity_request())
    for r in collect(inp, 0.4):
        print("    REPLY:", hexs(r))

    # Try parameter requests at a few plausible low addresses, across 2- and
    # 3-byte address widths, since width is unconfirmed.
    candidates = []
    for width in (3, 2):
        for hi in (0x00, 0x10, 0x20, 0x21):   # 0x2x is "system/utility" on many Yamaha maps
            lows = [(hi, m, 0) for m in (0x00, 0x01, 0x10)] if width == 3 else [(hi, 0)]
            for addr in lows:
                candidates.append(addr)

    print("\n[2] Parameter requests (3n) - looking for ANY 1n reply")
    got_any = False
    for addr in candidates:
        send_sysex(out, parameter_request(addr))
        for r in collect(inp, 0.12):
            got_any = True
            print(f"    addr {hexs(addr)} -> REPLY: {hexs(r)}")
    if not got_any:
        print("    (silent to all parameter requests)")

    print("\n[3] Dump requests (2n) - looking for ANY 0n reply")
    got_any = False
    for addr in candidates:
        send_sysex(out, dump_request(addr))
        for r in collect(inp, 0.15):
            got_any = True
            print(f"    addr {hexs(addr)} -> REPLY: {hexs(r)}")
    if not got_any:
        print("    (silent to all dump requests)")

    print("\nIf both 2n and 3n were silent, the M12 most likely does not service "
          "MIDI requests - that's the definitive answer, and the file-edit route "
          "is the way. If anything replied, capture the exact bytes and move to scan.")


def decode_tempo_echo(reply, target):
    """Return the byte index of any adjacent (MSB,LSB) pair decoding to target."""
    for i in range(len(reply) - 1):
        if reply[i] * 128 + reply[i + 1] == target:
            return i
    return None


def cmd_scan(args):
    """Parameter-request address scan with a tempo-echo detector.

    PRECONDITION: set CHURCH's kit tempo to --target on the unit first, so the
    parameter whose value equals --target is unambiguous. A 3-byte space is far
    too large to sweep fully (~2M * delay), so this sweeps a CONFIGURABLE window
    - keep it tight and widen only around regions that reply.
    """
    inp, out = open_ports(args)
    target = args.target
    print(f"Scanning for the address whose value decodes to {target} bpm "
          f"(MSB*128+LSB). Set CHURCH to {target} on the unit first.\n")

    hi_lo, hi_hi = args.hi
    mid_lo, mid_hi = args.mid
    low_lo, low_hi = args.low
    hits = []
    tested = 0
    for hi in range(hi_lo, hi_hi + 1):
        for mid in range(mid_lo, mid_hi + 1):
            for low in range(low_lo, low_hi + 1):
                addr = (hi, mid, low)
                send_sysex(out, parameter_request(addr))
                tested += 1
                for r in collect(inp, args.delay):
                    idx = decode_tempo_echo(r, target)
                    tag = "  <== TEMPO MATCH" if idx is not None else ""
                    print(f"addr {hexs(addr)} -> {hexs(r)}{tag}")
                    if idx is not None:
                        hits.append((addr, idx, r))
    print(f"\nTested {tested} addresses. Tempo matches: {len(hits)}")
    for addr, idx, r in hits:
        print(f"  TEMPO at request-addr {hexs(addr)}, value bytes at reply index "
              f"{idx}: {r[idx]:02X} {r[idx+1]:02X}")
    if hits:
        print("\nNext: confirm by changing the unit's tempo and re-requesting that "
              "address - the reply should track. Then build the matching 1n "
              "Parameter Change write:  F0 43 10 18 <addr> <MSB> <LSB> F7")


# -----------------------------------------------------------------------------
# Automated end-to-end run
# -----------------------------------------------------------------------------
def open_all(args):
    """Open every input and output port matching --match (or all three M12 ports).

    The M12 exposes 3 USB MIDI ports and we do NOT know a priori which one carries
    SysEx, so automation opens them all and lets the device tell us by replying.
    Returns (inputs, outputs) as lists of (name, port).
    """
    in_names = [n for n in mido.get_input_names() if args.match.lower() in n.lower()]
    out_names = [n for n in mido.get_output_names() if args.match.lower() in n.lower()]
    if not in_names or not out_names:
        print(f"No ports matched --match={args.match!r}. Available:")
        list_ports()
        sys.exit("Adjust --match (e.g. 'DTX' or 'YAMAHA') or pass explicit ports.")
    inputs = [(n, mido.open_input(n)) for n in in_names]
    outputs = [(n, mido.open_output(n)) for n in out_names]
    return inputs, outputs


def collect_all(inputs, window_s):
    """Collect (in_name, sysex_payload) tuples from ALL input ports within window."""
    got = []
    t_end = time.time() + window_s
    while time.time() < t_end:
        for name, inp in inputs:
            for msg in inp.iter_pending():
                if msg.type == "sysex":
                    got.append((name, list(msg.data)))
        time.sleep(0.002)
    return got


def is_identity_reply(payload):
    return payload[:4] == [0x7E, 0x7F, 0x06, 0x02]


def cmd_auto(args):
    """Run the full hunt unattended: find the live SysEx port, learn whether the
    device services 2n/3n requests, and (if a --target tempo is given and 3n is
    alive) scan for the tempo address - all in one go. Prints a definitive verdict."""
    inputs, outputs = open_all(args)
    print(f"Opened {len(inputs)} input / {len(outputs)} output port(s) matching "
          f"{args.match!r}.\n")

    # --- Phase A: passive liveness. Does the unit transmit anything on its own? ---
    print("[A] Passive listen (0.6s) - is the unit transmitting at all?")
    any_in = []
    t_end = time.time() + 0.6
    while time.time() < t_end:
        for name, inp in inputs:
            for msg in inp.iter_pending():
                any_in.append((name, msg.type))
        time.sleep(0.002)
    if any_in:
        kinds = {}
        for _, t in any_in:
            kinds[t] = kinds.get(t, 0) + 1
        print(f"    Traffic seen: {kinds}  (unit is alive on the bus)")
    else:
        print("    Silent (normal - the M12 idles quietly; not a problem).")

    # --- Phase B: identity on every output, listening on every input ---
    print("\n[B] Identity probe across all ports (which pair is the SysEx pipe?)")
    live_pairs = []
    for oname, out in outputs:
        send_sysex(out, identity_request())
        for iname, payload in collect_all(inputs, 0.4):
            tag = "  <== IDENTITY" if is_identity_reply(payload) else ""
            print(f"    OUT[{oname}] -> IN[{iname}]: {hexs(payload)}{tag}")
            if is_identity_reply(payload):
                live_pairs.append((oname, out, iname))
    # de-dup on (out_name, in_name)
    seen = set()
    uniq = []
    for oname, out, iname in live_pairs:
        if (oname, iname) not in seen:
            seen.add((oname, iname))
            uniq.append((oname, out, iname))
    live_pairs = uniq

    if not live_pairs:
        print("\n    No identity reply on ANY port.")
        print("    VERDICT: the SysEx pipe is not open. This is almost certainly a")
        print("    settings/cabling issue, NOT a final answer about tempo. Check on")
        print("    the unit: UTIL6-9 MIDI IN/OUT = USB, device number = all, fw >= 1.11,")
        print("    and that USB TO HOST goes straight to this Mac. Then re-run.")
        return

    oname, out, iname = live_pairs[0]
    inp_for_listen = [(n, p) for (n, p) in inputs if n == iname] or inputs
    print(f"\n    Pipe confirmed: send on OUT[{oname}], reply on IN[{iname}].")
    if len(live_pairs) > 1:
        print(f"    ({len(live_pairs)} pairs answered; using the first.)")

    # --- Phase C: does it honor 3n (param request) and 2n (dump request)? ---
    candidates = []
    for width in (3, 2):
        for hi in (0x00, 0x10, 0x20, 0x21):
            lows = [(hi, m, 0) for m in (0x00, 0x01, 0x10)] if width == 3 else [(hi, 0)]
            candidates.extend(lows)

    print("\n[C] Parameter requests (3n) - looking for any 1n reply")
    p3_replies = []
    for addr in candidates:
        send_sysex(out, parameter_request(addr))
        for _, payload in collect_all(inp_for_listen, 0.12):
            p3_replies.append((addr, payload))
            print(f"    addr {hexs(addr)} -> {hexs(payload)}")
    if not p3_replies:
        print("    (silent to all parameter requests)")

    print("\n[D] Dump requests (2n) - looking for any 0n reply")
    p2_replies = []
    for addr in candidates:
        send_sysex(out, dump_request(addr))
        for _, payload in collect_all(inp_for_listen, 0.15):
            p2_replies.append((addr, payload))
            print(f"    addr {hexs(addr)} -> {hexs(payload)}")
    if not p2_replies:
        print("    (silent to all dump requests)")

    # --- Phase E: verdict, and auto-scan if 3n is alive and we have a target ---
    print("\n" + "=" * 70)
    if not p3_replies and not p2_replies:
        print("VERDICT: identity works, but the M12 ignores every 2n/3n request.")
        print("This is the expected outcome for this unit - it does not service MIDI")
        print("parameter/dump requests, so there is no live tempo address to write.")
        print("The file-edit route (offset 0x4DD4/0x4DD5) remains the way. Done.")
        return

    print("VERDICT: the M12 DID answer requests - the SysEx route is alive.")
    if p3_replies:
        print(f"  {len(p3_replies)} parameter (3n) reply/replies captured above.")
    if p2_replies:
        print(f"  {len(p2_replies)} dump (2n) reply/replies captured above.")

    if p3_replies and args.target:
        print(f"\n[E] 3n is alive - auto-scanning for the tempo address "
              f"(target {args.target} bpm).")
        print("    PRECONDITION: CHURCH must be set to "
              f"{args.target} bpm on the unit right now.")
        hi_lo, hi_hi = args.hi
        mid_lo, mid_hi = args.mid
        low_lo, low_hi = args.low
        hits, tested = [], 0
        for hi in range(hi_lo, hi_hi + 1):
            for mid in range(mid_lo, mid_hi + 1):
                for low in range(low_lo, low_hi + 1):
                    addr = (hi, mid, low)
                    send_sysex(out, parameter_request(addr))
                    tested += 1
                    for _, payload in collect_all(inp_for_listen, args.delay):
                        idx = decode_tempo_echo(payload, args.target)
                        tag = "  <== TEMPO MATCH" if idx is not None else ""
                        if idx is not None or args.verbose:
                            print(f"    addr {hexs(addr)} -> {hexs(payload)}{tag}")
                        if idx is not None:
                            hits.append((addr, idx, payload))
        print(f"    Tested {tested} addresses. Tempo matches: {len(hits)}")
        for addr, idx, payload in hits:
            print(f"      TEMPO at request-addr {hexs(addr)}, value bytes at index "
                  f"{idx}: {payload[idx]:02X} {payload[idx+1]:02X}")
            print(f"      Write it with:  F0 43 10 18 {hexs(addr)} <MSB> <LSB> F7")
    elif p3_replies:
        print("\n  3n is alive but no --target given, so the tempo scan was skipped.")
        print("  Set CHURCH to a distinctive bpm (e.g. 175) and re-run with")
        print("  --target 175 to auto-locate the tempo address.")


def main():
    p = argparse.ArgumentParser(description="DTX-MULTI 12 SysEx probe")
    p.add_argument("--inport", help="exact MIDI input port name")
    p.add_argument("--outport", help="exact MIDI output port name")
    p.add_argument("--match", default="DTX", help="substring to auto-match the M12 port")
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("ports", help="list MIDI ports")
    sub.add_parser("identity", help="known-good identity round trip")
    sub.add_parser("probe", help="battery of 2n/3n tests")

    def add_scan_args(sp, target_default):
        sp.add_argument("--target", type=int, default=target_default,
                        help="tempo set on the unit (0 = skip scan)")
        sp.add_argument("--delay", type=float, default=0.06, help="seconds to wait per reply")
        sp.add_argument("--verbose", action="store_true", help="print every reply during scan")
        sp.add_argument("--hi", type=lambda x: tuple(int(v, 0) for v in x.split(":")),
                        default=(0x00, 0x21), help="hi-byte range lo:hi (e.g. 0x00:0x21)")
        sp.add_argument("--mid", type=lambda x: tuple(int(v, 0) for v in x.split(":")),
                        default=(0x00, 0x10), help="mid-byte range lo:hi")
        sp.add_argument("--low", type=lambda x: tuple(int(v, 0) for v in x.split(":")),
                        default=(0x00, 0x7F), help="low-byte range lo:hi")

    add_scan_args(sub.add_parser("scan", help="parameter-request address scan for tempo"),
                  target_default=175)
    add_scan_args(sub.add_parser("auto", help="fully automated: find pipe, probe, "
                                              "verdict, and scan if --target given"),
                  target_default=0)

    m = sub.add_parser("monitor", help="passively print everything the M12 transmits")
    m.add_argument("--all", action="store_true", help="include active-sensing/clock")

    g = sub.add_parser("guided", help="interactive, self-labeling control capture")
    g.add_argument("--out", default="guided-capture.json",
                   help="output JSON path (default guided-capture.json)")

    w = sub.add_parser("write", help="send a live 1n Parameter Change write")
    w.add_argument("--addr", required=True, help='hex address bytes, e.g. "20 00"')
    w.add_argument("--data", default="", help='hex data bytes, e.g. "01 2F"')
    w.add_argument("--dev", type=int, default=0, help="device-number nibble (0-15)")
    w.add_argument("--repeat", type=int, default=1, help="send N times")
    w.add_argument("--gap", type=float, default=0.05, help="seconds between repeats")

    args = p.parse_args()
    if args.cmd == "ports":
        list_ports()
    elif args.cmd == "identity":
        cmd_identity(args)
    elif args.cmd == "probe":
        cmd_probe(args)
    elif args.cmd == "scan":
        cmd_scan(args)
    elif args.cmd == "auto":
        cmd_auto(args)
    elif args.cmd == "monitor":
        cmd_monitor(args)
    elif args.cmd == "guided":
        cmd_guided(args)
    elif args.cmd == "write":
        cmd_write(args)


if __name__ == "__main__":
    main()
