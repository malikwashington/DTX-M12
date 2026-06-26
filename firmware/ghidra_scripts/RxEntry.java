// RxEntry.java — determine whether the MIDI-RX channel dispatcher (switchD_0c03cdbc) and
// the realtime handler FUN_0c044c08 are statically reachable from a real input parser, or
// whether (like the pad path) they hang off boot-initialized RAM dispatch cells.
// Approach: bind 0x0C05A578 (the UNCONDITIONAL_CALL site into FUN_0c05ad18) into a function,
// climb its callers; and find what references the dispatcher entry. Also locate the function
// that contains the switch dispatcher (its prologue) by scanning a wider window, and list its
// in-refs (callers). Report each chain's TOP and whether the top is RAM-gated.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class RxEntry extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; ReferenceManager rm; DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); rm=currentProgram.getReferenceManager();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // 1) Bind the dispatcher function: scan a WIDE backward window from 0x0C03CD40.
        long dispEntry=findEnclosing(0x0C03CDBCL);
        sb.append("dispatcher switchD_0c03cdbc enclosing entry = 0x"+Long.toHexString(dispEntry)+"\n");
        if(dispEntry>0){ sb.append("CALLERS of dispatcher func:\n"); listRefs(dispEntry,2); }

        // 2) Bind 0x0C05A578 (call into realtime caller) and climb.
        long e2=findEnclosing(0x0C05A578L);
        sb.append("\n0x0C05A578 enclosing entry = 0x"+Long.toHexString(e2)+"\n");
        if(e2>0){ sb.append("CALLERS of that func:\n"); listRefs(e2,3); }

        // 3) Who references the USB-MIDI input? Look for the function that calls FUN_0c05ad18
        // and climb to a top with no callers (RAM-gated) or a named ISR.
        sb.append("\n=== climb from FUN_0c05ad18 ===\n");
        listRefs(0x0C05ad18L,4);

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/rxentry.txt");
        pw.print(sb.toString()); pw.close();
        println("RxEntry wrote rxentry.txt ("+sb.length()+" chars)");
    }

    long findEnclosing(long a){
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f!=null) return f.getEntryPoint().getOffset();
        for(long s=a; s>a-0x1200; s-=2){
            byte[] b=new byte[2]; try{currentProgram.getMemory().getBytes(toAddr(s),b);}catch(Exception e){break;}
            if((b[0]&0xFF)==0x4F&&(b[1]&0xFF)==0x22){
                if(getInstructionAt(toAddr(s))==null) disassemble(toAddr(s));
                try{f=createFunction(toAddr(s),null);}catch(Exception e){}
                if(f!=null&&f.getBody().contains(toAddr(a))) return f.getEntryPoint().getOffset();
                f=null;
            }
        }
        return -1;
    }

    void listRefs(long a,int depth){ listRefs(a,depth,new HashSet<>(),0); }
    void listRefs(long a,int maxDepth,Set<Long> seen,int d){
        if(d>maxDepth||!seen.add(a)) return;
        Function f=fm.getFunctionContaining(toAddr(a));
        String pad="  ".repeat(d+1);
        if(f==null){ sb.append(pad+"0x"+Long.toHexString(a)+" (no enclosing func — RAM/ISR entry?)\n"); return; }
        int n=0;
        for(Reference r:rm.getReferencesTo(f.getEntryPoint())){
            Function cf=fm.getFunctionContaining(r.getFromAddress());
            sb.append(pad+r.getFromAddress()+" "+r.getReferenceType()
                +(cf!=null?(" in "+cf.getName()+"@"+cf.getEntryPoint()):" (NO FUNC)")+"\n");
            n++;
            if(cf!=null && (r.getReferenceType().isCall()||r.getReferenceType().isJump()))
                listRefs(cf.getEntryPoint().getOffset(),maxDepth,seen,d+1);
        }
        if(n==0) sb.append(pad+"^ TOP: no static callers (RAM-table/boot-gated or ISR vector)\n");
    }
}
