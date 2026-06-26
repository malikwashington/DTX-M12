// CleanMute.java — examine the CLEAN candidates that touch the voice slots:
//   FUN_0c0236d6 (CLEAN, size 758, calls both slot-ops) and FUN_0c0c0c70.
//   Also re-examine FUN_0c04898a's TRUE entry: maybe there is a clean wrapper that sets r10-r13
//   then calls into the loop. Find any function that CALLS FUN_0c04898a-internal-loop with a
//   fixed stack flag. And: search for the CC120/AllSoundOff trigger path (immediate 0x78) that
//   reaches FUN_0c04898a with correct context -> a pad could emit CC120 internally.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class CleanMute extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        long[] cands={0x0c0236d6L,0x0c0c0c70L};
        for(long a:cands){ sb.append("\n################ "+Long.toHexString(a)+" ################\n"); decomp(a); }

        // The 4 callers of FUN_0c04898a set the stack flag byte at [sp+3]. Find the exact value
        // each passes (so we know which flag combo = full stop). Disasm just the bytes that build
        // the stack arg before each call (search backward from call for mov.b ...,@(...,r15) / @-r15).
        sb.append("\n################ stack-flag setup at each FUN_0c04898a call site ################\n");
        long[] sites={0x0c03d3b4L,0x0c03d472L,0x0c03d554L,0x0c03d95cL};
        for(long s:sites){
            sb.append("\n-- call @0x"+Long.toHexString(s)+" : preceding 0x30 bytes --\n");
            for(long a=s-0x30;a<=s+4;a+=2){
                Instruction ins=lst.getInstructionAt(toAddr(a));
                if(ins==null){ try{disassemble(toAddr(a));}catch(Exception e){} ins=lst.getInstructionAt(toAddr(a)); }
                if(ins!=null) sb.append("    0x"+Long.toHexString(a)+"  "+ins.toString()+"\n");
            }
        }

        // Search for CC120 (0x78) AllSoundOff handler that calls/reaches FUN_0c04898a — a clean
        // internal trigger. Find funcs with imm 0x78 that call FUN_0c04898a-family or set stack[+3]=4.
        sb.append("\n################ funcs with 0x78 (CC120) near a mute call ################\n");
        for (Function f: iterable(fm.getFunctions(true))){
            boolean has78=false;
            for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true)))
                for(int op=0;op<ins.getNumOperands();op++)
                    for(Object o:ins.getOpObjects(op))
                        if(o instanceof Scalar && (((Scalar)o).getUnsignedValue()&0xFF)==0x78) has78=true;
            if(has78){
                boolean callsMute=false;
                for(Function c:f.getCalledFunctions(mon)) if(c.getEntryPoint().getOffset()==0x0c04898aL) callsMute=true;
                if(callsMute) sb.append("  "+f.getName()+"@"+f.getEntryPoint()+"  CALLS_MUTE size="+f.getBody().getNumAddresses()+"\n");
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/cleanmute.txt");
        pw.print(sb.toString()); pw.close();
        println("CleanMute wrote cleanmute.txt ("+sb.length()+" chars)");
    }
    void decomp(long a) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func\n"); return; }
        sb.append("## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+"\n");
        sb.append("callees:"); for(Function c:f.getCalledFunctions(mon)) sb.append(" "+c.getEntryPoint()); sb.append("\n");
        DecompileResults r=dec.decompileFunction(f,120,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
