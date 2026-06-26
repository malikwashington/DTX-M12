#!/usr/bin/env python3
"""DTX-MULTI 12 control library — read & write the unit live over USB MIDI.

Protocol (reverse-engineered + verified, see M12_PARAM_MAP.md):
  param write : F0 43 1n 7F 0F  aH aM aL  dd  F7        (n=device#, no checksum)
  dump request: F0 43 2n 7F 0F  aH aM aL  F7            -> bulk dump reply
  bulk block  : F0 43 0n 7F 0F  cH cL  aH aM aL [data] chk F7
                chk = (-(sum of count+addr+data)) & 0x7F

Address = (category, index, offset). Category 0x10 = per-pad voice edit buffer;
index = internal pad index (see PAD_INDEX); the 26-byte block holds the pad's
core voice params at the offsets in PAD_OFF.

Usage:
  m = M12()
  rec = m.read_pad(6)                 # -> dict of bytes for app-pad 6
  m.set_pad(6, volume=80, cutoff=100, pan=64)
  m.set_voice(6, category=4, number=41)   # Hi-Hat 041
"""
import mido, time

DEV = 0                      # device number (UTIL6-12); 0 seen in capture
MODEL = [0x7F, 0x0F]

# category 0x10 per-pad block offsets (confirmed by live capture + readback)
PAD_OFF = {
    "voice_category": 0x01,   # 0x04=Hi-Hat, 0x07=Orchestral/Misc, ...
    "voice_number":   0x02,   # 1..N within category
    "volume":         0x03,   # 0..127
    "pan":            0x04,   # 0..127, 0x40=center
    "reverb_send":    0x05,   # 0..127
    "chorus_send":    0x06,   # 0..127
    "variation_send": 0x07,   # 0..127
    "tune":           0x08,   # coarse, 0x40=center(approx)
    "cutoff":         0x0C,   # 0..127, 0x7F=open
    "resonance":      0x0D,   # 0..127
    "attack":         0x0E,   # 0..127
    "decay":          0x0F,   # 0..127
    "release":        0x10,   # 0..127
}
# app pad number -> internal index (mid byte). NON-LINEAR; fill in as mapped.
PAD_INDEX = {1: 0x0C, 6: 0x08}

def _port(sub, names):
    m = [n for n in names if sub in n]
    if not m: raise RuntimeError(f"no MIDI port matching {sub!r} in {names}")
    return m[0]

class M12:
    def __init__(self, match="DTX-MULTI 12 Port 1", dev=DEV):
        self.dev = dev
        self.out = mido.open_output(_port(match, mido.get_output_names()))
        self.inp = mido.open_input(_port(match, mido.get_input_names()))

    # --- low level ---
    def param(self, cat, idx, off, val):
        """Write one parameter (1n)."""
        self.out.send(mido.Message("sysex",
            data=[0x43, 0x10 | self.dev, *MODEL, cat, idx, off, val & 0x7F]))
        time.sleep(0.01)

    def _drain(self):
        for _ in self.inp.iter_pending(): pass

    def dump(self, cat, idx, off, timeout=0.3):
        """Send dump request (2n), return (addr, data-bytes) or (None,None)."""
        self._drain()
        self.out.send(mido.Message("sysex", data=[0x43, 0x20 | self.dev, *MODEL, cat, idx, off]))
        t0 = time.monotonic()
        while time.monotonic() - t0 < timeout:
            for m in self.inp.iter_pending():
                if m.type == "sysex":
                    d = list(m.data)
                    if len(d) >= 9 and d[0] == 0x43:
                        cnt = (d[4] << 7) | d[5]
                        return (d[6], d[7], d[8]), d[9:9+cnt]
            time.sleep(0.005)
        return None, None

    @staticmethod
    def _chk(payload):
        return (-sum(payload)) & 0x7F

    def write_block(self, cat, idx, off, data):
        """Write a whole block via bulk dump (0n) with checksum."""
        n = len(data)
        body = [(n >> 7) & 0x7F, n & 0x7F, cat, idx, off, *data]
        self.out.send(mido.Message("sysex",
            data=[0x43, 0x00 | self.dev, *MODEL, *body, self._chk(body)]))
        time.sleep(0.02)

    # --- high level ---
    def _idx(self, pad):
        if pad not in PAD_INDEX:
            raise KeyError(f"pad {pad} index unknown; add to PAD_INDEX")
        return PAD_INDEX[pad]

    def read_pad(self, pad):
        addr, data = self.dump(0x10, self._idx(pad), 0x00)
        if data is None: return None
        return {name: data[o] for name, o in PAD_OFF.items()} | {"_raw": data}

    def set_pad(self, pad, **params):
        idx = self._idx(pad)
        for name, val in params.items():
            if name not in PAD_OFF: raise KeyError(name)
            self.param(0x10, idx, PAD_OFF[name], val)

    def set_voice(self, pad, category, number):
        idx = self._idx(pad)
        self.param(0x10, idx, PAD_OFF["voice_category"], category)
        self.param(0x10, idx, PAD_OFF["voice_number"], number)

    def close(self):
        self.out.close(); self.inp.close()


if __name__ == "__main__":
    m = M12()
    print("Pad 6 current:", m.read_pad(6))
    m.close()
