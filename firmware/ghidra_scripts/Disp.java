import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class Disp extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm;
  void info(long fe){
    Function f=fm.getFunctionContaining(toAddr(fe));
    if(f==null){ if(getInstructionAt(toAddr(fe))==null) try{disassemble(toAddr(fe));}catch(Exception e){} try{f=createFunction(toAddr(fe),null);}catch(Exception e){} }
    println("\n###### 0x"+Long.toHexString(fe)+" "+(f==null?"NOFUNC":f.getName()+" @"+f.getEntryPoint()));
    if(f==null) return;
    DecompileResults r=dec.decompileFunction(f,120,mon);
    if(r!=null&&r.decompileCompleted()) println(r.getDecompiledFunction().getC()); else println("(fail)");
  }
  public void run() throws Exception {
    fm=currentProgram.getFunctionManager();
    dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    info(0x0C0C9E60L);
  }
}
