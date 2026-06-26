import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class Tempo extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; Memory mem;
  void info(long fe, boolean disasm){
    Function f=fm.getFunctionContaining(toAddr(fe));
    if(f==null){ if(getInstructionAt(toAddr(fe))==null) try{disassemble(toAddr(fe));}catch(Exception e){} try{f=createFunction(toAddr(fe),null);}catch(Exception e){} }
    println("\n###### 0x"+Long.toHexString(fe)+" in "+(f==null?"NO FUNC":(f.getName()+" @"+f.getEntryPoint()+" ..0x"+Long.toHexString(f.getBody().getMaxAddress().getOffset()))));
    if(f==null) return;
    DecompileResults r=dec.decompileFunction(f,90,mon);
    if(r!=null&&r.decompileCompleted()) println(r.getDecompiledFunction().getC());
    else println("(decomp fail)");
    if(disasm){
      println("---- DISASM ----");
      Address ad=f.getEntryPoint(), end=f.getBody().getMaxAddress();
      while(ad.compareTo(end)<=0){ Instruction ins=getInstructionAt(ad); if(ins==null){println("  "+ad+" (data)"); ad=ad.add(2); continue;} StringBuilder rs=new StringBuilder(); for(Reference rf:ins.getReferencesFrom()) rs.append(" ->"+rf.getToAddress()); println("  "+ad+"  "+ins+rs); ad=ins.getMaxAddress().add(1);} }
    println("---- CALLERS ----");
    for(Reference rf: currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint())){
      Function cf=fm.getFunctionContaining(rf.getFromAddress());
      println("   from "+rf.getFromAddress()+" ["+rf.getReferenceType()+"] in "+(cf==null?"?":cf.getName()));
    }
  }
  public void run() throws Exception {
    fm=currentProgram.getFunctionManager(); mem=currentProgram.getMemory();
    dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    String[] which=getScriptArgs();
    long[] addrs = {0x0C03F038L, 0x0C049C96L, 0x0C042C70L, 0x0C043B98L};
    for(long a: addrs) info(a, a==0x0C03F038L || a==0x0C049C96L);
  }
}
