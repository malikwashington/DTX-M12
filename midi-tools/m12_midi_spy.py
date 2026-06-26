#!/usr/bin/env python3
"""DTX-MULTI 12 MIDI spy / bridge / emulator.

Reverse-engineer the (undocumented) editing protocol that the **DTXM12 Touch** iPad
app uses, by watching what it sends. Three modes:

  list                          # show all MIDI in/out ports
  monitor  <in-port-substr>     # log everything arriving on one port
  bridge   <appA> <devB>        # MITM: forward appA<->devB, log BOTH directions
  emulate  [name]               # create a virtual M12 port, answer Identity Request,
                                 # log everything the app sends (Mac pretends to be M12)

Yamaha SysEx is decoded: 43=Yamaha, command nibble after that = 0n bulk-dump,
1n param-change, 2n dump-request, 3n param-request; model 18 = DTX-MULTI 12.
The real M12 Identity Reply (used by emulate) is the one we captured live.
"""
import sys, time, mido

IDENTITY_REPLY = [0x7E,0x7F,0x06,0x02,0x43,0x00,0x41,0x3E,0x06,0x00,0x00,0x00,0x7F]
T0 = None
def ts():
    global T0
    t = time.monotonic()
    if T0 is None: T0 = t
    return f"{t-T0:8.3f}"

def decode_sysex(data):
    """data = bytes between F0 and F7 (mido gives msg.data as that)."""
    b = list(data)
    if not b: return "(empty)"
    if b[0] == 0x7E or b[0] == 0x7F:
        kind = "Universal " + ("NonRT" if b[0]==0x7E else "RT")
        return f"{kind}: {' '.join(f'{x:02X}' for x in b)}"
    if b[0] == 0x43:
        cmd = b[1] if len(b)>1 else 0
        hi, dev = cmd>>4, cmd&0x0F
        name = {0:"BULK-DUMP",1:"PARAM-CHANGE",2:"DUMP-REQUEST",3:"PARAM-REQUEST"}.get(hi,f"cmd{hi}")
        model = b[2] if len(b)>2 else None
        rest = b[3:]
        tag = f"Yamaha {name} dev={dev} model={model:02X}" if model is not None else f"Yamaha {name} dev={dev}"
        # bulk dump: [bc_hi bc_lo addr... data... checksum]
        body = ' '.join(f'{x:02X}' for x in rest)
        return f"{tag} | {body}"
    return "raw: " + ' '.join(f'{x:02X}' for x in b)

def fmt(msg, where):
    if msg.type == "sysex":
        return f"[{ts()}] {where:>6} SYSEX  {decode_sysex(msg.data)}"
    if msg.type in ("clock","active_sensing"): return None   # suppress chatter
    return f"[{ts()}] {where:>6} {msg}"

def log(msg, where):
    line = fmt(msg, where)
    if line:
        print(line, flush=True)
        if msg.type == "sysex":
            with open("midi_capture.log","a") as f:
                f.write(line+"\n")

def pick(substr, ports):
    m = [p for p in ports if substr.lower() in p.lower()]
    if not m: sys.exit(f"no port matching {substr!r} in {ports}")
    return m[0]

def main():
    if len(sys.argv) < 2: sys.exit(__doc__)
    cmd = sys.argv[1]
    ins, outs = mido.get_input_names(), mido.get_output_names()
    if cmd == "list":
        print("INPUTS:");  [print(f"  [{i}] {n}") for i,n in enumerate(ins)]
        print("OUTPUTS:"); [print(f"  [{i}] {n}") for i,n in enumerate(outs)]
        return
    if cmd == "monitor":
        port = pick(sys.argv[2], ins)
        print(f"monitoring {port!r}  (SysEx also -> midi_capture.log)")
        with mido.open_input(port) as ip:
            for msg in ip: log(msg, "IN")
    if cmd == "bridge":
        pa, pb = pick(sys.argv[2], ins), pick(sys.argv[3], ins)
        oa, ob = pick(sys.argv[2], outs), pick(sys.argv[3], outs)
        print(f"BRIDGE  A={pa!r}  <->  B={pb!r}   (logging both ways)")
        ia, ib = mido.open_input(pa), mido.open_input(pb)
        outA, outB = mido.open_output(oa), mido.open_output(ob)
        while True:
            for msg in ia.iter_pending(): log(msg,"A>B"); outB.send(msg)
            for msg in ib.iter_pending(): log(msg,"B>A"); outA.send(msg)
            time.sleep(0.0005)
    if cmd == "emulate":
        name = sys.argv[2] if len(sys.argv)>2 else "DTX-MULTI 12"
        print(f"EMULATE: virtual port {name!r} — answering Identity Request, logging all")
        vin = mido.open_input(name, virtual=True)
        vout = mido.open_output(name, virtual=True)
        for msg in vin:
            log(msg, "APP")
            if msg.type=="sysex" and list(msg.data)[:4]==[0x7E,0x7F,0x06,0x01] \
               or (msg.type=="sysex" and list(msg.data)[:3]==[0x7E,0x00,0x06]) \
               or (msg.type=="sysex" and len(msg.data)>=4 and msg.data[0]==0x7E and msg.data[2]==0x06 and msg.data[3]==0x01):
                vout.send(mido.Message("sysex", data=IDENTITY_REPLY))
                print(f"[{ts()}]   -> sent Identity Reply")

if __name__ == "__main__":
    main()
