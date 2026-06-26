// Vtable.java — locate static vtables (const function-pointer arrays) in code.bin and resolve the
// object pointers used by the strike-dispatch sites.
//  1) Scan code.bin for runs of >=6 consecutive code pointers (each in 0x0C000000..0x0C140000,
//     4-aligned, pointing at a function entry) = candidate vtables / handler tables. Report base +
//     entries (resolved to FUN names).
//  2) For a set of strike-dispatch functions that do (**(code**)(objptr+off))(), find where objptr
//     comes from: trace the literal that loads the object base (a DAT in RAM), and report the off
//     values used, so we can map obj+off -> which vtable slot.
//  3) Specifically resolve the dispatch in FUN_0c0f1b26/FUN_0c0f1e64 (obj+0x1e4/0x1e8/0x110/0xf2)
//     and the pad-control path. Also: is there a const handler table indexed by pad-function code
//     (stride 4 or 0x10) sitting in code.bin? Cross-ref with the action funcs.
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

public class Vtable extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    boolean isCodePtr(long v){
        if(v<0x0C000000L || v>=0x0C140000L || (v&1)!=0) return false;
        return fm.getFunctionContaining(toAddr(v))!=null;
    }

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // 1) static vtable scan: runs of >=6 code pointers
        sb.append("################ static vtable / handler-array candidates (>=6 code ptrs) ################\n");
        int reported=0;
        for(long a=0x0C000000L; a<0x0C140000L-24; ){
            int n=0; long b=a;
            while(b<0x0C140000L-4){ long v=u32(b); if(isCodePtr(v)){ n++; b+=4; } else break; }
            if(n>=6){
                sb.append(String.format("\n  vtable @0x%08x (%d ptrs):\n",a,n));
                for(int i=0;i<Math.min(n,20);i++){
                    long v=u32(a+i*4L); Function f=fm.getFunctionContaining(toAddr(v));
                    sb.append(String.format("    [%2d] 0x%08x %s\n",i,v,f!=null?f.getName():"?"));
                }
                reported++; a=b;
                if(reported>30) { sb.append("  ...(more, truncated)\n"); break; }
            } else a += (n>0? n*4L : 4);
        }

        // 2) object-pointer resolution for strike-dispatch funcs: find the DAT literal that loads
        // the object base in each, and the offsets used in (**(code**)(obj+off))().
        sb.append("\n################ object-pointer literals in strike-dispatch funcs ################\n");
        long[] fns={0x0c0f1b26L,0x0c0f1e64L,0x0c044938L,0x0c0656baL,0x0c04dca4L};
        for(long fa: fns){
            Function f=fm.getFunctionContaining(toAddr(fa));
            if(f==null) continue;
            sb.append("\n-- "+f.getName()+"@"+f.getEntryPoint()+" --\n");
            for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true))){
                String s=ins.toString();
                if(s.startsWith("mov.l @(0x")&&s.contains(",pc),")){
                    long ea=pcRelLit(ins); if(ea==0) continue;
                    long v=u32(ea);
                    sb.append(String.format("    @%s  %s  -> [0x%08x]=0x%08x %s\n",ins.getAddress(),s,ea,v,
                              isCodePtr(v)?("("+fm.getFunctionContaining(toAddr(v)).getName()+")"):(v>=0x0C200000L&&v<0x0C700000L?"(RAM obj)":"")));
                }
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/vtable.txt");
        pw.print(sb.toString()); pw.close();
        println("Vtable wrote vtable.txt ("+sb.length()+" chars)");
    }
    long u32(long a){ try{ return mem.getInt(toAddr(a))&0xFFFFFFFFL; }catch(Exception e){ return 0; } }
    long pcRelLit(Instruction ins){
        for(int op=0;op<ins.getNumOperands();op++)
            for(Object o:ins.getOpObjects(op))
                if(o instanceof Scalar){ long disp=((Scalar)o).getUnsignedValue();
                    if(disp<0x1000) return (ins.getAddress().getOffset()&~3L)+4+disp*4; }
        return 0;
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
