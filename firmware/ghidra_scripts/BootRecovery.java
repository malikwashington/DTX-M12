// BootRecovery.java — map boot + firmware-update + recovery architecture to assess brick risk.
//  1) Reset vector / boot entry: SH-2A vector table is at the image base. vec[0]=initial PC,
//     vec[1]=initial SP. Read them; decompile the reset handler / early boot.
//  2) Updater strings near 0x0C08B4FC-0x0C08B5B0: find them + their referencing functions.
//  3) Flash write/erase routine: SH-2A on-chip flash (FLD/FLASH) uses control regs in the
//     0x007F.. or 0xFFA8.. / 0x00800000 area; standard ROM programming uses FCCS/FPCS/FECS/
//     FKEY/FMATS/FTDAR registers (SH7206/SH72xx). We scan for byte stores of the magic FKEY
//     value 0xA5 and the FMATS pattern, and for tight write loops near flash regs. Also we look
//     for "USB" / "Executing" string referencers and follow to the writer.
//  4) Report whether the updater + writer are in the patched app region (<0x0C140000) or a
//     separate/protected region, and whether any boot-time unconditional USB-update path exists.
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

public class BootRecovery extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // 1) reset vector table at base 0x0C000000
        sb.append("################ 1) RESET VECTORS @ base 0x0C000000 ################\n");
        for (int i=0;i<8;i++){
            long a=0x0C000000L + i*4;
            long v=mem.getInt(toAddr(a)) & 0xFFFFFFFFL;
            sb.append("  vec["+i+"] @0x"+Long.toHexString(a)+" = 0x"+Long.toHexString(v)+"\n");
        }
        long pc=mem.getInt(toAddr(0x0C000000L)) & 0xFFFFFFFFL;
        long sp=mem.getInt(toAddr(0x0C000004L)) & 0xFFFFFFFFL;
        sb.append("  -> initial PC=0x"+Long.toHexString(pc)+" initial SP=0x"+Long.toHexString(sp)+"\n");
        sb.append("\n-- reset handler decompile --\n");
        if (getInstructionAt(toAddr(pc))==null) disassemble(toAddr(pc));
        if (fm.getFunctionContaining(toAddr(pc))==null){ try{createFunction(toAddr(pc),null);}catch(Exception e){} }
        decomp(pc);

        // 2) updater strings
        sb.append("\n################ 2) UPDATER STRINGS + referencers ################\n");
        for (Data d: iterable(lst.getDefinedData(true))) {
            Object v=d.getValue(); if(!(v instanceof String)) continue;
            String s=(String)v; long off=d.getAddress().getOffset();
            if (off<0x0C08B000L || off>0x0C08C200L) {
                // also catch update-y keywords anywhere
                if (!(s.contains("Now working")||s.contains("Executing")||s.contains("USB device")
                      ||s.contains("update")||s.contains("Update")||s.contains("flash")
                      ||s.contains("Flash")||s.contains("Writing")||s.contains("writing")
                      ||s.contains(".PGM")||s.contains("PGM")||s.contains("Installer")
                      ||s.contains("Verify")||s.contains("verify")||s.contains("Erasing"))) continue;
            }
            StringBuilder refs=new StringBuilder();
            for (Reference r: rm.getReferencesTo(d.getAddress())){
                Function f=fm.getFunctionContaining(r.getFromAddress());
                refs.append(" "+(f!=null?f.getName()+"@"+f.getEntryPoint():"?@"+r.getFromAddress()));
            }
            if (s.length()>=3) sb.append("  \""+s+"\" @"+d.getAddress()+(refs.length()>0?(" <-"+refs):"  (NO XREF)")+"\n");
        }

        // 3) flash programming: look for FKEY magic 0xA5 stores and known flash reg addresses.
        // SH72xx F-ZTAT flash regs (P4 area): FCCS 0xFFFFE800-ish varies; but the SH7206 user-flash
        // programming is typically done via a download-to-RAM API. We scan for the 0x5A/0xA5 keys and
        // for references to addresses in 0x00000000-0x00080000 (on-chip ROM) and to peripheral flash
        // control space. Simpler: find functions whose immediates include the classic flash unlock
        // keys and tight loops; report them.
        sb.append("\n################ 3) FLASH-WRITE candidates (unlock keys / erase loops) ################\n");
        int hits=0;
        for (Function f: iterable(fm.getFunctions(true))) {
            if (hits>40) break;
            Set<Long> imms=new HashSet<>(); boolean hasFKEY=false, hasErase=false;
            for (Instruction ins: iterable(lst.getInstructions(f.getBody(), true))) {
                for (int op=0; op<ins.getNumOperands(); op++)
                    for (Object o: ins.getOpObjects(op))
                        if (o instanceof Scalar) imms.add(((Scalar)o).getUnsignedValue()&0xFFFFFFFFL);
            }
            // classic F-ZTAT: FKEY=0xA5, FMATS toggles, program/erase pulse counts; also "0x5A" appears
            if (imms.contains(0xA5L) && (imms.contains(0x5AL)||imms.contains(0x80L))) {
                // require it also touch a low ROM/flash address literal
                sb.append("  flash? "+f.getName()+"@"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()
                          +" imms{A5,..}\n"); hits++;
            }
        }

        // 4) Functions referencing very-low addresses (on-chip ROM/flash 0x0..0x80000) or the
        // installer header magic — indicates the flash writer / image copier.
        sb.append("\n################ 4) funcs referencing low ROM/flash addrs (<0x00080000) ################\n");
        int n=0;
        for (Function f: iterable(fm.getFunctions(true))) {
            if (n>30) break;
            boolean low=false;
            for (Reference r: getRefsFrom(f)) {
                long t=r.getToAddress().getOffset();
                if (t<0x00080000L) { low=true; break; }
            }
            if (low){ sb.append("  "+f.getName()+"@"+f.getEntryPoint()+"\n"); n++; }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/boot_recovery.txt");
        pw.print(sb.toString()); pw.close();
        println("BootRecovery wrote boot_recovery.txt ("+sb.length()+" chars)");
    }
    List<Reference> getRefsFrom(Function f){
        List<Reference> out=new ArrayList<>();
        for (Instruction ins: iterable(lst.getInstructions(f.getBody(), true)))
            for (Reference r: ins.getReferencesFrom()) out.add(r);
        return out;
    }
    void decomp(long a) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func @0x"+Long.toHexString(a)+"\n"); return; }
        sb.append("## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+"\n");
        sb.append("callees:"); for(Function c:f.getCalledFunctions(mon)) sb.append(" "+c.getEntryPoint()); sb.append("\n");
        DecompileResults r=dec.decompileFunction(f,120,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
