// PadTable.java — examine the pad/seq action dispatch TABLES (DATA refs to FUN_0c044022 etc.)
// and the dispatcher FUN_0c04f306 that indexes them. Goal: find the pad-function action table
// where a code -> handler, so feature-6 can add a "stop all sounds" handler entry, OR find the
// switch/dispatch that selects it. Dump the tables (function-pointer arrays) around the DATA refs.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class PadTable extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        // dump function-pointer table windows around each DATA ref to the seq toggles
        long[] tableHints={0x0c04f334L,0x0c04f3d4L,0x0c04f86cL,0x0c05f0a0L,0x0c05187cL,0x0c05c1bcL};
        for (long t: tableHints){
            sb.append("\n################ table window around 0x"+Long.toHexString(t)+" ################\n");
            // scan back to a likely table start (align 4, find run of code pointers) then print
            long start=(t & ~3L)-0x40, end=(t&~3L)+0x60;
            for (long a=start;a<end;a+=4){
                try{ long v=mem.getInt(toAddr(a))&0xFFFFFFFFL;
                    Function tf=(v>=0x0C000000L&&v<=0x0C140000L)?fm.getFunctionContaining(toAddr(v)):null;
                    String mark=(a==t)?"  <== DATA-ref":"";
                    sb.append(String.format("  0x%08x: 0x%08x %s%s\n",a,v,tf!=null?("("+tf.getName()+")"):"",mark));
                }catch(Exception e){}
            }
        }

        // decompile FUN_0c04f306 and the funcs that read these tables (the dispatchers)
        sb.append("\n################ dispatcher FUN_0c04f306 + table readers ################\n");
        decomp(0x0c04f306L);
        // who references these tables as the base of an indexed load? list funcs referencing the
        // table region 0x0c04f300-0x0c04f900
        sb.append("\n-- funcs referencing 0x0c04f300..0x0c04f900 (table base loaders) --\n");
        Set<Long> fns=new LinkedHashSet<>();
        for (long a=0x0c04f300L;a<0x0c04f900L;a+=2){
            for (Reference r: rm.getReferencesTo(toAddr(a))){
                Function f=fm.getFunctionContaining(r.getFromAddress());
                if(f!=null) fns.add(f.getEntryPoint().getOffset());
            }
        }
        for(long fn:fns) sb.append("  0x"+Long.toHexString(fn)+"\n");

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/padtable.txt");
        pw.print(sb.toString()); pw.close();
        println("PadTable wrote padtable.txt ("+sb.length()+" chars)");
    }
    void decomp(long a) throws Exception {
        Function f=fm.getFunctionContaining(toAddr(a));
        if(f==null){ sb.append("  no func @0x"+Long.toHexString(a)+"\n"); return; }
        sb.append("## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+"\n");
        DecompileResults r=dec.decompileFunction(f,120,mon);
        if(r!=null&&r.getDecompiledFunction()!=null) sb.append(r.getDecompiledFunction().getC());
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
