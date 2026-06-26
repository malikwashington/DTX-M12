// MuteEntry.java — definitively map the mute routine: disassemble from 0x0c048956 (suspected
// TRUE entry) through 0x0c048ae0, showing where r8/r9/r10/r11/r12/r13/r14 are LOADED, and whether
// 0x0c04898a (the 4 callers' target) is reached by fallthrough with those regs already set.
// Then decompile the function created at 0x0c048956 and check CLEAN-ness + its param (the flag).
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class MuteEntry extends GhidraScript {
    public void run() throws Exception {
        StringBuilder sb=new StringBuilder();
        Listing lst=currentProgram.getListing();
        Memory mem=currentProgram.getMemory();
        FunctionManager fm=currentProgram.getFunctionManager();
        ReferenceManager rm=currentProgram.getReferenceManager();
        DecompInterface dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        ConsoleTaskMonitor mon=new ConsoleTaskMonitor();

        // who references 0x0c048956 (is it a known entry?) and 0x0c04898a
        sb.append("refs to 0x0c048956:\n");
        for(Reference r: rm.getReferencesTo(toAddr(0x0c048956L))) sb.append("  "+r.getReferenceType()+"@"+r.getFromAddress()+"\n");
        sb.append("refs to 0x0c04898a:\n");
        for(Reference r: rm.getReferencesTo(toAddr(0x0c04898aL))) sb.append("  "+r.getReferenceType()+"@"+r.getFromAddress()+"\n");
        Function f956=fm.getFunctionContaining(toAddr(0x0c048956L));
        sb.append("func containing 0x0c048956 = "+(f956!=null?f956.getName()+"@"+f956.getEntryPoint():"NONE")+"\n");

        sb.append("\n=== disasm 0x0c048956 .. 0x0c048a40 (reg loads) ===\n");
        for(long a=0x0c048956L;a<0x0c048a40L;){
            Instruction ins=lst.getInstructionAt(toAddr(a));
            if(ins==null){ try{disassemble(toAddr(a));}catch(Exception e){} ins=lst.getInstructionAt(toAddr(a)); }
            if(ins==null){ int w=mem.getShort(toAddr(a))&0xFFFF; sb.append(String.format("  0x%08x  .word 0x%04x\n",a,w)); a+=2; continue; }
            long ad=ins.getAddress().getOffset();
            StringBuilder by=new StringBuilder();
            for(int i=0;i<ins.getLength();i++) by.append(String.format("%02x",mem.getByte(toAddr(ad+i))&0xFF));
            String mark=(ad==0x0c04898aL)?"   <==== 4 callers jsr HERE":"";
            sb.append(String.format("  0x%08x  %-8s %s%s\n",ad,by,ins.toString(),mark));
            a=ins.getMaxAddress().getOffset()+1;
        }

        // Force a function at 0x0c048956 if not present, decompile it.
        if(f956==null || f956.getEntryPoint().getOffset()!=0x0c048956L){
            try{ if(getInstructionAt(toAddr(0x0c048956L))==null) disassemble(toAddr(0x0c048956L));
                 createFunction(toAddr(0x0c048956L),"MUTE_ENTRY"); }catch(Exception e){ sb.append("createFunction: "+e+"\n"); }
        }
        Function mf=fm.getFunctionContaining(toAddr(0x0c048956L));
        if(mf!=null){
            sb.append("\n=== decompile func @"+mf.getEntryPoint()+" ===\n");
            DecompileResults r=dec.decompileFunction(mf,120,mon);
            if(r!=null&&r.getDecompiledFunction()!=null){
                String c=r.getDecompiledFunction().getC();
                sb.append("CLEAN(no unaff/in_stack)="+!(c.contains("unaff_")||c.contains("in_stack_"))+"\n"+c);
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/muteentry.txt");
        pw.print(sb.toString()); pw.close();
        println("MuteEntry wrote muteentry.txt ("+sb.length()+" chars)");
    }
}
