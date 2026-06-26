// HookPoint.java — find a concrete hook for feature 6 in the pad-strike path.
// Approach: the pad "Pad Function" (UTIL4-1) dispatch selects an action when a pad with a
// control function is struck. We look for:
//  (1) the function that reads a pad-record "function type" byte and switches/dispatches to
//      action handlers (incl. our sequencer ones FUN_0c04f306/FUN_0c044022, click, tap, etc.).
//  (2) FUN_0c04f306's callers (the pad event entry) — decompile up the chain.
//  (3) any switch with many small-int arms calling into the 0x0C04Fxxx / 0x0C05xxxx action funcs.
// Also: dump FUN_0c04f306 callers and FUN_0c04fd68 (its main action) to understand the pad-event
// shape, so we can choose a single instruction to overwrite with a call to our trampoline.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class HookPoint extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // 1) callers of FUN_0c04f306 (pad seq action) up 2 levels
        sb.append("################ FUN_0c04f306 caller chain (pad-event path) ################\n");
        chain(0x0c04f306L, 2);

        // 2) the pad-function dispatcher: a function that references MANY of the action funcs in
        //    the 0x0C04Fxxx/0x0C05xxxx tables AND switches on a small int. We look for funcs that
        //    call FUN_0c04f306 and siblings.
        sb.append("\n################ FUN_0c04f306 + sibling action funcs — common caller (dispatcher) ################\n");
        Function f306=fm.getFunctionContaining(toAddr(0x0c04f306L));
        Set<Function> callers306 = f306.getCallingFunctions(mon);
        for(Function c: callers306){
            sb.append("\n#### caller "+c.getName()+"@"+c.getEntryPoint()+" size="+c.getBody().getNumAddresses()+" ####\n");
            // how many distinct 0x04Fxxx/0x05xxxx funcs does it call? (dispatcher signature)
            int act=0; Set<Long> ks=new TreeSet<>();
            for(Function cc: c.getCalledFunctions(mon)){
                long e=cc.getEntryPoint().getOffset();
                if((e>=0x0c04f000L&&e<0x0c05f000L)) act++;
            }
            for(Instruction ins: iterable(lst.getInstructions(c.getBody(),true)))
                if(ins.getMnemonicString().startsWith("cmp"))
                    for(int op=0;op<ins.getNumOperands();op++)
                        for(Object o:ins.getOpObjects(op))
                            if(o instanceof Scalar){ long v=((Scalar)o).getUnsignedValue(); if(v<=0x40) ks.add(v); }
            sb.append("  actionCalls="+act+" cmpKeys="+ks+"\n");
            DecompileResults r=dec.decompileFunction(c,120,mon);
            if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/hookpoint.txt");
        pw.print(sb.toString()); pw.close();
        println("HookPoint wrote hookpoint.txt ("+sb.length()+" chars)");
    }
    void chain(long a,int depth) throws Exception {
        if(depth<0) return;
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null) return;
        for(Function c: f.getCallingFunctions(mon)){
            sb.append("  ".repeat(2-depth)+"<- "+c.getName()+"@"+c.getEntryPoint()+" size="+c.getBody().getNumAddresses()+"\n");
            chain(c.getEntryPoint().getOffset(), depth-1);
        }
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
