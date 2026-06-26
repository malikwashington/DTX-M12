import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class R2 extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm;
  void info(long fe){
    Function f=fm.getFunctionContaining(toAddr(fe));
    if(f==null){ if(getInstructionAt(toAddr(fe))==null) try{disassemble(toAddr(fe));}catch(Exception e){} try{f=createFunction(toAddr(fe),null);}catch(Exception e){} }
    println("\n###### probe 0x"+Long.toHexString(fe)+" -> "+(f==null?"NOFUNC":f.getName()+" @"+f.getEntryPoint()+" .."+f.getBody().getMaxAddress()));
    if(f==null) return;
    DecompileResults r=dec.decompileFunction(f,120,mon);
    if(r!=null&&r.decompileCompleted()) println(r.getDecompiledFunction().getC()); else println("(fail)");
    println("-- CALLERS --");
    for(Reference rf: currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint())){
      Function cf=fm.getFunctionContaining(rf.getFromAddress());
      println("   from "+rf.getFromAddress()+" ["+rf.getReferenceType()+"] in "+(cf==null?"?":cf.getName()));
    }
  }
  public void run() throws Exception {
    fm=currentProgram.getFunctionManager();
    dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    for(long a: new long[]{0x0C045000L,0x0C045040L,0x0C045080L,0x0C044980L,0x0C0449C0L}) info(a);
  }
}
