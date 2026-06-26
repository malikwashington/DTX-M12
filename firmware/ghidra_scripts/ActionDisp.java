// ActionDisp.java — analyze FUN_0c04dda2 as the PER-STRIKE pad-function ACTION dispatcher.
//  1) full caller chain up to the trigger/strike entry (region <0x0C060000).
//  2) the per-function handler table(s): PTR_DAT_0c04de94 (flags), PTR_DAT_0c04de98 (param ptr
//     table), PTR_DAT_0c04e098 (HANDLER table, indexed code*0x10) — resolve their RAM bases and
//     dump the entries for codes 0x00..0x10 (esp. 0x01 = kit increment): does each slot hold a
//     function pointer the dispatcher calls? That table slot is the clean hook for feature 6.
//  3) decompile a couple of the ACTION handlers (FUN_0c04e1f2 etc.) to confirm they are state
//     changers (tempo/kit/click), not redraws.
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

public class ActionDisp extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // 1) caller chain
        sb.append("################ FUN_0c04dda2 caller chain (-> trigger/strike?) ################\n");
        Function f=fm.getFunctionContaining(toAddr(0x0c04dda2L));
        Set<Long> seen=new HashSet<>();
        chain(f,4,seen);
        sb.append("\n-- immediate caller FUN_0c04dca4 decompile --\n");
        decomp(0x0c04dca4L,80);

        // 2) resolve the three table literals by reading the function's pool. We disasm FUN_0c04dda2
        // and capture each `mov.l @(disp,pc),rN` literal value, labeling de94/de98/e098.
        sb.append("\n################ per-function tables in FUN_0c04dda2 ################\n");
        Map<String,Long> lits=new LinkedHashMap<>();
        for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true))){
            String s=ins.toString();
            if(s.startsWith("mov.l @(0x")&&s.contains(",pc),")){
                long ea=pcRelLit(ins); if(ea==0) continue;
                long v=mem.getInt(toAddr(ea))&0xFFFFFFFFL;
                sb.append(String.format("  lit @0x%08x = 0x%08x  (loaded @%s)\n",ea,v,ins.getAddress()));
                lits.put("0x"+Long.toHexString(ea),v);
            }
        }
        // dump handler table PTR_DAT_0c04e098 (RAM base) entries [code*0x10] for codes 0..0x10.
        // The literal at 0x0c04e098 (in pool) holds the RAM base. find it:
        Long e098 = lits.get("0xc04e098");
        Long de98 = lits.get("0xc04de98");
        Long de94 = lits.get("0xc04de94");
        sb.append("\n  resolved bases: de94(flags)="+hx(de94)+" de98(paramPtrTbl)="+hx(de98)+" e098(handlerTbl)="+hx(e098)+"\n");
        for(Long base : new Long[]{e098,de98,de94}){
            if(base==null) continue;
            sb.append("\n  -- table @0x"+Long.toHexString(base)+" entries [stride 0x10], codes 0x00..0x10 --\n");
            for(int code=0;code<=0x10;code++){
                long ent=base + (long)code*0x10;
                StringBuilder row=new StringBuilder();
                for(int w=0;w<0x10;w+=4){
                    long v; try{ v=mem.getInt(toAddr(ent+w))&0xFFFFFFFFL; }catch(Exception e){ v=-1; }
                    row.append(String.format(" %08x",v));
                    if(w==0){ Function tf=(v>=0x0C000000L&&v<0x0C140000L)?fm.getFunctionContaining(toAddr(v)):null;
                              if(tf!=null) row.append("("+tf.getName()+")"); }
                }
                sb.append(String.format("    code 0x%02x @0x%08x:%s\n",code,ent,row.toString()));
            }
        }

        // 3) action handler decompiles (confirm state-changers)
        sb.append("\n################ action handlers (state-changers?) ################\n");
        long[] handlers={0x0c04e1f2L,0x0c04e2c6L,0x0c04e378L,0x0c04e3d2L,0x0c04dfacL,0x0c04dfd6L};
        for(long h: handlers){ sb.append("\n"); decomp(h,40); }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/actiondisp.txt");
        pw.print(sb.toString()); pw.close();
        println("ActionDisp wrote actiondisp.txt ("+sb.length()+" chars)");
    }
    String hx(Long v){ return v==null?"null":("0x"+Long.toHexString(v)); }
    void chain(Function f,int depth,Set<Long> seen) throws Exception {
        if(f==null||depth<0) return;
        for(Function c: f.getCallingFunctions(mon)){
            long e=c.getEntryPoint().getOffset();
            sb.append("  ".repeat(4-depth)+"<- "+c.getName()+"@"+c.getEntryPoint()+" size="+c.getBody().getNumAddresses()+"\n");
            if(seen.add(e)) chain(c,depth-1,seen);
        }
    }
    void decomp(long a,int lim) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func @0x"+Long.toHexString(a)+"\n"); return; }
        sb.append("## "+f.getName()+"@"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ##\n");
        DecompileResults r=dec.decompileFunction(f,90,mon);
        if(r!=null&&r.getDecompiledFunction()!=null){ String[] ls=r.getDecompiledFunction().getC().split("\n");
            for(int i=0;i<Math.min(ls.length,lim);i++) sb.append(ls[i]).append("\n"); }
    }
    long pcRelLit(Instruction ins){
        for(int op=0;op<ins.getNumOperands();op++)
            for(Object o:ins.getOpObjects(op))
                if(o instanceof Scalar){ long disp=((Scalar)o).getUnsignedValue();
                    if(disp<0x800) return (ins.getAddress().getOffset()&~3L)+4+disp*4; }
        return 0;
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
