// DataInit.java — find the SH-2A C-runtime data-init copy loops that populate RAM from ROM
// (code.bin). These establish the {ROM src -> RAM dest, length} table relocations whose contents
// we can then reconstruct statically. Strategy:
//  1) Scan for memcpy-style loops: a tight loop body that does mov.l @rS+,rT ; mov.l rT,@rD+ (or
//     mov.b / mov.w variants) with a decrementing counter and a conditional branch back. Capture
//     the function and the pointers/lengths feeding it (from preceding literal loads).
//  2) Scan for BSS-zero loops: mov #0,rX in a loop storing to @rD+ with a counter.
//  3) Tabulate, prefer the EARLIEST init cluster (low addresses / functions with no callers that
//     reference many disjoint RAM dest regions = the startup relocator).
//  4) Resolve the literal triples (src,dst,len) that each copy loop consumes, from the literal
//     pool loads right before the loop.
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

public class DataInit extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // 1) find copy-loop functions: contain a post-increment load AND a post-increment store
        // within a back-branch loop. Detect by mnemonic+operand patterns.
        sb.append("################ copy-loop / relocator candidates ################\n");
        List<Function> copyFns=new ArrayList<>();
        for(Function f: iterable(fm.getFunctions(true))){
            if(f.getBody().getNumAddresses()>400) continue;
            boolean ldInc=false, stInc=false, backBranch=false, zeroStore=false;
            long fstart=f.getEntryPoint().getOffset();
            for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true))){
                String s=ins.toString();
                if(s.matches("mov\\.[lwb] @r\\d+\\+,r\\d+")) ldInc=true;
                if(s.matches("mov\\.[lwb] r\\d+,@r\\d+\\+")) stInc=true;
                // back branch: bf/bt to an address < current (loop)
                String m=ins.getMnemonicString();
                if((m.equals("bf")||m.equals("bt")||m.equals("bf/s")||m.equals("bt/s"))){
                    for(Reference r: ins.getReferencesFrom())
                        if(r.getToAddress().getOffset()<ins.getAddress().getOffset()) backBranch=true;
                }
                if(s.startsWith("mov #0x0,") ) zeroStore=true;
            }
            if(ldInc && stInc && backBranch){
                copyFns.add(f);
                sb.append(String.format("  COPY  0x%08x size=%d callers=%d\n",fstart,f.getBody().getNumAddresses(),f.getCallingFunctions(mon).size()));
            } else if(stInc && backBranch && zeroStore){
                sb.append(String.format("  ZERO? 0x%08x size=%d callers=%d\n",fstart,f.getBody().getNumAddresses(),f.getCallingFunctions(mon).size()));
            }
        }

        // 2) Many relocations are done INLINE in a big startup function (not separate copy funcs):
        // a function with MANY (src,dst,len) literal triples + repeated calls to one small copy fn.
        // Find the function that calls a copy fn many times, or has many mov.l literal loads of
        // RAM dest addresses (0x0C2xxxxx / 0x0C3xxxxx) followed by a loop.
        sb.append("\n################ startup relocator (many RAM-dest literals + copy calls) ################\n");
        for(Function f: iterable(fm.getFunctions(true))){
            if(f.getBody().getNumAddresses()>3000) continue;
            int ramDestLits=0, callCopy=0;
            Set<Long> dests=new TreeSet<>();
            for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true))){
                String s=ins.toString();
                if(s.startsWith("mov.l @(0x")&&s.contains(",pc),")){
                    long ea=pcRelLit(ins);
                    if(ea!=0){ long v=mem.getInt(toAddr(ea))&0xFFFFFFFFL;
                        if(v>=0x0C200000L && v<0x0C400000L){ ramDestLits++; dests.add(v); } }
                }
                for(Function c: f.getCalledFunctions(mon)) if(copyFns.contains(c)) callCopy=1;
            }
            if(ramDestLits>=6){
                sb.append(String.format("  RELOC? 0x%08x ramDestLits=%d distinct=%d callers=%d size=%d\n",
                          f.getEntryPoint().getOffset(),ramDestLits,dests.size(),f.getCallingFunctions(mon).size(),f.getBody().getNumAddresses()));
            }
        }

        // 3) decompile the top copy candidates to read their (src,dst,len) args
        sb.append("\n################ copy-fn decompiles ################\n");
        int n=0;
        for(Function f: copyFns){ if(n++>=8) break; sb.append("\n"); decompShort(f); }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/datainit.txt");
        pw.print(sb.toString()); pw.close();
        println("DataInit wrote datainit.txt ("+sb.length()+" chars)");
    }
    void decompShort(Function f) throws Exception {
        sb.append("## "+f.getName()+"@"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()
                  +" callers="+f.getCallingFunctions(mon).size()+" ##\n");
        DecompileResults r=dec.decompileFunction(f,60,mon);
        if(r!=null&&r.getDecompiledFunction()!=null){ String[] ls=r.getDecompiledFunction().getC().split("\n");
            for(int i=0;i<Math.min(ls.length,30);i++) sb.append(ls[i]).append("\n"); }
    }
    long pcRelLit(Instruction ins){
        for(int op=0;op<ins.getNumOperands();op++)
            for(Object o:ins.getOpObjects(op))
                if(o instanceof Scalar){ long disp=((Scalar)o).getUnsignedValue();
                    if(disp<0x1000) return (ins.getAddress().getOffset()&~3L)+4+disp*4; }
        return 0;
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
