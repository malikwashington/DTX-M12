// BootRecovery2.java — determine if updater/flash-writer live in PROG (code.bin) or are separate.
// Key questions:
//  (a) What is the "Executing.../Now working" UI? (normal file ops vs firmware updater)
//  (b) Is there ANY flash erase/program code in code.bin? (SH7206 FCU register accesses)
//  (c) Any boot-time unconditional reflash path / key-combo update mode?
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class BootRecovery2 extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // -------- (a) decompile the "Executing..." referencer + its callers --------
        sb.append("################ (a) 'Executing...' UI referencer @0x0C01A200 ################\n");
        decompAround(0x0C01A200L, 2);

        // -------- (b) FLASH CONTROL REGISTER scan --------
        // SH7206/SH72xx on-chip ROM (F-ZTAT/FCU) programming uses peripheral regs in the
        // 0xFFFFxxxx (P4) and 0x00xxxxxx ranges. The SH7206 FCU/FLD registers and the user-MAT
        // download-program approach use addresses like 0xFFFF????, 0x007F????, 0x00800000.
        // We flag any instruction whose operand immediates / referenced addresses fall in
        // classic flash/peripheral windows, and any function touching high P4 addresses.
        sb.append("\n################ (b) FLASH / high-peripheral address accesses ################\n");
        long[][] windows = {
            {0x00000000L, 0x00080000L},   // on-chip ROM / boot MAT
            {0x00800000L, 0x00880000L},   // user MAT image area (some parts)
            {0xFFFF8000L, 0xFFFFC000L},   // FCU / bus-state / peripheral
            {0xFFFFE000L, 0xFFFFFFFFL},   // P4 peripheral (INTC etc)
            {0xA0000000L, 0xA0080000L},   // P2 non-cached ROM mirror
        };
        Map<String,Integer> winHit=new LinkedHashMap<>();
        int scanned=0;
        for (Function f: iterable(fm.getFunctions(true))) {
            scanned++;
            for (Instruction ins: iterable(lst.getInstructions(f.getBody(), true))) {
                // referenced addresses
                for (Reference r: ins.getReferencesFrom()) {
                    long t=r.getToAddress().getOffset();
                    for (long[] w: windows) if (t>=w[0] && t<w[1]) {
                        String key=f.getName()+"@"+f.getEntryPoint()+" -> 0x"+Long.toHexString(t);
                        winHit.merge(key,1,Integer::sum);
                    }
                }
                // immediates that look like flash control magic (FKEY 0xA5, 0x5A, command bytes)
            }
        }
        sb.append("  functions referencing flash/peripheral windows ("+winHit.size()+"):\n");
        int c=0; for (Map.Entry<String,Integer> e: winHit.entrySet()){ sb.append("    "+e.getKey()+"\n"); if(++c>60){sb.append("    ...more...\n");break;} }

        // -------- (c) Immediate-value scan for flash unlock keys + erase command bytes --------
        sb.append("\n################ (c) flash-key immediate scan (A5/5A + status poll loops) ################\n");
        int kh=0;
        for (Function f: iterable(fm.getFunctions(true))) {
            Set<Long> imms=new HashSet<>();
            for (Instruction ins: iterable(lst.getInstructions(f.getBody(), true)))
                for (int op=0; op<ins.getNumOperands(); op++)
                    for (Object o: ins.getOpObjects(op))
                        if (o instanceof Scalar) imms.add(((Scalar)o).getUnsignedValue()&0xFFFFFFFFL);
            boolean a5=imms.contains(0xA5L), fa5=imms.contains(0xFFFFA5A5L)||imms.contains(0xA5A5L);
            // Renesas RX/SH FCU command set: 0xE8 (program), 0x20 (erase), 0xD0 (confirm), 0x50,0x70
            int fcuCmds=0;
            for (long cmd: new long[]{0xE8L,0x20L,0xD0L,0x50L,0x70L,0xFFL,0x71L})
                if (imms.contains(cmd)) fcuCmds++;
            if (a5||fa5){
                sb.append("  key? "+f.getName()+"@"+f.getEntryPoint()+" A5="+a5+" fcuCmds="+fcuCmds+"\n");
                if(++kh>30) break;
            }
        }

        // -------- (d) boot / reset early code: lowest-address functions + their string refs --------
        sb.append("\n################ (d) lowest-address functions (early boot candidates) ################\n");
        List<Function> all=new ArrayList<>(); for (Function f: iterable(fm.getFunctions(true))) all.add(f);
        all.sort((x,y)->Long.compare(x.getEntryPoint().getOffset(),y.getEntryPoint().getOffset()));
        for (int i=0;i<12 && i<all.size();i++){
            Function f=all.get(i);
            sb.append("  "+f.getName()+"@"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()
                      +" calledBy="+f.getCallingFunctions(mon).size()+"\n");
        }

        // -------- (e) key-combo / button-mask checks near boot (SHIFT/KIT/ENTER) --------
        // Diagnostic/update modes read a button matrix at power-on. We can't name the GPIO without
        // the schematic, but we can look for the version string and functions that compare a button
        // mask early. Find the "Ver:" / version screen string and its referencers.
        sb.append("\n################ (e) version-screen / diag-mode strings ################\n");
        for (Data d: iterable(lst.getDefinedData(true))) {
            Object v=d.getValue(); if(!(v instanceof String)) continue;
            String s=(String)v;
            if (s.contains("Ver")||s.contains("ver:")||s.contains("MULTI 12")||s.contains("Press [ENTER")
                ||s.contains("No media")||s.contains("not found")||s.contains("DTX-MULTI")){
                StringBuilder refs=new StringBuilder();
                for (Reference r: rm.getReferencesTo(d.getAddress())){
                    Function f=fm.getFunctionContaining(r.getFromAddress());
                    refs.append(" "+(f!=null?f.getName()+"@"+f.getEntryPoint():"?@"+r.getFromAddress()));
                }
                sb.append("  \""+s.replace("\n","\\n")+"\" @"+d.getAddress()+(refs.length()>0?(" <-"+refs):"  (NO XREF)")+"\n");
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/boot_recovery2.txt");
        pw.print(sb.toString()); pw.close();
        println("BootRecovery2 wrote boot_recovery2.txt ("+sb.length()+" chars), scanned "+scanned+" funcs");
    }

    void decompAround(long a, int depth) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ if(getInstructionAt(toAddr(a))==null) disassemble(toAddr(a));
            try{createFunction(toAddr(a),null);}catch(Exception e){} f=fm.getFunctionContaining(toAddr(a)); }
        if(f==null){ sb.append("  no func @0x"+Long.toHexString(a)+"\n"); return; }
        decomp(f);
        if (depth>0) for (Function caller: f.getCallingFunctions(mon)){
            sb.append("\n  --- CALLER of "+f.getName()+" ---\n");
            decomp(caller);
        }
    }
    void decomp(Function f) throws Exception {
        sb.append("## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+"\n");
        sb.append("callees:"); for(Function cc:f.getCalledFunctions(mon)) sb.append(" "+cc.getEntryPoint()); sb.append("\n");
        DecompileResults r=dec.decompileFunction(f,90,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
