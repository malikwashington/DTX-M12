// PadFuncTable.java — hunt for the Pad Function (UTIL4-1) indexed dispatch: a jump table of
// action handlers indexed by the pad-function enum. Signature in SH-2A:
//    mov.l @(r0,rN),rM ; jmp @rM   (or jsr) where rN is a literal pointing into a fn-ptr table.
// We scan all functions for `mov.l @(r0,rX),rY` followed (within a few insns) by `jmp @rY`/`jsr @rY`,
// and report the table base (the literal loaded into rX) + the table contents. The pad-function
// table will contain entries pointing at click/tap/seq/transport action funcs.
// Also: directly scan the data region for fn-ptr arrays whose entries include FUN_0c044022,
// FUN_0c0440de, FUN_0c04f306 (known pad/seq actions) consecutively -> that's the pad-function table.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class PadFuncTable extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // (A) data-region scan: find runs of >=8 consecutive code pointers (a jump/action table)
        // that include any of the known pad/seq action funcs.
        Set<Long> known=new HashSet<>(Arrays.asList(0x0c044022L,0x0c0440deL,0x0c04f306L,0x0c0440c6L,
            0x0c04fd68L,0x0c054cceL,0x0c047b62L));
        sb.append("################ fn-ptr tables containing known pad/seq actions ################\n");
        Memory m=mem;
        long lo=0x0c020000L, hi=0x0c100000L;
        long a=lo;
        while(a<hi){
            // detect start of a code-ptr run
            int run=0; long s=a;
            while(a<hi){
                long v;
                try{ v=m.getInt(toAddr(a))&0xFFFFFFFFL; }catch(Exception e){ break; }
                if(v>=0x0C000000L && v<0x0C140000L){ run++; a+=4; } else break;
            }
            if(run>=8){
                // does this run contain a known action? if so dump it
                boolean has=false;
                for(long x=s;x<s+run*4L;x+=4){ long v=m.getInt(toAddr(x))&0xFFFFFFFFL; if(known.contains(v)) has=true; }
                if(has){
                    sb.append(String.format("\n-- table @0x%08x .. 0x%08x (%d entries) --\n",s,s+run*4L,run));
                    for(long x=s;x<s+run*4L;x+=4){
                        long v=m.getInt(toAddr(x))&0xFFFFFFFFL;
                        Function tf=fm.getFunctionContaining(toAddr(v));
                        sb.append(String.format("  [%2d] 0x%08x 0x%08x %s%s\n",(int)((x-s)/4),x,v,
                            tf!=null?("("+tf.getName()+")"):"", known.contains(v)?"  <== known action":""));
                    }
                }
            } else { a=s+ (run>0?run*4L:2); }
            if(a<=s) a=s+2;
        }

        // (B) indexed-jump scan: mov.l @(r0,rN),rM ; (jmp|jsr) @rM
        sb.append("\n################ indexed jump-table dispatch sites (mov.l @(r0,rN),rM; jmp @rM) ################\n");
        for(Function f: iterable(fm.getFunctions(true))){
            Instruction prev=null;
            for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true))){
                String m2=ins.getMnemonicString();
                if((m2.equals("jmp")||m2.equals("jsr")) && prev!=null){
                    String ps=prev.toString();
                    if(ps.startsWith("mov.l @(r0,")){
                        sb.append("  "+f.getName()+"@"+f.getEntryPoint()+"  @"+prev.getAddress()+"  "+ps+" ; "+ins.toString()+"\n");
                    }
                }
                prev=ins;
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/padfunctable.txt");
        pw.print(sb.toString()); pw.close();
        println("PadFuncTable wrote padfunctable.txt ("+sb.length()+" chars)");
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
