// FindSysex.java — locate the MIDI/SysEx parser + dispatch in the M12 firmware.
// Strategy: (1) find functions whose instructions use the scalar immediates that a
// Yamaha SysEx parser must touch (0xF0 start, 0x43 mfr id, 0x7F/0x0F model group);
// (2) memory-search for the bulk-dump template bytes 43 ?? 7F 0F; (3) force-disassemble
// the mystery table words to learn what they are.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class FindSysex extends GhidraScript {
    public void run() throws Exception {
        StringBuilder sb = new StringBuilder();
        Listing lst = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();

        // (1) For every function, collect the set of "interesting" byte immediates.
        long[] want = {0xF0, 0x43, 0x7F, 0x0F, 0x7E};
        Set<Long> wantSet = new HashSet<>();
        for (long w : want) wantSet.add(w);
        sb.append("=== functions touching SysEx constants (F0/43/7F/0F/7E) ===\n");
        FunctionIterator fit = fm.getFunctions(true);
        List<String> hits = new ArrayList<>();
        while (fit.hasNext()) {
            Function f = fit.next();
            Set<Long> found = new HashSet<>();
            InstructionIterator ii = lst.getInstructions(f.getBody(), true);
            while (ii.hasNext()) {
                Instruction ins = ii.next();
                for (int op = 0; op < ins.getNumOperands(); op++) {
                    for (Object o : ins.getOpObjects(op)) {
                        if (o instanceof Scalar) {
                            long v = ((Scalar) o).getUnsignedValue() & 0xFF;
                            if (wantSet.contains(v)) found.add(v);
                        }
                    }
                }
            }
            // require 0x43 AND (0xF0 or 0x7F) — the signature of a Yamaha SysEx handler
            if (found.contains(0x43L) && (found.contains(0xF0L) || found.contains(0x7FL))) {
                hits.add("  " + f.getName() + " @ " + f.getEntryPoint()
                         + "  consts=" + new TreeSet<>(found));
            }
        }
        for (String h : hits) sb.append(h).append("\n");
        sb.append("(" + hits.size() + " candidate functions)\n");

        // (2) Memory search for bulk-dump template: 43 ?? 7F 0F  (?? = any sub-status)
        sb.append("\n=== memory matches for '7F 0F' (model group) with 0x43 within 3 bytes ===\n");
        Memory mem = currentProgram.getMemory();
        byte[] needle = {(byte)0x7F, (byte)0x0F};
        Address a = currentProgram.getMinAddress();
        int shown = 0;
        while (a != null && shown < 40) {
            Address m = mem.findBytes(a, needle, null, true, new ConsoleTaskMonitor());
            if (m == null) break;
            // check a 0x43 in the 3 bytes before
            boolean near43 = false;
            try {
                for (int k = 1; k <= 3; k++)
                    if ((mem.getByte(m.subtract(k)) & 0xFF) == 0x43) near43 = true;
            } catch (Exception e) {}
            sb.append("  " + m + (near43 ? "  <-- 0x43 nearby (SysEx template?)" : "") + "\n");
            shown++;
            a = m.add(2);
        }

        // (3) Force-disassemble the mystery FX-table words.
        DecompInterface dec = new DecompInterface(); dec.openProgram(currentProgram);
        long[] mystery = {0x0C021712L, 0x0C02171AL, 0x0C0218C0L};
        sb.append("\n=== force-disassemble mystery table words ===\n");
        for (long x : mystery) {
            Address ad = toAddr(x);
            sb.append("\n-- 0x" + Long.toHexString(x) + " --\n");
            disassemble(ad);
            for (int i = 0; i < 8; i++) {
                Instruction ins = lst.getInstructionAt(ad);
                if (ins == null) { sb.append("  (no instruction)\n"); break; }
                sb.append("  " + ad + "  " + ins.toString() + "\n");
                ad = ins.getMaxAddress().add(1);
            }
        }

        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/sysex_dump.txt");
        pw.print(sb.toString()); pw.close();
        println("FindSysex wrote sysex_dump.txt (" + sb.length() + " chars)");
    }
}
