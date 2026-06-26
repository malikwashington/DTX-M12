import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class Anchor2 extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; Memory mem;
  void dumpFn(long fe){
    Function f=fm.getFunctionContaining(toAddr(fe));
    if(f==null){ if(getInstructionAt(toAddr(fe))==null) try{disassemble(toAddr(fe));}catch(Exception e){} try{f=createFunction(toAddr(fe),null);}catch(Exception e){} }
    println("\n###### FUNC 0x"+Long.toHexString(fe)+" -> "+(f==null?"NO FUNC":(f.getName()+" body "+f.getBody().getMinAddress()+".."+f.getBody().getMaxAddress())));
    if(f==null) return;
    DecompileResults r=dec.decompileFunction(f,60,mon);
    if(r!=null&&r.decompileCompleted()) println(r.getDecompiledFunction().getC());
    else println("(decomp fail)");
    println("---- CALLERS ----");
    for(Reference rf: currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint())){
      Function cf=fm.getFunctionContaining(rf.getFromAddress());
      println("   from "+rf.getFromAddress()+" ["+rf.getReferenceType()+"] in "+(cf==null?"?":cf.getName()));
    }
    long e=f.getEntryPoint().getOffset();
    for(long p=0x0C000000L;p<0x0C140000L;p+=4){
      try{ long w=mem.getInt(toAddr(p))&0xFFFFFFFFL;
        if(w==e){ Function df=fm.getFunctionContaining(toAddr(p)); println("   POOL@0x"+Long.toHexString(p)+" in "+(df==null?"?":df.getName())); }
      }catch(Exception ex){}
    }
  }
  public void run() throws Exception {
    fm=currentProgram.getFunctionManager(); mem=currentProgram.getMemory();
    dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    // The block holding anchor 0x43792: find its head. Force-disasm at a few candidate starts.
    for(long c: new long[]{0x0C0436E0L,0x0C043700L,0x0C043720L,0x0C043744L,0x0C043786L}){
      Function f=fm.getFunctionContaining(toAddr(c));
      println("containing 0x"+Long.toHexString(c)+" = "+(f==null?"none":f.getName()+"@"+f.getEntryPoint()));
    }
    // tail-call target with r4=0x27, and the thunk's callee
    dumpFn(0x0C045154L);  // tail-jump target from anchor block (the 'do tempo action'?)
    dumpFn(0x0C042D56L);  // callee of the 0x42b6a thunk
  }
}
