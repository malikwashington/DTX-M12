// BootRecovery4.java — deep-dive the A55A function, search for flash NOR/FCU sequences with
// proper byte-level analysis, decompile the USB file handler, and dump the boot entry raw bytes.
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

public class BootRecovery4 extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // Decompile the candidate "system control" functions
        for (long a: new long[]{0x0c008868L, 0x0c011a16L, 0x0c0153bcL, 0x0c031774L}){
            sb.append("\n================ FUN @0x"+Long.toHexString(a)+" ================\n");
            Function f=fm.getFunctionContaining(toAddr(a));
            if(f==null){ if(getInstructionAt(toAddr(a))==null) disassemble(toAddr(a)); try{createFunction(toAddr(a),null);}catch(Exception e){} f=fm.getFunctionContaining(toAddr(a)); }
            if(f!=null){
                decomp(f);
                sb.append("  callers:");
                for(Function cl: f.getCallingFunctions(mon)) sb.append(" "+cl.getEntryPoint());
                sb.append("\n");
            } else sb.append("  no func @0x"+Long.toHexString(a)+"\n");
        }

        // Find the function that LOADS the "Now working" string ptr (0x0C08B53D / +index variants).
        // Strategy: scan all instructions; find mov.l that resolves to a literal whose value is in
        // the updater-string range 0x0C08B400-0x0C08B700, report containing function.
        sb.append("\n================ functions that LOAD updater-string pointers ================\n");
        Set<String> loaders=new LinkedHashSet<>();
        for (Function f: iterable(fm.getFunctions(true))) {
            for (Instruction ins: iterable(lst.getInstructions(f.getBody(), true))) {
                for (Reference r: ins.getReferencesFrom()){
                    long t=r.getToAddress().getOffset();
                    if (t>=0x0C08B400L && t<=0x0C08B700L)
                        loaders.add(f.getName()+"@"+f.getEntryPoint()+" loads 0x"+Long.toHexString(t));
                }
            }
        }
        for(String s: loaders){ sb.append("  "+s+"\n"); }
        if(loaders.isEmpty()) sb.append("  NONE found via direct refs — strings indexed via runtime table\n");

        // GLOBAL byte-level NOR / FCU command sequence scan over the whole code image.
        // NOR AMD unlock: write 0xAA to xxx555, 0x55 to xxx2AA, then 0x80/0x10/0x30/0xA0.
        // We instead just report ALL functions whose immediate set includes both 0xAA and 0x55
        // (any size), plus 0xA5/0x5A pairs (Renesas FCU/WDT keys).
        sb.append("\n================ GLOBAL flash-command immediate pairs ================\n");
        int g=0;
        for (Function f: iterable(fm.getFunctions(true))) {
            Set<Long> imms=new HashSet<>();
            for (Instruction ins: iterable(lst.getInstructions(f.getBody(), true)))
                for (int op=0; op<ins.getNumOperands(); op++)
                    for (Object o: ins.getOpObjects(op))
                        if (o instanceof Scalar) imms.add(((Scalar)o).getUnsignedValue()&0xFFFFFFFFL);
            boolean norPair = imms.contains(0xAAL)&&imms.contains(0x55L);
            boolean keyPair = imms.contains(0xA5L)&&imms.contains(0x5AL);
            boolean a55a    = imms.contains(0xA55AL)||imms.contains(0xFFFFA55AL)||imms.contains(0x5AA5L);
            if (norPair||keyPair||a55a){
                sb.append("  "+f.getName()+"@"+f.getEntryPoint()+" norPair="+norPair+" keyPair="+keyPair+" a55a="+a55a+"\n");
                if(++g>30) break;
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/boot_recovery4.txt");
        pw.print(sb.toString()); pw.close();
        println("BootRecovery4 wrote boot_recovery4.txt ("+sb.length()+" chars)");
    }
    void decomp(Function f) throws Exception {
        sb.append("## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+"\n");
        sb.append("callees:"); for(Function cc:f.getCalledFunctions(mon)) sb.append(" "+cc.getEntryPoint()); sb.append("\n");
        DecompileResults r=dec.decompileFunction(f,90,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
