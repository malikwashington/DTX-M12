// F6Recon.java — feature-6 design recon.
//  1) FUN_0c0C9E60 pad-function switch: full disasm with raw bytes + decompile; enumerate the
//     compared function-code values and each arm's target, to pick a free code + a hook point.
//  2) FUN_0c0440c6 (clean transport stop) + its callees: full disasm + decompile, identify
//     the self-contained voice-mute primitive among callees.
//  3) FUN_0c048956 vs FUN_0c04898a: disasm both entries; find the voice-clear loop body and
//     whether a clean (no inherited-reg) callable does just the 100-slot clear.
//  4) Dump the literal pools so we can resolve primitive addresses for the trampoline.
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

public class F6Recon extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        sb.append("################ 1) FUN_0c0c9e60 pad-function switch ################\n");
        rawDisasm(0x0c0c9e60L);
        decomp(0x0c0c9e60L);

        sb.append("\n################ 2) FUN_0c0440c6 (clean transport stop) ################\n");
        rawDisasm(0x0c0440c6L);
        decomp(0x0c0440c6L);
        sb.append("\n-- FUN_0c0440c6 callees decompiled (find self-contained voice-mute) --\n");
        Function f44=fm.getFunctionContaining(toAddr(0x0c0440c6L));
        for (Function c: f44.getCalledFunctions(mon)) {
            sb.append("\n#### callee "+c.getName()+" @"+c.getEntryPoint()
                      +" size="+c.getBody().getNumAddresses()+" ####\n");
            DecompileResults r=dec.decompileFunction(c,90,mon);
            boolean unaff=false;
            if(r!=null&&r.getDecompiledFunction()!=null){
                String cc=r.getDecompiledFunction().getC();
                unaff=cc.contains("unaff_")||cc.contains("in_stack_");
                sb.append("CLEAN="+(!unaff)+"\n"+cc);
            }
        }

        sb.append("\n################ 3) FUN_0c048956 / FUN_0c04898a entries ################\n");
        // disasm both candidate entries to see which is the loop-only clean clear
        rawDisasm(0x0c048956L);

        sb.append("\n################ 4) literal pools (resolve primitive addrs) ################\n");
        // FUN_0c0c9e60 pool, FUN_0c0440c6 pool — print the data words near each function end
        dumpLiterals(0x0c0c9e60L, 0x0c0ca160L);

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/f6_recon.txt");
        pw.print(sb.toString()); pw.close();
        println("F6Recon wrote f6_recon.txt ("+sb.length()+" chars)");
    }

    void rawDisasm(long a) {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func @0x"+Long.toHexString(a)+" — raw window:\n");
            for(long x=a;x<a+0x40;x+=2){ try{int w=mem.getShort(toAddr(x))&0xFFFF; sb.append(String.format("  0x%08x: %04x\n",x,w));}catch(Exception e){} }
            return; }
        sb.append("## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()
                  +" (file 0x"+Long.toHexString(f.getEntryPoint().getOffset()-0x0C000000L)+")\n");
        for (Instruction ins: iterable(lst.getInstructions(f.getBody(), true))) {
            long ad=ins.getAddress().getOffset();
            StringBuilder bytes=new StringBuilder();
            try { for(int i=0;i<ins.getLength();i++) bytes.append(String.format("%02x",mem.getByte(toAddr(ad+i))&0xFF)); }
            catch(Exception e){}
            Reference[] rf=ins.getReferencesFrom(); StringBuilder rs=new StringBuilder();
            for(Reference r:rf) rs.append(" ->"+r.getToAddress());
            sb.append(String.format("  0x%08x  %-10s %s%s\n", ad, bytes.toString(), ins.toString(), rs.toString()));
        }
    }
    void decomp(long a) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func @0x"+Long.toHexString(a)+"\n"); return; }
        sb.append("-- decompile "+f.getName()+" --\n");
        DecompileResults r=dec.decompileFunction(f,120,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
    }
    void dumpLiterals(long start, long end){
        for(long a=start;a<end;a+=4){
            try{ long v=mem.getInt(toAddr(a))&0xFFFFFFFFL;
                if(v>=0x0C000000L && v<=0x0C2FFFFFL){
                    Function tf=fm.getFunctionContaining(toAddr(v));
                    sb.append(String.format("  lit @0x%08x = 0x%08x %s\n",a,v,tf!=null?("("+tf.getName()+")"):""));
                }
            }catch(Exception e){}
        }
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
