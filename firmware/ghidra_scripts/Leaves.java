import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class Leaves extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; Memory mem;
  void info(long fe){
    Function f=fm.getFunctionContaining(toAddr(fe));
    if(f==null){ if(getInstructionAt(toAddr(fe))==null) try{disassemble(toAddr(fe));}catch(Exception e){} try{f=createFunction(toAddr(fe),null);}catch(Exception e){} }
    println("\n###### 0x"+Long.toHexString(fe)+" "+(f==null?"NOFUNC":f.getName()+" body .."+f.getBody().getMaxAddress()));
    if(f==null) return;
    DecompileResults r=dec.decompileFunction(f,120,mon);
    if(r!=null&&r.decompileCompleted()) println(r.getDecompiledFunction().getC()); else println("(fail)");
    println("-- DISASM --");
    Address ad=f.getEntryPoint(), end=f.getBody().getMaxAddress();
    while(ad.compareTo(end)<=0){ Instruction ins=getInstructionAt(ad); if(ins==null){println("  "+ad+" (data)"); ad=ad.add(2); continue;} StringBuilder rs=new StringBuilder(); for(Reference rf:ins.getReferencesFrom()) rs.append(" ->"+rf.getToAddress()); println("  "+ad+"  "+ins+rs); ad=ins.getMaxAddress().add(1);}
    println("-- CALLERS --");
    for(Reference rf: currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint())){
      Function cf=fm.getFunctionContaining(rf.getFromAddress());
      println("   from "+rf.getFromAddress()+" ["+rf.getReferenceType()+"] in "+(cf==null?"?":cf.getName()));
    }
    long e=f.getEntryPoint().getOffset();
    for(long p=0x0C000000L;p<0x0C140000L;p+=4){ try{ if((mem.getInt(toAddr(p))&0xFFFFFFFFL)==e){ Function df=fm.getFunctionContaining(toAddr(p)); println("   POOL@0x"+Long.toHexString(p)+" in "+(df==null?"?":df.getName())); } }catch(Exception ex){} }
  }
  public void run() throws Exception {
    fm=currentProgram.getFunctionManager(); mem=currentProgram.getMemory();
    dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    // code 5,6,7 (tempo inc/dec/tap), 8 (click), 9 (fx)
    for(long a: new long[]{0x0C0C9FF4L,0x0C0C9FFAL,0x0C0CA010L,0x0C0CA026L,0x0C0CA03CL}) info(a);
  }
}
