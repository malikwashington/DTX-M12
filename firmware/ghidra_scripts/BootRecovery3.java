// BootRecovery3.java — decompile the real boot entry, the USB-update/"File not found" handler,
// and trace what loads the "Now working/Executing" strings. Also a TIGHT flash-register scan.
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

public class BootRecovery3 extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        sb.append("################ BOOT ENTRY @0x0C000004 + chain ################\n");
        decompAt(0x0C000004L);

        sb.append("\n################ 'File not found' handler FUN @0c031774 (USB updater?) ################\n");
        Function fnf=fm.getFunctionContaining(toAddr(0x0C031774L));
        if(fnf!=null){ decomp(fnf);
            sb.append("\n  callers:\n");
            for(Function cl: fnf.getCallingFunctions(mon)) sb.append("    "+cl.getName()+"@"+cl.getEntryPoint()+"\n");
        } else sb.append("  no func\n");

        // Find the instruction(s) that LOAD literal 0x0C01A200... no — 0x0C01A200 was the *location*
        // of a pointer in the literal pool. Find which function's body contains 0x0C01A200 (it loads
        // the "Executing" string via mov.l @(disp,pc)).
        sb.append("\n################ function CONTAINING the 'Executing' literal-load site ################\n");
        Function exf=fm.getFunctionContaining(toAddr(0x0C01A200L));
        if(exf!=null){ sb.append("  contains: "+exf.getName()+"@"+exf.getEntryPoint()+"\n"); decomp(exf); }
        else sb.append("  0x0C01A200 not in any function (it's in a literal pool / data)\n");

        // TIGHT flash scan: only real FCU/peripheral addresses. SH7206 FCU regs live at
        // 0xFFFF8000-0xFFFFE000 area (FRT/FCCS). Also scan for the specific magic words used in
        // SH F-ZTAT in-RAM programming: 0x5555, 0x2AAA (AMD-style), 0xAA/0x55 (NOR command),
        // and the download-API entry addresses in on-chip ROM (0x..) — report functions whose
        // immediates include a >=0xFFFF8000 address OR 0x00007F.. (FCU) constants.
        sb.append("\n################ TIGHT flash/FCU register scan ################\n");
        int hits=0;
        for (Function f: iterable(fm.getFunctions(true))) {
            Set<Long> bigImm=new TreeSet<>();
            boolean nor=false; Set<Long> imms=new HashSet<>();
            for (Instruction ins: iterable(lst.getInstructions(f.getBody(), true))) {
                for (int op=0; op<ins.getNumOperands(); op++)
                    for (Object o: ins.getOpObjects(op))
                        if (o instanceof Scalar){
                            long v=((Scalar)o).getUnsignedValue()&0xFFFFFFFFL; imms.add(v);
                            if (v>=0xFFFF8000L && v<=0xFFFFFFFFL) bigImm.add(v);
                            if (v>=0x00007F00L && v<=0x00008100L) bigImm.add(v);
                            if (v>=0x00800000L && v<=0x00880000L) bigImm.add(v);
                        }
                // also referenced addresses in those windows (PC-relative literal loads)
                for (Reference r: ins.getReferencesFrom()){
                    long t=r.getToAddress().getOffset();
                    if (t>=0xFFFF8000L || (t>=0x00007F00L&&t<=0x00008100L) || (t>=0x00800000L&&t<=0x00880000L)) bigImm.add(t);
                }
            }
            // NOR-flash AMD command sequence detection
            if (imms.contains(0xAAL)&&imms.contains(0x55L)&&(imms.contains(0x80L)||imms.contains(0x30L)||imms.contains(0xA0L))) nor=true;
            if (!bigImm.isEmpty() || nor){
                sb.append("  flash? "+f.getName()+"@"+f.getEntryPoint()+" nor="+nor+" bigImm=");
                for(long v: bigImm) sb.append("0x"+Long.toHexString(v)+" ");
                sb.append("\n");
                if(++hits>40) break;
            }
        }

        // What addresses does the boot/init reference for device init? List functions called by boot
        // that reference high peripheral addrs >=0xFFFF8000 (these are the LCD/USB/MIDI device inits).
        sb.append("\n################ peripheral-init: funcs referencing >=0xFFFF8000 (real P4) ################\n");
        int pc2=0;
        Set<String> seen=new LinkedHashSet<>();
        for (Function f: iterable(fm.getFunctions(true))) {
            for (Instruction ins: iterable(lst.getInstructions(f.getBody(), true))){
                for (Reference r: ins.getReferencesFrom()){
                    long t=r.getToAddress().getOffset();
                    if (t>=0xFFFF8000L) seen.add(f.getName()+"@"+f.getEntryPoint()+" ->0x"+Long.toHexString(t));
                }
                for (int op=0; op<ins.getNumOperands(); op++)
                    for (Object o: ins.getOpObjects(op))
                        if (o instanceof Scalar){ long v=((Scalar)o).getUnsignedValue()&0xFFFFFFFFL;
                            if (v>=0xFFFF8000L) seen.add(f.getName()+"@"+f.getEntryPoint()+" imm0x"+Long.toHexString(v)); }
            }
        }
        for(String s: seen){ sb.append("  "+s+"\n"); if(++pc2>50){sb.append("  ...more...\n");break;} }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/boot_recovery3.txt");
        pw.print(sb.toString()); pw.close();
        println("BootRecovery3 wrote boot_recovery3.txt ("+sb.length()+" chars)");
    }
    void decompAt(long a) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ if(getInstructionAt(toAddr(a))==null) disassemble(toAddr(a)); try{createFunction(toAddr(a),null);}catch(Exception e){} f=fm.getFunctionContaining(toAddr(a)); }
        if(f==null){ sb.append("  no func @0x"+Long.toHexString(a)+"\n"); return; }
        decomp(f);
    }
    void decomp(Function f) throws Exception {
        sb.append("## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+"\n");
        sb.append("callees:"); for(Function cc:f.getCalledFunctions(mon)) sb.append(" "+cc.getEntryPoint()); sb.append("\n");
        DecompileResults r=dec.decompileFunction(f,90,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
