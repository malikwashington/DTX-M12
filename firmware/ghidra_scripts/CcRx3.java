// CcRx3.java — bind the dispatcher function (entry below 0x0C03CD40), find its callers
// (RX reachability), decompile the 0xB0/NoteOff common target 0x0C03D23E, and check whether
// the dispatcher is reached from a USB/serial MIDI INPUT parser. Also dump the realtime
// handler's caller chain (FUN_0c05ad18) to see if it shares the RX entry.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class CcRx3 extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; ReferenceManager rm; DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); rm=currentProgram.getReferenceManager();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // Find the prologue of the dispatcher: scan backwards from 0x0C03CD40 for 4F22.
        long disp=-1;
        for(long s=0x0C03CD40L; s>0x0C03C400L; s-=2){
            byte[] b=new byte[2]; currentProgram.getMemory().getBytes(toAddr(s),b);
            if((b[0]&0xFF)==0x4F&&(b[1]&0xFF)==0x22){
                if(getInstructionAt(toAddr(s))==null) disassemble(toAddr(s));
                Function f=null; try{f=createFunction(toAddr(s),null);}catch(Exception e){}
                if(f==null) f=fm.getFunctionContaining(toAddr(s));
                if(f!=null&&f.getBody().contains(toAddr(0x0C03CDBCL))){ disp=f.getEntryPoint().getOffset(); break; }
            }
        }
        sb.append("DISPATCHER entry = 0x"+Long.toHexString(disp)+"\n");
        if(disp>0){
            Function f=fm.getFunctionContaining(toAddr(disp));
            sb.append("size="+f.getBody().getNumAddresses()+"\nCALLERS:\n");
            climbCallers(disp, 0, new HashSet<>());
        }

        // Decompile the common CC/NoteOff target 0x0C03D23E (force-bind).
        bindAndDecomp(0x0C03D23EL,"CC/NoteOff common target");
        // Realtime handler caller chain.
        sb.append("\n=== FUN_0c044c08 (realtime FA/FB/FC) caller climb ===\n");
        climbCallers(0x0C044c08L, 0, new HashSet<>());
        // FUN_0c05ad18 decompile (the computed-call caller of realtime handler).
        bindAndDecomp(0x0C05ad18L,"realtime caller");

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/ccrx3.txt");
        pw.print(sb.toString()); pw.close();
        println("CcRx3 wrote ccrx3.txt ("+sb.length()+" chars)");
    }

    void climbCallers(long a, int depth, Set<Long> seen){
        if(depth>5||!seen.add(a)) return;
        Function f=fm.getFunctionContaining(toAddr(a));
        String pad="  ".repeat(depth+1);
        if(f==null){ sb.append(pad+"0x"+Long.toHexString(a)+" (no func)\n"); return; }
        int n=0;
        for(Reference r:rm.getReferencesTo(f.getEntryPoint())){
            Function cf=fm.getFunctionContaining(r.getFromAddress());
            sb.append(pad+r.getFromAddress()+" "+r.getReferenceType()
                +(cf!=null?(" in "+cf.getName()+"@"+cf.getEntryPoint()):" (no func)")+"\n");
            n++;
            if(cf!=null && r.getReferenceType().isCall())
                climbCallers(cf.getEntryPoint().getOffset(), depth+1, seen);
        }
        if(n==0) sb.append(pad+"(no callers — top of statically-reachable chain)\n");
    }

    void bindAndDecomp(long a,String tag){
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){
            for(long s=a; s>a-0x600; s-=2){
                byte[] b=new byte[2]; try{currentProgram.getMemory().getBytes(toAddr(s),b);}catch(Exception e){break;}
                if((b[0]&0xFF)==0x4F&&(b[1]&0xFF)==0x22){
                    if(getInstructionAt(toAddr(s))==null) disassemble(toAddr(s));
                    try{f=createFunction(toAddr(s),null);}catch(Exception e){}
                    if(f!=null&&f.getBody().contains(toAddr(a))) break; else f=null;
                }
            }
        }
        sb.append("\n\n########## ["+tag+"] 0x"+Long.toHexString(a)+" ");
        if(f==null){ sb.append("(could not bind)\n"); return;}
        sb.append(f.getName()+" @ "+f.getEntryPoint()+" ##########\n");
        DecompileResults r=dec.decompileFunction(f,90,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
    }
}
