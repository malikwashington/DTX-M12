#!/usr/bin/env python3
"""Decode a DTX-MULTI 12 SysEx bulk dump into its address-block map.

The block format is firmware-confirmed (transmit builder FUN_0c023106 @ RAM 0x0C0232xx,
model group 0x7F 0x0F):

    F0  43  0n  7F 0F  cH cL  aH aM aL  [data x count]  chk  F7
        43      = Yamaha
        0n      = bulk-dump block, n = device number
        7F 0F   = model / group
        cH cL   = data byte count, count = (cH << 7) | cL   (7 bits each)
        aH aM aL= 3-byte address (category, index, param)
        data    = count bytes, each 7-bit
        chk     = (-(sum of cH..last data)) & 0x7F

Also understands the companion messages so the producer can speak the full protocol:
    write : F0 43 1n 7F 0F aH aM aL dd F7        (single param, no checksum)
    read  : F0 43 2n 7F 0F aH aM aL F7           (dump request -> module replies w/ blocks)

Usage:
    m12_dump_decode.py <capture.syx>     # parse a raw .syx (concatenated F0..F7 msgs)
    m12_dump_decode.py --selftest        # build a block w/ the confirmed format, round-trip
"""
import sys, struct

YAMAHA = 0x43
MODEL  = (0x7F, 0x0F)


def checksum(payload):
    """payload = bytes from cH through the last data byte. Returns the 7-bit checksum."""
    return (-sum(payload)) & 0x7F


def split_sysex(buf):
    """Yield each F0..F7 message from a raw byte buffer."""
    i = 0
    while True:
        s = buf.find(0xF0, i)
        if s < 0:
            return
        e = buf.find(0xF7, s)
        if e < 0:
            return
        yield buf[s:e + 1]
        i = e + 1


def parse_block(msg):
    """Parse one F0..F7 message. Returns a dict describing it (type 'dump'/'write'/'read'/'other')."""
    if len(msg) < 5 or msg[1] != YAMAHA:
        return {"type": "other", "raw": msg}
    sub = msg[2] & 0xF0
    dev = msg[2] & 0x0F
    if (msg[3], msg[4]) != MODEL:
        return {"type": "other", "raw": msg, "dev": dev}
    body = msg[5:-1]  # between model and F7
    if sub == 0x00:  # bulk dump block
        cH, cL = body[0], body[1]
        count = (cH << 7) | cL
        aH, aM, aL = body[2], body[3], body[4]
        data = body[5:5 + count]
        chk_got = body[5 + count] if len(body) > 5 + count else None
        chk_calc = checksum(body[:5 + count])  # cH..last data
        return {"type": "dump", "dev": dev, "addr": (aH, aM, aL), "count": count,
                "data": bytes(data), "chk_ok": chk_got == chk_calc,
                "chk_got": chk_got, "chk_calc": chk_calc}
    if sub == 0x10:  # param write
        aH, aM, aL = body[0], body[1], body[2]
        return {"type": "write", "dev": dev, "addr": (aH, aM, aL), "value": body[3] if len(body) > 3 else None}
    if sub == 0x20:  # dump request
        aH, aM, aL = body[0], body[1], body[2]
        return {"type": "read", "dev": dev, "addr": (aH, aM, aL)}
    return {"type": "other", "raw": msg, "dev": dev}


def build_dump_block(addr, data, dev=0):
    """Construct a bulk-dump block exactly as the firmware does (for tests / emulation)."""
    aH, aM, aL = addr
    count = len(data)
    cH, cL = (count >> 7) & 0x7F, count & 0x7F
    body = bytes([cH, cL, aH, aM, aL]) + bytes(b & 0x7F for b in data)
    return bytes([0xF0, YAMAHA, 0x00 | (dev & 0x0F), MODEL[0], MODEL[1]]) + body + bytes([checksum(body), 0xF7])


def decode_capture(path):
    buf = open(path, "rb").read()
    blocks = [parse_block(m) for m in split_sysex(buf)]
    dumps = [b for b in blocks if b["type"] == "dump"]
    print(f"messages: {len(blocks)}   dump-blocks: {len(dumps)}")
    bad = [b for b in dumps if not b["chk_ok"]]
    if bad:
        print(f"  !! {len(bad)} blocks FAILED checksum")
    print("\naddress map (category, index, param) -> byte count:")
    by_cat = {}
    for b in dumps:
        aH, aM, aL = b["addr"]
        by_cat.setdefault(aH, []).append((aM, aL, b["count"]))
        print(f"  {aH:02X} {aM:02X} {aL:02X}   count={b['count']:<5} chk={'ok' if b['chk_ok'] else 'BAD'}")
    print("\nper-category summary:")
    for cat in sorted(by_cat):
        rows = by_cat[cat]
        total = sum(r[2] for r in rows)
        print(f"  category 0x{cat:02X}: {len(rows)} blocks, {total} data bytes")


def selftest():
    addr = (0x10, 0x08, 0x00)
    data = bytes(range(0, 26))           # a 26-byte voice block (cf. cat 0x10 live readback)
    blk = build_dump_block(addr, data, dev=0)
    print("built block:", blk.hex(" "))
    got = parse_block(next(split_sysex(blk)))
    assert got["type"] == "dump", got
    assert got["addr"] == addr, got
    assert got["count"] == len(data), got
    assert got["data"] == data, got
    assert got["chk_ok"], got
    # large block to exercise 2-byte count (count > 127)
    big = build_dump_block((0x00, 0x00, 0x00), bytes((i * 7) & 0x7F for i in range(300)))
    g2 = parse_block(next(split_sysex(big)))
    assert g2["count"] == 300 and g2["chk_ok"], g2
    # round-trip the documented checksum example shape
    print("selftest: PASS  (block build/parse, checksum, 7-bit count split all verified)")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    if sys.argv[1] == "--selftest":
        selftest()
    else:
        decode_capture(sys.argv[1])


if __name__ == "__main__":
    main()
