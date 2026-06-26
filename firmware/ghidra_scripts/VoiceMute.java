// VoiceMute.java — find a CLEAN, self-contained voice-mute primitive for feature 6.
//  The mute-all loop in FUN_0c04898a does, per slot (stride 0xF1C, 100 slots):
//    (*FUN_0c069cf8)(base+slot, ptr, 0xC);     // clear/memset a 0xC-byte descriptor
//    (*FUN_0c06a0f8)(base+slot, ...);           // voice-off
//  We decompile FUN_0c069cf8 and FUN_0c06a0f8 (the per-slot ops) and check CLEAN-ness, and
//  identify the slot base (DAT_0c048b80 -> 0x0C69E7BC) and what 0xFF/0x7F constants get written.
//  GOAL: determine whether we can write a tiny standalone loop in the trampoline that clears the
//  same 100 slots (calling the clean per-slot ops), OR whether there's an even simpler existing
//  clean "panic"/all-note-off function we can call.
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

public class VoiceMute extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        long[] prims={0x0c069cf8L,0x0c06a0f8L,0x0c041078L,0x0c040d1aL,0x0c04088aL,0x0c040d1aL};
        for(long p:prims){
            Function f=fm.getFunctionContaining(toAddr(p));
            if(f==null){ sb.append("\n#### no func @0x"+Long.toHexString(p)+" ####\n"); continue; }
            DecompileResults r=dec.decompileFunction(f,90,mon);
            String c=(r!=null&&r.getDecompiledFunction()!=null)?r.getDecompiledFunction().getC():"";
            boolean clean=!(c.contains("unaff_")||c.contains("in_stack_"));
            sb.append("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()
                      +" CLEAN="+clean+" callees="+f.getCalledFunctions(mon).size()+" ####\n"+c);
        }

        // Look for an existing clean "all sound off"/"panic" that does NOT need MIDI ctx: search
        // funcs that write 0xFF in a loop with stride 0xF1C, OR that reference the slot base
        // DAT 0x0C69E7BC. Also any func calling BOTH FUN_0c069cf8 and FUN_0c06a0f8 (the slot ops).
        sb.append("\n################ funcs calling BOTH slot-ops FUN_0c069cf8 & FUN_0c06a0f8 ################\n");
        Function pA=fm.getFunctionContaining(toAddr(0x0c069cf8L));
        Function pB=fm.getFunctionContaining(toAddr(0x0c06a0f8L));
        Set<Function> ca=pA!=null?pA.getCallingFunctions(mon):new HashSet<>();
        Set<Function> cb=pB!=null?pB.getCallingFunctions(mon):new HashSet<>();
        for(Function f:ca){
            if(cb.contains(f)){
                DecompileResults r=dec.decompileFunction(f,90,mon);
                String c=(r!=null&&r.getDecompiledFunction()!=null)?r.getDecompiledFunction().getC():"";
                boolean clean=!(c.contains("unaff_")||c.contains("in_stack_"));
                sb.append("  "+f.getName()+"@"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()
                          +" CLEAN="+clean+"\n");
            }
        }

        // The mute loop uses movi20 #0xf1c. Find ALL funcs that contain immediate 0xF1C (the slot
        // stride) — these are the voice-bank walkers; one may be a clean panic.
        sb.append("\n################ funcs with immediate 0xF1C (voice-slot stride) ################\n");
        for (Function f: iterable(fm.getFunctions(true))){
            boolean has=false;
            for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true)))
                for(int op=0;op<ins.getNumOperands();op++)
                    for(Object o:ins.getOpObjects(op))
                        if(o instanceof Scalar && (((Scalar)o).getUnsignedValue()&0xFFFFF)==0xF1C) has=true;
            if(has){
                DecompileResults r=dec.decompileFunction(f,60,mon);
                String c=(r!=null&&r.getDecompiledFunction()!=null)?r.getDecompiledFunction().getC():"";
                boolean clean=!(c.contains("unaff_")||c.contains("in_stack_"));
                sb.append("  "+f.getName()+"@"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()
                          +" CLEAN="+clean+" callers="+f.getCallingFunctions(mon).size()+"\n");
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/voicemute.txt");
        pw.print(sb.toString()); pw.close();
        println("VoiceMute wrote voicemute.txt ("+sb.length()+" chars)");
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
