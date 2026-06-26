// Classify.java — force-create + decompile the caller of FUN_0c0c9e60 (around 0x0c0c982a) and the
// arm jmp-target FUN_0c0c747c, to classify config-vs-strike. Also dump strings referenced in the
// caller's function (menu labels => config; none/velocity => strike).
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class Classify extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // find the function start that contains 0x0c0c982a (the bsr to dispatch). Scan back for a
        // standard prologue (sts.l pr,@-r15 = 4f22, often preceded by reg saves 2f.6).
        long callerStart=findFuncStart(0x0c0c982aL);
        sb.append("caller-of-dispatch function start ≈ 0x"+Long.toHexString(callerStart)+"\n");
        forceDecomp(callerStart, "CALLER_OF_DISPATCH", 120);

        // also force the dispatch's invoker one level up: who calls callerStart?
        sb.append("\n################ who calls the dispatch-caller? ################\n");
        Function cf=fm.getFunctionContaining(toAddr(callerStart));
        if(cf!=null) for(Function up: cf.getCallingFunctions(mon))
            sb.append("  <- "+up.getName()+"@"+up.getEntryPoint()+" size="+up.getBody().getNumAddresses()+"\n");

        // arm jmp-target FUN_0c0c747c
        sb.append("\n################ arm jmp-target 0x0c0c747c ################\n");
        long t=findFuncStart(0x0c0c747cL);
        forceDecomp(t, "ARM_JMP_TARGET", 50);

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/classify.txt");
        pw.print(sb.toString()); pw.close();
        println("Classify wrote classify.txt ("+sb.length()+" chars)");
    }

    long findFuncStart(long addr) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(addr));
        if(f!=null) return f.getEntryPoint().getOffset();
        // scan back up to 0x400 bytes for 4f22 (sts.l pr,@-r15)
        for(long a=addr; a>addr-0x400; a-=2){
            int wv;
            try{ wv=mem.getShort(toAddr(a))&0xFFFF; }catch(Exception e){ continue; }
            if(wv==0x4f22){
                // back up over any reg-save prologue (2f.6 / 4f.. movmu)
                long s=a;
                while(s-2>addr-0x420){
                    int p; try{ p=mem.getShort(toAddr(s-2))&0xFFFF; }catch(Exception e){ break; }
                    // reg saves: 2fX6 (mov.l rX,@-r15), or 48f0/movmu
                    if((p&0xF0FF)==0x2006 || p==0x48f0 || (p&0xFF00)==0x2f00) s-=2; else break;
                }
                return s;
            }
        }
        return addr;
    }
    void forceDecomp(long start,String label,int lim) throws Exception {
        if(getInstructionAt(toAddr(start))==null) disassemble(toAddr(start));
        Function f=fm.getFunctionContaining(toAddr(start));
        if(f==null){ try{ f=createFunction(toAddr(start),label); }catch(Exception e){ sb.append("  createFunction: "+e+"\n"); } }
        if(f==null){ sb.append("  could not create func @0x"+Long.toHexString(start)+"\n"); return; }
        sb.append("## "+f.getName()+"@"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ##\n");
        // strings referenced in this function
        sb.append("  referenced strings:");
        for(Instruction ins: iterable(lst.getInstructions(f.getBody(),true)))
            for(Reference r: ins.getReferencesFrom()){
                Data d=lst.getDefinedDataAt(r.getToAddress());
                if(d!=null && d.getValue() instanceof String) sb.append(" \""+d.getValue()+"\"");
            }
        sb.append("\n");
        DecompileResults r=dec.decompileFunction(f,90,mon);
        if(r!=null&&r.getDecompiledFunction()!=null){
            String c=r.getDecompiledFunction().getC(); String[] ls=c.split("\n");
            for(int i=0;i<Math.min(ls.length,lim);i++) sb.append(ls[i]).append("\n");
        }
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
