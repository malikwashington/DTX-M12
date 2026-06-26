// MidiRx.java — locate + characterize the MIDI Control-Change RECEIVE handler.
// Decompiles the MIDI status dispatcher (~0x0C03CD60), its jump table (0x0C03CDC8),
// the channel-mode CC handlers (FUN_0c04898a/FUN_0c048956), the realtime handler
// (FUN_0c044c08), and the transport-stop primitive (FUN_0c0440c6). Reports static
// callers of each (is the CC path statically reachable?).
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class MidiRx extends GhidraScript {
    StringBuilder sb = new StringBuilder();
    FunctionManager fm;
    ReferenceManager rm;
    DecompInterface dec;
    ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm = currentProgram.getFunctionManager();
        rm = currentProgram.getReferenceManager();
        dec = new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon = new ConsoleTaskMonitor();

        // 1) Dump the dispatcher jump table around 0x0C03CDC8 (status-byte -> handler).
        sb.append("=== jump table @0x0C03CDC8 (status-byte dispatch) ===\n");
        Listing lst = currentProgram.getListing();
        for (long a = 0x0C03CDC8L; a < 0x0C03CE30L; a += 4) {
            try {
                byte[] b = new byte[4];
                currentProgram.getMemory().getBytes(toAddr(a), b);
                long v = ((b[0]&0xFFL)<<24)|((b[1]&0xFFL)<<16)|((b[2]&0xFFL)<<8)|(b[3]&0xFFL);
                sb.append(String.format("  0x%08x: 0x%08x %s\n", a, v,
                    (v>=0x0C000000L&&v<0x0C140000L)?("-> "+funcName(v)):""));
            } catch (Exception e) { sb.append("  0x"+Long.toHexString(a)+": <err>\n"); }
        }

        // 2) Decompile + callers for the key functions.
        long[] tgts = {
            0x0C03CD60L,  // MIDI status-byte dispatcher
            0x0C044c08L,  // realtime handler (FA/FB/FC/F8)
            0x0C04898aL,  // channel-mode CC handler (interior)
            0x0C048956L,  // channel-mode CC handler (entry / MUTE_ENTRY)
            0x0C0440c6L,  // transport-stop primitive
        };
        for (long t : tgts) decompAndCallers(t);

        // 3) Walk the dispatcher's calls to find the Control-Change (Bn) arm.
        Function disp = fm.getFunctionContaining(toAddr(0x0C03CD60L));
        if (disp != null) {
            sb.append("\n=== dispatcher callees ===\n");
            for (Function c : disp.getCalledFunctions(mon))
                sb.append("  "+c.getEntryPoint()+" "+c.getName()+"\n");
        }

        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/midirx.txt");
        pw.print(sb.toString()); pw.close();
        println("MidiRx wrote midirx.txt ("+sb.length()+" chars)");
    }

    String funcName(long a){ Function f = fm.getFunctionContaining(toAddr(a)); return f!=null?f.getName()+"@"+f.getEntryPoint():"?"; }

    void decompAndCallers(long a) {
        Function f = fm.getFunctionContaining(toAddr(a));
        sb.append("\n\n########## 0x"+Long.toHexString(a)+" ");
        if (f==null) { sb.append("(NO FUNCTION) ##########\n"); return; }
        sb.append(f.getName()+" @ "+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ##########\n");
        // static callers (refs)
        sb.append("STATIC CALLERS:\n");
        int n=0;
        for (Reference r : rm.getReferencesTo(f.getEntryPoint())) {
            Function cf = fm.getFunctionContaining(r.getFromAddress());
            sb.append("  "+r.getFromAddress()+" "+r.getReferenceType()
                +(cf!=null?(" in "+cf.getName()+"@"+cf.getEntryPoint()):" (no func)")+"\n");
            n++;
        }
        if (n==0) sb.append("  (none)\n");
        DecompileResults r = dec.decompileFunction(f, 120, mon);
        if (r!=null && r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
        else sb.append("  <decompile failed>\n");
    }
}
