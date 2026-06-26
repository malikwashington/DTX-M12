// StrikePath.java — determine whether FUN_0c0c9e60 is config-time or strike-time, and find the
// REAL strike-time pad-function dispatcher / tempo-increment handler.
//  1) Full caller chain of FUN_0c0c9e60 (up to 3 levels), decompiled, to classify the context
//     (edit/menu vs trigger/strike). Look for menu/cursor strings vs trigger/velocity signals.
//  2) The arm jmp-targets are 0x0C0C74xx (display refresh). Decompile one (FUN @0x0C0C747C) to
//     confirm it's a UI refresh -> proves FUN_0c0c9e60 is the EDIT/APPLY path.
//  3) Hunt the strike-time path: find functions that (a) read a pad's "function" setting from the
//     runtime block offsets the arms wrote (e.g. +6,+7,+9,...) AND (b) act (tempo inc / seq / etc).
//     Also search for tempo-increment: functions that load a tempo cell, add, clamp, store.
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

public class StrikePath extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // 1) caller chain of FUN_0c0c9e60
        sb.append("################ caller chain of FUN_0c0c9e60 (config vs strike?) ################\n");
        Function disp=fm.getFunctionContaining(toAddr(0x0c0c9e60L));
        Set<Long> seen=new HashSet<>();
        chain(disp, 3, seen);

        // decompile the immediate caller(s)
        sb.append("\n-- immediate caller decompiles --\n");
        for(Function c: disp.getCallingFunctions(mon)){
            sb.append("\n#### "+c.getName()+"@"+c.getEntryPoint()+" size="+c.getBody().getNumAddresses()+" ####\n");
            DecompileResults r=dec.decompileFunction(c,90,mon);
            if(r!=null&&r.getDecompiledFunction()!=null) sb.append(trim(r.getDecompiledFunction().getC(),60));
        }

        // 2) confirm arm jmp-target FUN_0c0c747c is a UI refresh
        sb.append("\n################ arm jmp-target FUN_0c0c747c (UI refresh? proves config-path) ################\n");
        decomp(0x0c0c747cL,40);

        // 3) strike-time tempo handler hunt: functions referencing a tempo cell with inc/clamp.
        // Kit tempo is 14-bit (range ~30..300). A tempo-inc handler will: load tempo, add 1, clamp
        // to max, store, and refresh. Search for immediates 300 (0x12C) or 0x4B0/0x4E (BPM-ish) and
        // a nearby add #1 + store. Also search functions that read pad runtime offset +6/+7 (the
        // function-param the arms wrote) and act.
        sb.append("\n################ tempo-increment candidates (load;add 1;clamp;store) ################\n");
        // tempo-ish immediates: 300=0x12C, 30=0x1E, and 0x4B0=1200? We scan for funcs with 0x12c.
        for(Function f: iterable(fm.getFunctions(true))){
            Set<Long> imms=imms(f);
            // candidate if it has a tempo-range constant AND an 'add #1' increment
            boolean tempoConst = imms.contains(0x12cL) || imms.contains(0x1eL);
            boolean hasAdd1=false, hasStore=false;
            for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true))){
                String s=ins.toString();
                if(s.equals("add 0x1,r0")||s.startsWith("add 0x1,")) hasAdd1=true;
                if(s.startsWith("mov.w r")||s.startsWith("mov.b r")) hasStore=true;
            }
            if(tempoConst && hasAdd1 && hasStore && f.getBody().getNumAddresses()<400){
                sb.append("  "+f.getName()+"@"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()
                          +" imms{tempoConst} callers="+f.getCallingFunctions(mon).size()+"\n");
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/strikepath.txt");
        pw.print(sb.toString()); pw.close();
        println("StrikePath wrote strikepath.txt ("+sb.length()+" chars)");
    }
    void chain(Function f,int depth,Set<Long> seen) throws Exception {
        if(f==null||depth<0) return;
        for(Function c: f.getCallingFunctions(mon)){
            long e=c.getEntryPoint().getOffset();
            sb.append("  ".repeat(3-depth)+"<- "+c.getName()+"@"+c.getEntryPoint()+" size="+c.getBody().getNumAddresses()+"\n");
            if(seen.add(e)) chain(c,depth-1,seen);
        }
    }
    void decomp(long a,int lim) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func @0x"+Long.toHexString(a)+"\n"); return; }
        sb.append("## "+f.getName()+"@"+f.getEntryPoint()+" ##\n");
        DecompileResults r=dec.decompileFunction(f,60,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(trim(r.getDecompiledFunction().getC(),lim));
    }
    String trim(String c,int lim){ String[] ls=c.split("\n"); StringBuilder b=new StringBuilder();
        for(int i=0;i<Math.min(ls.length,lim);i++) b.append(ls[i]).append("\n"); return b.toString(); }
    Set<Long> imms(Function f){ Set<Long> s=new HashSet<>();
        for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true)))
            for(int op=0;op<ins.getNumOperands();op++)
                for(Object o:ins.getOpObjects(op))
                    if(o instanceof Scalar) s.add(((Scalar)o).getUnsignedValue()&0xFFFFFFFFL);
        return s; }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
