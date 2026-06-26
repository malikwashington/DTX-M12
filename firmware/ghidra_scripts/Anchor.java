import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class Anchor extends GhidraScript {
  public void run() throws Exception {
    FunctionManager fm = currentProgram.getFunctionManager();
    Memory mem = currentProgram.getMemory();
    DecompInterface dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
    ConsoleTaskMonitor mon=new ConsoleTaskMonitor();
    long[] anchors={0x0C043792L, 0x0C044CB6L};
    Set<Long> seen=new HashSet<>();
    for(long an: anchors){
      Function f=fm.getFunctionContaining(toAddr(an));
      println("\n###### anchor 0x"+Long.toHexString(an)+" -> "+(f==null?"NO FUNC":("FUNC "+f.getName()+" @ "+f.getEntryPoint()+"  body "+f.getBody().getMinAddress()+".."+f.getBody().getMaxAddress())));
      if(f==null) continue;
      long fe=f.getEntryPoint().getOffset();
      if(seen.add(fe)){
        DecompileResults r=dec.decompileFunction(f,60,mon);
        if(r!=null&&r.decompileCompleted()) println(r.getDecompiledFunction().getC());
        else println("(decomp fail)");
        // CALLERS via Ghidra refs
        println("---- CALLERS of "+f.getName()+" @"+f.getEntryPoint()+" ----");
        for(Reference rf: currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint())){
          Function cf=fm.getFunctionContaining(rf.getFromAddress());
          println("   from "+rf.getFromAddress()+" ["+rf.getReferenceType()+"] in "+(cf==null?"?":cf.getName()+"@"+cf.getEntryPoint()));
        }
        // literal-pool callers: scan whole code for word==entry
        println("---- literal-pool refs to 0x"+Long.toHexString(fe)+" ----");
        for(long p=0x0C000000L;p<0x0C140000L;p+=4){
          long w=mem.getInt(toAddr(p))&0xFFFFFFFFL;
          if(w==fe){
            Function df=fm.getFunctionContaining(toAddr(p));
            println("   pool@0x"+Long.toHexString(p)+" in "+(df==null?"?":df.getName()+"@"+df.getEntryPoint()));
          }
        }
      }
    }
  }
}
