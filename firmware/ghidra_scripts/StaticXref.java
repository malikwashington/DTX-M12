// StaticXref.java — resolve the STATIC indirect-call graph (the PTR_FUN literals that hold fixed
// code addresses), which Ghidra didn't turn into call refs. For each function, scan its literal
// pool for entries that are code pointers, and record edge (func -> target). Then INVERT: find
// which functions statically reference the seq/transport ACTION funcs via a literal AND have a
// pad-function-code switch. This recovers callers that the normal call-graph missed.
//   Targets: FUN_0c0440de, FUN_0c044022, FUN_0c0440c6, FUN_0c043ed6 (transport/seq actions).
//   Report each referrer with its compare-key set (the function-code switch) and region.
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

public class StaticXref extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // The action funcs whose STATIC referrers we want (transport/seq/tempo on a pad function).
        Set<Long> targets=new HashSet<>(Arrays.asList(
            0x0c0440deL,0x0c044022L,0x0c0440c6L,0x0c043ed6L,0x0c043ffcL,0x0c04f306L));
        // Build: which RAM/code literal cells hold these targets, and who loads them.
        // 1) find pool addresses (code.bin words) whose value == a target.
        Map<Long,Long> litToTarget=new HashMap<>(); // litAddr -> target
        for(long a=0x0C000000L; a<0x0C140000L-4; a+=2){
            long v=u32(a); if(targets.contains(v)) litToTarget.put(a,v);
        }
        sb.append("################ literal cells holding action-func addresses ################\n");
        for(Map.Entry<Long,Long> e: litToTarget.entrySet())
            sb.append(String.format("  lit @0x%08x -> 0x%08x\n",e.getKey(),e.getValue()));

        // 2) for each function, see if it loads one of these literals (mov.l @(disp,pc) whose EA is
        //    in litToTarget). Those are the static callers of the action.
        sb.append("\n################ functions statically referencing an action (via literal pool) ################\n");
        for(Function f: iterable(fm.getFunctions(true))){
            Set<Long> hitTargets=new TreeSet<>();
            for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true))){
                String s=ins.toString();
                if(s.startsWith("mov.l @(0x")&&s.contains(",pc),")){
                    long ea=pcRelLit(ins);
                    if(litToTarget.containsKey(ea)) hitTargets.add(litToTarget.get(ea));
                }
            }
            if(!hitTargets.isEmpty()){
                // compare-key set
                Set<Long> ks=new TreeSet<>();
                for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true)))
                    if(ins.getMnemonicString().startsWith("cmp"))
                        for(int op=0;op<ins.getNumOperands();op++)
                            for(Object o:ins.getOpObjects(op))
                                if(o instanceof Scalar){ long v=((Scalar)o).getUnsignedValue(); if(v<=0x2c) ks.add(v); }
                sb.append(String.format("  %s@%s  refsActions=%s  cmpKeys=%s  size=%d callers=%d\n",
                          f.getName(),f.getEntryPoint(),hitTargets,ks,f.getBody().getNumAddresses(),
                          f.getCallingFunctions(mon).size()));
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/staticxref.txt");
        pw.print(sb.toString()); pw.close();
        println("StaticXref wrote staticxref.txt ("+sb.length()+" chars)");
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
