// Feature6.java — pin code sites for FEATURE 6 (stop-all-sounds on pad strike) and gather
// data for FEATURE 7 (loop restart). Produces:
//   A) decompile of FUN_0c04898a (the mute-all routine) + its raw disasm of the &4 path
//   B) all callers of FUN_0c04898a, decompiled, to learn the invocation convention
//   C) candidate pad-strike dispatcher functions (note-on / trigger -> voice play) and the
//      per-pad "function"/"message type" dispatch (switch on a small index)
//   D) free/padding space scan (runs of >=0x20 zero bytes that are unreferenced)
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class Feature6 extends GhidraScript {
    StringBuilder sb = new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm = currentProgram.getFunctionManager(); lst = currentProgram.getListing();
        rm = currentProgram.getReferenceManager(); mem = currentProgram.getMemory();
        dec = new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon = new ConsoleTaskMonitor();

        long MUTE = 0x0C04898AL;

        // ---- A) the mute routine itself ----
        sb.append("################ A) FUN_0c04898a (mute-all) ################\n");
        decompAndDisasm(MUTE);

        // ---- B) callers of the mute routine ----
        sb.append("\n\n################ B) CALLERS of 0x0c04898a ################\n");
        Set<Long> callerEntries = new LinkedHashSet<>();
        for (Reference r : rm.getReferencesTo(toAddr(MUTE))) {
            Function f = fm.getFunctionContaining(r.getFromAddress());
            sb.append("  ref " + r.getFromAddress() + " " + r.getReferenceType()
                      + (f!=null?(" in "+f.getName()+"@"+f.getEntryPoint()):" (no func)") + "\n");
            if (f!=null) callerEntries.add(f.getEntryPoint().getOffset());
        }
        sb.append("(" + callerEntries.size() + " distinct callers)\n");
        for (long a : callerEntries) {
            sb.append("\n===== CALLER @0x"+Long.toHexString(a)+" =====\n");
            // show the few instructions around each call site (register setup before jsr)
            for (Reference r : rm.getReferencesTo(toAddr(MUTE))) {
                Function f = fm.getFunctionContaining(r.getFromAddress());
                if (f!=null && f.getEntryPoint().getOffset()==a) {
                    sb.append("  --- call-site context @"+r.getFromAddress()+" ---\n");
                    disasmWindow(r.getFromAddress().getOffset()-0x28, r.getFromAddress().getOffset()+0x6);
                }
            }
            decompOnly(a);
        }

        // ---- C) pad-strike dispatcher candidates ----
        // Heuristic: the pad function dispatch is a switch on a small per-pad code. Look for
        // functions that (a) read a per-pad struct and (b) call the voice-play primitive or
        // FUN_0c04898a-family. We surface functions that reference the pad-function strings and
        // functions with a dense small-immediate compare ladder.
        sb.append("\n\n################ C) pad-strike / pad-function dispatch candidates ################\n");
        String[] padKeys = {"Pad","pad","Trigger","trigger","Func","MsgType","Message","Note","Stack","Alter","Hold"};
        sb.append("--- strings hinting pad function/message + referencing funcs ---\n");
        int shown=0;
        for (Data d : iterable(lst.getDefinedData(true))) {
            if (shown>60) break;
            Object v = d.getValue();
            if (!(v instanceof String)) continue;
            String s=(String)v;
            boolean hit=false; for (String k:padKeys) if (s.contains(k)) {hit=true;break;}
            if (!hit || s.length()<3 || s.length()>28) continue;
            StringBuilder refs=new StringBuilder();
            for (Reference r: rm.getReferencesTo(d.getAddress())){
                Function f=fm.getFunctionContaining(r.getFromAddress());
                if(f!=null) refs.append(" "+f.getName()+"@"+f.getEntryPoint());
            }
            if(refs.length()>0){ sb.append("  \""+s+"\" @"+d.getAddress()+" <-"+refs+"\n"); shown++; }
        }

        PrintWriter pw = new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/feature6.txt");
        pw.print(sb.toString()); pw.close();
        println("Feature6 wrote feature6.txt (" + sb.length() + " chars)");
    }

    void decompAndDisasm(long a) throws Exception {
        Function f = fm.getFunctionContaining(toAddr(a));
        if (f==null){ sb.append("  no func @0x"+Long.toHexString(a)+"\n"); return; }
        sb.append("\n## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+"\n");
        sb.append("callees:"); for(Function c:f.getCalledFunctions(mon)) sb.append(" "+c.getEntryPoint()); sb.append("\n");
        sb.append("callers:"); for(Function c:f.getCallingFunctions(mon)) sb.append(" "+c.getEntryPoint()); sb.append("\n");
        sb.append("--- DISASM ---\n");
        for (Instruction ins : iterable(lst.getInstructions(f.getBody(), true))) {
            Reference[] rf = ins.getReferencesFrom();
            StringBuilder rs=new StringBuilder();
            for(Reference r:rf) rs.append(" ->"+r.getToAddress());
            sb.append("  "+ins.getAddress()+"  "+ins.toString()+rs+"\n");
        }
        sb.append("--- DECOMP ---\n");
        DecompileResults r=dec.decompileFunction(f,120,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
    }

    void decompOnly(long a) throws Exception {
        Function f = fm.getFunctionContaining(toAddr(a));
        if (f==null){ sb.append("  no func @0x"+Long.toHexString(a)+"\n"); return; }
        sb.append("## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+"\n");
        DecompileResults r=dec.decompileFunction(f,120,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
        else sb.append("  <decompile failed>\n");
    }

    void disasmWindow(long start, long end) {
        Address ad=toAddr(start);
        while(ad.getOffset()<end){
            Instruction ins=lst.getInstructionAt(ad);
            if(ins==null){ try{disassemble(ad);}catch(Exception e){} ins=lst.getInstructionAt(ad);}
            if(ins==null){ sb.append("    "+ad+"  (data)\n"); ad=ad.add(2); continue; }
            Reference[] rf=ins.getReferencesFrom();
            StringBuilder rs=new StringBuilder();
            for(Reference r:rf) rs.append(" ->"+r.getToAddress());
            sb.append("    "+ad+"  "+ins.toString()+rs+"\n");
            ad=ins.getMaxAddress().add(1);
        }
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
