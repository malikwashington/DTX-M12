// BootRecovery5.java — find the diagnostic/test-program + NOR-flash ID-read code, and the
// power-on key-combo dispatch. Determine if the test/update mode is in code.bin (PROG) or absent.
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

public class BootRecovery5 extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // (1) Find NOR-flash JEDEC autoselect ID-read: writes 0xAA->555, 0x55->2AA, 0x90->555.
        //     Look for functions that contain BOTH imm 0xAA and 0x55 and (0x90 or 0xF0).
        sb.append("################ (1) NOR JEDEC sequence functions (AA/55 + 90/F0/80/30/A0/10) ################\n");
        List<Function> norFns=new ArrayList<>();
        for (Function f: iterable(fm.getFunctions(true))) {
            Set<Long> imms=new HashSet<>();
            for (Instruction ins: iterable(lst.getInstructions(f.getBody(), true)))
                for (int op=0; op<ins.getNumOperands(); op++)
                    for (Object o: ins.getOpObjects(op))
                        if (o instanceof Scalar) imms.add(((Scalar)o).getUnsignedValue()&0xFFFFFFFFL);
            boolean aa=imms.contains(0xAAL), c55=imms.contains(0x55L);
            int cmds=0; for(long c: new long[]{0x90L,0xF0L,0x80L,0x30L,0xA0L,0x10L,0x70L,0x25L,0x29L,0xB0L}) if(imms.contains(c)) cmds++;
            if (aa && c55 && cmds>=1){
                norFns.add(f);
                StringBuilder cs=new StringBuilder();
                for(long c: new long[]{0x90L,0xF0L,0x80L,0x30L,0xA0L,0x10L,0x70L,0x25L,0x29L,0xB0L}) if(imms.contains(c)) cs.append("0x"+Long.toHexString(c)+" ");
                sb.append("  "+f.getName()+"@"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" cmds:"+cs+"\n");
            }
        }

        // Decompile the most likely NOR-program/erase function(s)
        sb.append("\n################ (2) decompile NOR-sequence candidates ################\n");
        int dn=0;
        for (Function f: norFns){
            if(dn++>=4) break;
            decomp(f);
            sb.append("  callers:"); for(Function cl: f.getCallingFunctions(mon)) sb.append(" "+cl.getEntryPoint()); sb.append("\n\n");
        }

        // (3) test-program MIDI codes 'A0 20', 'A0 21'... appear as data? search defined data + bytes
        sb.append("\n################ (3) any string/data containing diagnostic markers ################\n");
        for (Data d: iterable(lst.getDefinedData(true))) {
            Object v=d.getValue(); if(!(v instanceof String)) continue;
            String s=(String)v;
            if (s.contains("ROM")||s.contains("RAM")||s.contains("TEST")||s.contains("Test")
                ||s.contains("DIAG")||s.contains("Diag")||s.contains("IC1")||s.contains("Check")
                ||s.contains("Version")||s.contains("VERSION")){
                StringBuilder refs=new StringBuilder();
                int rc=0; for (Reference r: rm.getReferencesTo(d.getAddress())){ Function f=fm.getFunctionContaining(r.getFromAddress());
                    refs.append(" "+(f!=null?f.getEntryPoint():r.getFromAddress())); if(++rc>3) break; }
                sb.append("  \""+s.replace("\n","\\n")+"\" @"+d.getAddress()+(refs.length()>0?(" <-"+refs):"  (noxref)")+"\n");
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/boot_recovery5.txt");
        pw.print(sb.toString()); pw.close();
        println("BootRecovery5 wrote boot_recovery5.txt ("+sb.length()+" chars)");
    }
    void decomp(Function f) throws Exception {
        sb.append("## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+"\n");
        DecompileResults r=dec.decompileFunction(f,90,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
