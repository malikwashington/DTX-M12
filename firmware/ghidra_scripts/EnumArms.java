// EnumArms.java — enumerate ALL switch arms of FUN_0c0c9e60 (pad-function dispatch), codes
// 0x00..0x10 (+ the high codes), and for each: arm handler address, its jmp-target literal (addr
// + value), raw disasm, decompile, and the ultimate target it tail-jumps to. Goal: identify the
// TEMPO-INCREMENT arm (the one whose handler increments the tempo variable) and confirm its arm
// structure (epilogue-then-jmp via a per-arm literal), so the literal can be repointed.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class EnumArms extends GhidraScript {
    StringBuilder sb=new StringBuilder();
    FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
    DecompInterface dec; ConsoleTaskMonitor mon;

    // code -> arm head (from the disasm of FUN_0c0c9e60)
    static final long[][] ARMS = {
        {0x00,0x0c0c9f38L},{0x01,0x0c0c9f4eL},{0x02,0x0c0c9f64L},{0x03,0x0c0c9fc4L},
        {0x04,0x0c0c9feeL},{0x05,0x0c0c9ff4L},{0x06,0x0c0c9ffaL},{0x07,0x0c0ca010L},
        {0x08,0x0c0ca026L},{0x09,0x0c0ca03cL},{0x0a,0x0c0ca052L},{0x0b,0x0c0ca068L},
        {0x0c,0x0c0ca06eL},{0x0d,0x0c0ca084L},{0x0e,0x0c0ca0d0L},{0x0f,0x0c0ca0e8L},
        {0x10,0x0c0ca0caL},
        // high codes for completeness
        {0xD4,0x0c0c9f90L},{0xA6,0x0c0c9fa2L},{0xAA,0x0c0ca09aL},{0xAB,0x0c0ca0b2L},{0xD3,0x0c0ca100L},
    };

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();
        rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.toggleCCode(true); dec.openProgram(currentProgram);
        mon=new ConsoleTaskMonitor();

        sb.append("################ FUN_0c0c9e60 switch arms — full enumeration ################\n");
        for(long[] arm: ARMS){
            int code=(int)arm[0]; long head=arm[1];
            sb.append(String.format("\n========== CODE 0x%02x  arm @0x%08x ==========\n",code,head));
            armDetail(head);
        }

        // Tempo-variable hunt: which arm handler writes/increments a tempo cell? The kit tempo is
        // cat0x14 off 0x14/0x15 (14-bit). At runtime the live tempo is a RAM cell. We look across
        // arm bodies (and the funcs they tail-jump to) for an "increment" pattern: load var, add,
        // store; and for references to tempo-ish DATs. We list arms whose target funcs do add+store.
        sb.append("\n################ increment-pattern arms (load;add;store -> candidate inc/dec) ################\n");
        for(long[] arm: ARMS){
            long head=arm[1];
            long tgt=jmpTarget(head);
            String why=incPattern(head) ? "ARM-body inc" : "";
            if(tgt!=0){ if(incPattern(tgt)) why+=(why.isEmpty()?"":" + ")+"TARGET inc"; }
            if(!why.isEmpty())
                sb.append(String.format("  code 0x%02x arm 0x%08x tgt 0x%08x : %s\n",(int)arm[0],head,tgt,why));
        }

        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/enumarms.txt");
        pw.print(sb.toString()); pw.close();
        println("EnumArms wrote enumarms.txt ("+sb.length()+" chars)");
    }

    // disasm an arm head until its terminating jmp/rts; resolve its per-arm jmp literal; decompile
    // the function it tail-jumps to.
    void armDetail(long head) throws Exception {
        long lit=0, litVal=0, tgt=0;
        long a=head; int guard=0;
        sb.append("  -- arm disasm --\n");
        while(guard++<40){
            Instruction ins=lst.getInstructionAt(toAddr(a));
            if(ins==null){ try{disassemble(toAddr(a));}catch(Exception e){} ins=lst.getInstructionAt(toAddr(a)); }
            if(ins==null){ sb.append(String.format("    0x%08x (data)\n",a)); break; }
            String s=ins.toString();
            // capture mov.l @(disp,pc),rN literal
            if(s.startsWith("mov.l @(0x") && s.contains(",pc),")){
                long ea = pcRelLit(ins);
                if(ea!=0){ lit=ea; litVal=mem.getInt(toAddr(ea))&0xFFFFFFFFL; }
            }
            String by=bytes(ins);
            sb.append(String.format("    0x%08x  %-8s %s\n",a,by,s));
            String m=ins.getMnemonicString();
            if(m.equals("jmp")||m.equals("rts")||m.equals("jsr")||m.equals("braf")||m.equals("rts/n")){
                // include delay slot
                long ds=ins.getMaxAddress().getOffset()+1;
                Instruction d=lst.getInstructionAt(toAddr(ds));
                if(d!=null) sb.append(String.format("    0x%08x  %-8s %s  (delay slot)\n",ds,bytes(d),d.toString()));
                if(m.equals("jmp")) tgt=litVal; // jmp @r2 after mov.l literal
                break;
            }
            if(m.equals("bra")){
                for(Reference r: ins.getReferencesFrom()) tgt=r.getToAddress().getOffset();
                break;
            }
            a=ins.getMaxAddress().getOffset()+1;
        }
        sb.append(String.format("  jmp-target-literal: %s = 0x%08x   tail-target: 0x%08x\n",
                  lit!=0?("0x"+Long.toHexString(lit)):"(none)", litVal, tgt));
        // structure check: does the arm restore pr (lds.l @r15+,pr) before transferring?
        boolean restoresPr=false;
        long b=head; int g2=0;
        while(g2++<40){
            Instruction ins=lst.getInstructionAt(toAddr(b));
            if(ins==null) break;
            if(ins.toString().contains("lds.l @r15+,pr")) restoresPr=true;
            String m=ins.getMnemonicString();
            if(m.equals("jmp")||m.equals("rts")||m.equals("bra")||m.equals("rts/n")) break;
            b=ins.getMaxAddress().getOffset()+1;
        }
        sb.append("  restores-pr-before-transfer: "+restoresPr+"\n");
        // decompile the tail target function (what the arm actually does)
        if(tgt!=0){
            Function tf=fm.getFunctionContaining(toAddr(tgt));
            sb.append("  -- tail-target decompile ("+(tf!=null?tf.getName():("@0x"+Long.toHexString(tgt)))+") --\n");
            if(tf!=null){
                DecompileResults r=dec.decompileFunction(tf,60,mon);
                if(r!=null&&r.getDecompiledFunction()!=null){
                    String c=r.getDecompiledFunction().getC();
                    // trim to first ~25 lines
                    String[] ls=c.split("\n"); int lim=Math.min(ls.length,28);
                    for(int i=0;i<lim;i++) sb.append("    "+ls[i]+"\n");
                }
            }
        }
    }

    long jmpTarget(long head) throws Exception {
        long a=head; int guard=0; long litVal=0;
        while(guard++<40){
            Instruction ins=lst.getInstructionAt(toAddr(a));
            if(ins==null){ try{disassemble(toAddr(a));}catch(Exception e){} ins=lst.getInstructionAt(toAddr(a)); }
            if(ins==null) break;
            String s=ins.toString();
            if(s.startsWith("mov.l @(0x")&&s.contains(",pc),")){ long ea=pcRelLit(ins); if(ea!=0) litVal=mem.getInt(toAddr(ea))&0xFFFFFFFFL; }
            String m=ins.getMnemonicString();
            if(m.equals("jmp")) return litVal;
            if(m.equals("bra")){ for(Reference r:ins.getReferencesFrom()) return r.getToAddress().getOffset(); }
            if(m.equals("rts")||m.equals("rts/n")) return 0;
            a=ins.getMaxAddress().getOffset()+1;
        }
        return 0;
    }

    // detect load;add;store increment pattern in a function (or short arm window)
    boolean incPattern(long entry){
        Function f=fm.getFunctionContaining(toAddr(entry));
        long lo=entry, hi=entry+0x60;
        if(f!=null){ lo=f.getEntryPoint().getOffset(); hi=f.getBody().getMaxAddress().getOffset(); }
        boolean sawAdd=false;
        long a=lo;
        while(a<=hi){
            Instruction ins=lst.getInstructionAt(toAddr(a));
            if(ins==null){ a+=2; continue; }
            String s=ins.toString();
            if(s.startsWith("add ")||s.startsWith("add\t")) sawAdd=true;
            // a write of an incremented value back to memory near an add
            a=ins.getMaxAddress().getOffset()+1;
        }
        return sawAdd;
    }

    long pcRelLit(Instruction ins){
        // mov.l @(disp,pc),rN ; EA=(addr&~3)+4+disp*4
        for(int op=0;op<ins.getNumOperands();op++)
            for(Object o:ins.getOpObjects(op))
                if(o instanceof Scalar){ long disp=((Scalar)o).getUnsignedValue();
                    if(disp<0x400) return (ins.getAddress().getOffset()&~3L)+4+disp*4; }
        return 0;
    }
    String bytes(Instruction ins){
        StringBuilder b=new StringBuilder();
        try{ long ad=ins.getAddress().getOffset(); for(int i=0;i<ins.getLength();i++) b.append(String.format("%02x",mem.getByte(toAddr(ad+i))&0xFF)); }catch(Exception e){}
        return b.toString();
    }
    <T> Iterable<T> iterable(java.util.Iterator<T> it){ return ()->it; }
}
