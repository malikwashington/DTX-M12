import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class Strike2 extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; Memory mem;
  void info(long fe){
    Function f=fm.getFunctionContaining(toAddr(fe));
    if(f==null){ if(getInstructionAt(toAddr(fe))==null) try{disassemble(toAddr(fe));}catch(Exception e){} try{f=createFunction(toAddr(fe),null);}catch(Exception e){} }
    println("\n###### 0x"+Long.toHexString(fe)+" in "+(f==null?"NO FUNC":(f.getName()+" @"+f.getEntryPoint()+" ..0x"+Long.toHexString(f.getBody().getMaxAddress().getOffset()))));
    if(f==null) return;
    DecompileResults r=dec.decompileFunction(f,120,mon);
    if(r!=null&&r.decompileCompleted()) println(r.getDecompiledFunction().getC());
    else println("(decomp fail)");
    println("---- CALLERS ----");
    for(Reference rf: currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint())){
      Function cf=fm.getFunctionContaining(rf.getFromAddress());
      println("   from "+rf.getFromAddress()+" ["+rf.getReferenceType()+"] in "+(cf==null?"?":cf.getName()));
    }
  }
  public void run() throws Exception {
    fm=currentProgram.getFunctionManager(); mem=currentProgram.getMemory();
    dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    info(0x0C050200L);   // caller of pad-strike entry FUN_0c04f306
    info(0x0C043684L);   // caller of FUN_0c042c68 (pattern/tempo)
  }
}
