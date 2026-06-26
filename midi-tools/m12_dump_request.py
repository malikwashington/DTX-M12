#!/usr/bin/env python3
"""DTX-MULTI 12 — READ-ONLY bulk-dump requester.

This tool can ONLY send dump-REQUEST messages (`F0 43 2n 7F 0F aH aM aL F7`) and record
the module's reply. It has NO ability to write parameters: the sub-status nibble is hard-
wired to 0x20 and the message builder refuses anything else. A dump request does not alter
the M12 — it just asks the module to transmit its data.

Usage:
  m12_dump_request.py --ports                 list MIDI in/out ports
  m12_dump_request.py [--match M12] [--addr 00 00 00] [--secs 4] [--out dump.syx]
  m12_dump_request.py --sweep [--secs 2] [--out dump.syx]   request several categories

After capture, decode with:  m12_dump_decode.py <out.syx>
"""
import sys, time, argparse

YAMAHA, MODEL_H, MODEL_L = 0x43, 0x7F, 0x0F
DUMP_REQUEST = 0x20  # sub-status nibble; READ only. Never 0x10 (write).

# Categories worth requesting in --sweep (from the known address scheme / Data List).
SWEEP_ADDRS = [(0x00, 0x00, 0x00), (0x02, 0x00, 0x00), (0x10, 0x00, 0x00),
               (0x21, 0x00, 0x00), (0x30, 0x00, 0x00)]


def build_dump_request(addr, dev=0):
    """Build a READ-ONLY dump request. Refuses any non-read sub-status."""
    assert DUMP_REQUEST == 0x20, "this tool is read-only"
    aH, aM, aL = addr
    return [0xF0, YAMAHA, DUMP_REQUEST | (dev & 0x0F), MODEL_H, MODEL_L, aH, aM, aL, 0xF7]


def list_ports():
    import mido
    print("INPUTS:")
    for n in mido.get_input_names():
        print("  ", n)
    print("OUTPUTS:")
    for n in mido.get_output_names():
        print("  ", n)


def pick(names, match):
    if match:
        for n in names:
            if match.lower() in n.lower():
                return n
    for n in names:
        if any(k in n.lower() for k in ("dtx", "multi 12", "m12", "yamaha")):
            return n
    return names[0] if names else None


def capture(match, addrs, secs, out, quiet=False, settle=0.35):
    """Send each dump request; for each, collect bytes until a complete F0..F7 reply
    lands (then move on early) or `secs` elapses. Returns the captured bytes."""
    import mido
    inn = pick(mido.get_input_names(), match)
    outn = pick(mido.get_output_names(), match)
    if not inn or not outn:
        sys.exit("could not find M12 MIDI in/out ports (try --ports / --match)")
    if not quiet:
        print(f"in : {inn}\nout: {outn}")
    rx = bytearray()
    with mido.open_input(inn) as ip, mido.open_output(outn) as op:
        for addr in addrs:
            req = build_dump_request(addr)
            if not quiet:
                print("  -> request " + " ".join(f"{b:02X}" for b in req))
            op.send(mido.Message.from_bytes(req))
            t0, got, last = time.time(), False, time.time()
            while time.time() - t0 < secs:
                pend = False
                for msg in ip.iter_pending():
                    rx += bytes(msg.bytes()); pend = True
                    if bytes(msg.bytes())[-1:] == b'\xf7':
                        got = True
                if pend:
                    last = time.time()
                if got and time.time() - last > settle:
                    break
                time.sleep(0.005)
    if not rx:
        if not quiet:
            print("no reply captured (check cable / UTIL MIDI = USB / device number).")
        return rx
    open(out, "wb").write(bytes(rx))
    if not quiet:
        print(f"captured {len(rx)} bytes -> {out}\nnow run: m12_dump_decode.py {out}")
    return rx


def scan_categories(match, secs, out, hi=0x7F):
    """READ-ONLY category discovery: request (cat,0,0) for cat in 0..hi, keep replies."""
    addrs = [(c, 0x00, 0x00) for c in range(hi + 1)]
    rx = capture(match, addrs, secs, out, quiet=True, settle=0.18)
    print(f"scanned {hi + 1} categories -> {len(rx)} bytes captured -> {out}")
    print(f"decode with: m12_dump_decode.py {out}")


def enum_indices(match, cat, out, imax=0x7F):
    """READ-ONLY index enumeration: request (cat,idx,0) for idx in 0..imax."""
    addrs = [(cat, i, 0x00) for i in range(imax + 1)]
    rx = capture(match, addrs, min(1.0, 0.6), out, quiet=True, settle=0.15)
    print(f"enumerated cat 0x{cat:02X} idx 0..0x{imax:02X} -> {len(rx)} bytes -> {out}")
    print(f"decode with: m12_dump_decode.py {out}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ports", action="store_true")
    ap.add_argument("--match")
    ap.add_argument("--addr", nargs=3, type=lambda x: int(x, 16))
    ap.add_argument("--sweep", action="store_true")
    ap.add_argument("--scan", action="store_true", help="request (cat,0,0) for all categories 0..0x7F")
    ap.add_argument("--enum", type=lambda x: int(x, 16), help="enumerate (CAT,idx,0) for idx 0..0x7F")
    ap.add_argument("--secs", type=float, default=4.0)
    ap.add_argument("--out", default="dump.syx")
    a = ap.parse_args()
    if a.ports:
        list_ports(); return
    if a.scan:
        scan_categories(a.match, min(a.secs, 1.0), a.out); return
    if a.enum is not None:
        enum_indices(a.match, a.enum, a.out); return
    addrs = SWEEP_ADDRS if a.sweep else [tuple(a.addr) if a.addr else (0x00, 0x00, 0x00)]
    capture(a.match, addrs, a.secs, a.out)


if __name__ == "__main__":
    main()
