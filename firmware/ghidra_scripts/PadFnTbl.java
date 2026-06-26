// PadFnTbl.java — find the per-strike pad-function HANDLER TABLE (indexed by function code 0..N)
// among the static code-pointer arrays, and resolve the strike dispatcher that indexes it.
//  Key idea: the strike pad-function dispatcher does `mov.l @(r0,rT),rM ; jsr/jmp @rM` where rT is
//  a table base (a static code-ptr array in code.bin) and r0 = code*4. We already enumerated such
//  indexed-jump sites (PadFuncTable.java). Now: for each indexed-jump site, resolve the table base
//  literal, dump the table, and check if its entries are pad-function ACTIONS (transport/tempo/
//  click/kit) vs UI. The one with action entries indexed by 0..N = the strike dispatcher.
//  Also: directly search static code-ptr arrays whose ENTRIES include the seq/transport actions
//  {FUN_0c0440de,FUN_0c044022,FUN_0c0440c6,...} AND kit/click handlers, with small stride.
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

public class PadFnTbl extends GhidraScript {
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

        // indexed-jump sites: mov.l @(r0,rT),rM ; (jmp|jsr) @rM, where the PREVIOUS-prev instr
        // loads rT from a pc-relative literal = the table base. Resolve + dump.
        sb.append("################ indexed dispatch sites + resolved table contents ################\n");
        for(Function f: iterable(fm.getFunctions(true))){
            Instruction prev=null, prev2=null;
            for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true))){
                String m=ins.getMnemonicString();
                if((m.equals("jmp")||m.equals("jsr")) && prev!=null && prev.toString().startsWith("mov.l @(r0,")){
                    // find the table base reg in prev (e.g. "mov.l @(r0,r1),r0" -> base reg r1)
                    String ps=prev.toString();
                    String baseReg=ps.replaceAll(".*@\\(r0,(r\\d+)\\).*","$1");
                    // search backward in this function for `mov.l @(disp,pc),baseReg`
                    long tableBase=findBaseLit(f, prev.getAddress().getOffset(), baseReg);
                    if(tableBase!=0){
                        // dump table while entries are code ptrs
                        int n=0; while(isCodePtr(u32(tableBase+n*4L)) && n<0x30) n++;
                        if(n>=3){
                            sb.append(String.format("\n  %s@%s : table base 0x%08x (%d entries):\n",
                                      f.getName(),prev.getAddress(),tableBase,n));
                            int act=0;
                            for(int i=0;i<n;i++){ long v=u32(tableBase+i*4L); Function tf=fm.getFunctionContaining(toAddr(v));
                                String nm=tf!=null?tf.getName():"?";
                                boolean isAct=isAction(v);
                                if(isAct) act++;
                                sb.append(String.format("    [%2d] 0x%08x %s%s\n",i,v,nm,isAct?"  <ACTION>":""));
                            }
                            sb.append("    -> actionEntries="+act+(act>=2?"  <<< STRIKE PAD-FUNC TABLE CANDIDATE":"")+"\n");
                        }
                    }
                }
                prev2=prev; prev=ins;
            }
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/padfntbl.txt");
        pw.print(sb.toString()); pw.close();
        println("PadFnTbl wrote padfntbl.txt ("+sb.length()+" chars)");
    }
    Set<Long> ACT=new HashSet<>(Arrays.asList(0x0c0440deL,0x0c044022L,0x0c0440c6L,0x0c043ed6L,0x0c043ffcL,
        0x0c0440daL,0x0c04f306L,0x0c04dda2L,0x0c04dca4L));
    boolean isAction(long v){ return ACT.contains(v); }
    long findBaseLit(Function f, long beforeAddr, String reg) {
        long best=0;
        for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true))){
            if(ins.getAddress().getOffset()>=beforeAddr) break;
            String s=ins.toString();
            if(s.startsWith("mov.l @(0x")&&s.endsWith(","+reg)&&s.contains(",pc),")){
                long ea=pcRelLit(ins); if(ea!=0) best=u32(ea);
            }
        }
        return best;
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
