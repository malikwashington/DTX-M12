// RelocTable.java — find the C-runtime relocation table (the _INITSCT-style table of
// (rom_src_start, rom_src_end_or_len, ram_dst) triples) in code.bin, and the early entry.
//  1) Scan code.bin data for runs of triples where word[0] and word[1] look like ROM addrs
//     (in 0x0C000000..0x0C140000) and word[2] looks like a RAM dest (0x0C200000..0x0C400000),
//     OR (src, dst, len) where len is a plausible small/medium size. Report the table.
//  2) Find the early init function: scan low addresses for a function that reads such a table and
//     loops (the relocator). Also report what the entry region looks like (BOOT jumps to app entry
//     — find the lowest real function and any that set up SP/SR).
//  3) Print the concrete objptr / vtable values used by the strike-dispatch sites so we know which
//     RAM region the relocation must cover. (We feed in the known indirect-call object offsets.)
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class RelocTable extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    boolean isRom(long v){ return v>=0x0C000000L && v<0x0C140000L; }
    boolean isRam(long v){ return v>=0x0C200000L && v<0x0C700000L; }

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // 1) scan for relocation-table triples. Two common layouts:
        //    (a) {src_start, src_end, dst}  (HEW/Renesas DTBL/BTBL style)
        //    (b) {src, dst, len}
        sb.append("################ relocation-table triple scan (src/dst/len) ################\n");
        long lo=0x0C000000L, hi=0x0C140000L;
        int found=0;
        for(long a=lo; a<hi-12; a+=4){
            long w0=u32(a), w1=u32(a+4), w2=u32(a+8);
            // layout (a): src_start(ROM) < src_end(ROM), dst(RAM)
            boolean a_ok = isRom(w0)&&isRom(w1)&&w1>w0&&(w1-w0)<0x80000 && isRam(w2);
            // layout (b): src(ROM), dst(RAM), len small-medium
            boolean b_ok = isRom(w0)&&isRam(w1)&&w2>0&&w2<0x80000;
            if(a_ok||b_ok){
                // require it be part of a RUN (>=2 consecutive triples) to avoid noise
                long n0=u32(a+12),n1=u32(a+16),n2=u32(a+20);
                boolean a_ok2 = isRom(n0)&&isRom(n1)&&n1>n0&&isRam(n2);
                boolean b_ok2 = isRom(n0)&&isRam(n1)&&n2>0&&n2<0x80000;
                if(a_ok2||b_ok2){
                    sb.append(String.format("  triple-run @0x%08x : [%08x %08x %08x][%08x %08x %08x] (%s)\n",
                              a,w0,w1,w2,n0,n1,n2, a_ok?"src,end,dst":"src,dst,len"));
                    found++;
                    if(found>40) break;
                }
            }
        }
        if(found==0) sb.append("  (no relocation-table triple runs found in code.bin)\n");

        // 2) early entry / relocator hunt: lowest functions + any reading a wide RAM region in a loop
        sb.append("\n################ lowest functions (entry/init region) ################\n");
        int c=0;
        for(Function f: iterable(fm.getFunctions(true))){
            if(f.getEntryPoint().getOffset()>=0x0C010000L) break;
            sb.append(String.format("  0x%08x size=%d callers=%d\n",f.getEntryPoint().getOffset(),
                      f.getBody().getNumAddresses(),f.getCallingFunctions(mon).size()));
            if(++c>30) break;
        }

        // 3) Is there a function that copies a LARGE block to RAM 0x0C2xxxxx/0x0C3xxxxx? Search all
        // funcs for a mov.l/mov.b store whose dest base literal is in the RAM data region, in a loop.
        sb.append("\n################ funcs writing to RAM data region 0x0C2C0000..0x0C320000 (vtable area?) ################\n");
        for(Function f: iterable(fm.getFunctions(true))){
            boolean writesRam=false; Set<Long> bases=new TreeSet<>();
            for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true))){
                for(Reference r: ins.getReferencesFrom()){
                    long t=r.getToAddress().getOffset();
                    if(t>=0x0C2C0000L && t<0x0C320000L && r.getReferenceType().isData()){ writesRam=true; bases.add(t); }
                }
            }
            if(writesRam && bases.size()>=2){
                sb.append(String.format("  0x%08x writes %d RAM-data targets size=%d\n",
                          f.getEntryPoint().getOffset(),bases.size(),f.getBody().getNumAddresses()));
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/reloctable.txt");
        pw.print(sb.toString()); pw.close();
        println("RelocTable wrote reloctable.txt ("+sb.length()+" chars)");
    }
    long u32(long a){ try{ return mem.getInt(toAddr(a))&0xFFFFFFFFL; }catch(Exception e){ return 0; } }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
