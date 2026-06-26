// F6Emu.java — emulate the feature-6 patch.
//  PART 1: prove the CLEAN mute primitive MUTE_ENTRY @0x0C048956 is callable with r4=flags and
//          executes (a) the 100-slot voice-clear loop (flag &2) and (b) the transport-stop
//          (flag &4 -> FUN_0c048b98 == FUN_0c0440c6) and (c) all-note-off (&0x40). We invoke it
//          with r4=0x46 and trace: confirm the loop runs ~100 iters and the &4/&0x40 blocks hit.
//          Boot-pointer leaf calls (jsr @r12/@r10/@r2 via literal pool funcs that themselves do
//          RAM-table indirects) are STUBBED to return, like Feat7Emu.
//  PART 2: emulate the TRAMPOLINE we will place at 0x0C13E000:
//             mov.l @(disp,pc), r0   ; r0 = 0x46  (or load from pool)
//             mov #0x46, r4          ; flags
//             mov.l @(disp,pc), r2   ; r2 = 0x0C048956
//             jmp @r2                ; tail-call the mute primitive (its rts returns to OUR caller)
//             nop
//             .long 0x0C048956
//          We assemble these bytes, write them at 0x0C13E000 in emu memory, set pr=RET_SENTINEL,
//          jump to 0x0C13E000, and confirm it reaches MUTE_ENTRY with r4=0x46 then returns.
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.pcode.memstate.MemoryFaultHandler;
import ghidra.program.model.address.*;
import ghidra.util.task.TaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class F6Emu extends GhidraScript {
    static final long MUTE   = 0x0C048956L;   // clean entry, r4=flags
    static final long TRAMP  = 0x0C13E1C0L;   // trampoline landing (verified free zero space, 4-aligned)
    static final long SP     = 0x0C7F0000L;
    static final long RET     = 0x0C7FFFFEL;
    static final long CODE_LO = 0x0C000000L, CODE_HI = 0x0C140000L;
    static final long BUDGET = 60000;

    StringBuilder log=new StringBuilder();
    void P(String s){ log.append(s).append("\n"); println(s); }

    // boot-pointer leaf funcs reached via the mute's literal pool — stub to immediate return.
    // From muteentry.txt literal pool: PTR_FUN_0c048b6c..b98 resolve to:
    //   0c048b6c->FUN_0c04088a, b70->FUN_0c0514d2?, b74->FUN_0c040d1a, b78->FUN_0c041078,
    //   b7c->FUN_0c069cf8, b88->FUN_0c06a0f8, b98->FUN_0c0440c6 (the &4 transport stop).
    // We DON'T stub 0c0440c6 (we want to SEE it run); but 0c0440c6 itself calls boot-pointer
    // leaves -> we stub those leaves. Simplest: stub a denylist of known RAM-indirect leaves and
    // let everything in-code run; the lazy fault handler zero-fills reads.
    static final Set<Long> STUBS = new HashSet<>(Arrays.asList(
        0x0C04088AL, 0x0C040D1AL, 0x0C041078L, 0x0C069CF8L, 0x0C06A0F8L,
        0x0C0514D2L, 0x0C040DD0L, 0x0C043ED6L, 0x0C04408AL, 0x0C06A72CL,
        0x0C040B7CL,
        // voice-engine leaf reached from mute b70 literal (0x0C03F910); stub it so the &2
        // voice-clear loop can be validated. Also the transport-stop leaf FUN_0c040f30 (b34)
        // which itself does indirect table jumps in some paths.
        0x0C03F910L, 0x0C041078L));

    public void run() throws Exception {
        P("######## Feature-6 patch software validation ########");
        P("Mute primitive MUTE_ENTRY @0x0C048956 (CLEAN, int f(uint flags in r4)).");
        P("flags: &2=voice-clear(100 slots,stride 0xF1C); &4=transport-stop(FUN_0c0440c6);");
        P("       &0x40=all-note-off; &0x80=reset. We use 0x46 = voice-clear+stop+all-note-off.\n");

        // PART 1: call MUTE_ENTRY directly with several flag values
        directCall("P1a MUTE_ENTRY r4=0x02 (voice-clear only)", MUTE, 0x02);
        directCall("P1b MUTE_ENTRY r4=0x04 (transport-stop only)", MUTE, 0x04);
        directCall("P1c MUTE_ENTRY r4=0x46 (voice-clear+stop+allnoteoff = STOP ALL)", MUTE, 0x46);
        directCall("P1d MUTE_ENTRY r4=0x06 (voice-clear+transport-stop = STOP ALL, minimal)", MUTE, 0x06);

        // PART 1e: control-flow-only run — stub EVERY leaf the mute calls (incl. slot ops &
        // transport-stop leaves) so only MUTE_ENTRY's own flow runs, to confirm it reaches the
        // epilogue 0x0C048ADC and RETURNS cleanly with r4=0x06.
        cfOnly("P1e MUTE_ENTRY r4=0x06 CONTROL-FLOW-ONLY (all leaves stubbed -> clean return)", 0x06);

        // PART 2: emulate the trampoline bytes at 0x0C13E000
        emulateTrampoline();

        P("\n######## SUMMARY ########");
        for(Map.Entry<String,String> e: results.entrySet()) P("  "+e.getKey()+"  ->  "+e.getValue());
        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/f6_emu.txt");
        pw.print(log); pw.close();
        P("\nwrote f6_emu.txt");
    }

    LinkedHashMap<String,String> results=new LinkedHashMap<>();

    void directCall(String name, long entry, int flags) throws Exception {
        P("============================================================");
        P("SCENARIO "+name); P("============================================================");
        EmulatorHelper emu=new EmulatorHelper(currentProgram);
        try{
            emu.setMemoryFaultHandler(new MemoryFaultHandler(){
                public boolean uninitializedRead(Address a,int size,byte[] buf,int off){ for(int i=0;i<size;i++) buf[off+i]=0; return true; }
                public boolean unknownAddress(Address a,boolean w){ return true; }
            });
            for(int i=0;i<=14;i++) emu.writeRegister("r"+i,0L);
            emu.writeRegister(emu.getStackPointerRegister(),SP);
            emu.writeRegister("pr",RET);
            emu.writeRegister("r4",(long)flags);
            emu.setBreakpoint(toAddr(RET));
            emu.writeRegister(emu.getPCRegister(),entry);

            int loopIters=0; boolean hitLoop=false, hitStop=false, hitAllNoteOff=false, hitReset=false;
            long count=0; String stop="budget"; StringBuilder tr=new StringBuilder(); int traced=0;
            TaskMonitor mon=TaskMonitor.DUMMY; long lastPc=-1;
            while(count<BUDGET){
                long pc=emu.getExecutionAddress().getOffset();
                if(pc==RET){ stop="returned (rts to sentinel)"; break; }
                if(pc<CODE_LO||pc>=CODE_HI){ stop="PC left code @0x"+Long.toHexString(pc); break; }
                // milestones
                if(pc==0x0C0489ECL) hitLoop=true;            // movi20 #0xf1c (loop setup)
                if(pc==0x0C048A04L) loopIters++;             // loop head (cmp/ge target / iter start)
                if(pc==0x0C0440C6L) hitStop=true;            // &4 transport-stop target (FUN_0c0440c6)
                if(pc==0x0C048A52L) hitAllNoteOff=true;      // &0x40 block head
                if(pc==0x0C048AA2L) hitReset=true;           // &0x80 block head
                if(pc==0x0C048ADCL) hitReset=hitReset;       // epilogue (no-op marker)
                if(STUBS.contains(pc)){
                    long ret=emu.readRegister("pr").longValue()&0xFFFFFFFFL;
                    if(traced<60){ tr.append(String.format("    [%4d] 0x%08x  <STUB> -> 0x%08x\n",count,pc,ret)); traced++; }
                    emu.writeRegister("r0",0L); emu.writeRegister(emu.getPCRegister(),ret); count++; continue;
                }
                if(traced<240){ tr.append(String.format("    [%4d] 0x%08x  %s\n",count,pc,instrAt(pc))); traced++; }
                lastPc=pc;
                try{ if(!emu.step(mon)){ stop="halt:"+emu.getLastError()+"@0x"+Long.toHexString(emu.getExecutionAddress().getOffset()); break; } }
                catch(Throwable t){ stop="exc@0x"+Long.toHexString(pc)+":"+t; break; }
                count++;
            }
            P("  trace (first 60):"); P(tr.toString().replaceAll("\\n$",""));
            P("  instrs="+count+" stop="+stop+" lastPc=0x"+Long.toHexString(lastPc));
            P("  hitVoiceLoop(&2)="+hitLoop+" loopIters~="+loopIters+"  hitTransportStop(&4)="+hitStop
              +" hitAllNoteOff(&0x40)="+hitAllNoteOff+" hitReset(&0x80)="+hitReset);
            String res="loop="+hitLoop+" iters~"+loopIters+" stop="+hitStop+" anoff="+hitAllNoteOff+" ret="+stop.startsWith("returned");
            results.put(name,res); P("");
        } finally { emu.dispose(); }
    }

    // Control-flow-only: stub ANY jsr/bsr target that is a real function (PC!=next), so only
    // MUTE_ENTRY's own straight-line/loop control flow executes. Confirms reach-epilogue+return.
    void cfOnly(String name, int flags) throws Exception {
        P("============================================================");
        P("SCENARIO "+name); P("============================================================");
        EmulatorHelper emu=new EmulatorHelper(currentProgram);
        try{
            emu.setMemoryFaultHandler(new MemoryFaultHandler(){
                public boolean uninitializedRead(Address a,int size,byte[] buf,int off){ for(int i=0;i<size;i++) buf[off+i]=0; return true; }
                public boolean unknownAddress(Address a,boolean w){ return true; }
            });
            for(int i=0;i<=14;i++) emu.writeRegister("r"+i,0L);
            emu.writeRegister(emu.getStackPointerRegister(),SP);
            emu.writeRegister("pr",RET);
            emu.writeRegister("r4",(long)flags);
            emu.setBreakpoint(toAddr(RET));
            emu.writeRegister(emu.getPCRegister(),MUTE);
            // Any address OUTSIDE [MUTE, 0x0C048AE2) that we land on via a call -> treat as a stub
            // (return immediately). We detect by: pc not in the mute body -> pop pr.
            long BODY_LO=MUTE, BODY_HI=0x0C048AE2L;
            boolean hitLoop=false, hitStop4=false, hitEpilogue=false; int loopIters=0;
            long count=0; String stop="budget"; TaskMonitor mon=TaskMonitor.DUMMY;
            while(count<BUDGET){
                long pc=emu.getExecutionAddress().getOffset();
                if(pc==RET){ stop="returned (rts to sentinel) — CLEAN"; break; }
                if(pc==0x0C0489ECL) hitLoop=true;
                if(pc==0x0C048A04L) loopIters++;
                if(pc==0x0C048A48L) hitStop4=true;   // &4 path: load &FUN_0c0440c6 then jsr
                if(pc==0x0C048ADCL) hitEpilogue=true;
                if(pc<BODY_LO || pc>=BODY_HI){
                    // a called leaf -> stub: pop pr (the jsr put return in pr), zero r0.
                    long ret=emu.readRegister("pr").longValue()&0xFFFFFFFFL;
                    emu.writeRegister("r0",0L); emu.writeRegister(emu.getPCRegister(),ret); count++; continue;
                }
                try{ if(!emu.step(mon)){ stop="halt:"+emu.getLastError(); break; } }catch(Throwable t){ stop="exc:"+t; break; }
                count++;
            }
            P("  instrs="+count+" stop="+stop);
            P("  hitVoiceLoop(&2)="+hitLoop+" loopIters~="+loopIters+" hitTransportStop(&4)="+hitStop4
              +" reachedEpilogue(0x0C048ADC)="+hitEpilogue);
            results.put(name,"loop="+hitLoop+" iters~"+loopIters+" stop4="+hitStop4+" epilogue="+hitEpilogue
                        +" cleanReturn="+stop.startsWith("returned"));
            P("");
        } finally { emu.dispose(); }
    }

    void emulateTrampoline() throws Exception {
        P("============================================================");
        P("SCENARIO P2 TRAMPOLINE @0x0C13E000 (assembled bytes) -> MUTE_ENTRY r4=0x46");
        P("============================================================");
        // Trampoline (SH-2A BE):
        //   e446            mov   #0x46, r4          ; flags = stop-all
        //   d201            mov.l @(0x4,pc), r2      ; r2 = [0x0C13E00C] = 0x0C048956  (pc=tramp+4, +0x4*1+... see calc)
        //   422b            jmp   @r2                ; tail-call mute; its rts returns to OUR caller
        //   0009            nop                      ; delay slot
        //   0c048956        .long 0x0C048956
        // mov.l @(disp,pc): target = (PC&~3)+4+disp*4, PC = addr of the mov.l.
        // mov.l at 0x0C13E002: (0x0C13E002&~3)=0x0C13E000; +4=0x0C13E004; need 0x0C13E00C -> disp=2 -> d202.
        byte[] tramp = new byte[]{
            (byte)0xe4,0x06,            // mov #0x06,r4   (voice-clear + transport-stop)
            (byte)0xd2,0x02,            // mov.l @(0x8,pc -> 0x0C13E00C),r2  (disp=2)
            0x42,0x2b,                  // jmp @r2
            0x00,0x09,                  // nop (delay slot)
            0x00,0x00,                  // pad
            0x00,0x00,                  // pad  (0x0C13E00C is next, 4-aligned)
            0x0c,0x04,(byte)0x89,0x56   // .long 0x0C048956
        };
        EmulatorHelper emu=new EmulatorHelper(currentProgram);
        try{
            emu.setMemoryFaultHandler(new MemoryFaultHandler(){
                public boolean uninitializedRead(Address a,int size,byte[] buf,int off){ for(int i=0;i<size;i++) buf[off+i]=0; return true; }
                public boolean unknownAddress(Address a,boolean w){ return true; }
            });
            emu.writeMemory(toAddr(TRAMP),tramp);
            P("  wrote trampoline bytes @0x"+Long.toHexString(TRAMP)+": e406 d202 422b 0009 0000 0000 0c048956");
            for(int i=0;i<=14;i++) emu.writeRegister("r"+i,0L);
            emu.writeRegister(emu.getStackPointerRegister(),SP);
            emu.writeRegister("pr",RET);
            emu.setBreakpoint(toAddr(RET));
            emu.writeRegister(emu.getPCRegister(),TRAMP);

            boolean reachedMute=false; long r4AtMute=-1; long count=0; String stop="budget";
            boolean hitLoop=false, hitStop=false; int loopIters=0;
            long BODY_LO=MUTE, BODY_HI=0x0C048AE2L, TR_LO=TRAMP, TR_HI=TRAMP+0x10;
            StringBuilder tr=new StringBuilder(); int traced=0; TaskMonitor mon=TaskMonitor.DUMMY;
            while(count<BUDGET){
                long pc=emu.getExecutionAddress().getOffset();
                if(pc==RET){ stop="returned (trampoline+mute returned to caller) — CLEAN"; break; }
                if(pc==MUTE && !reachedMute){ reachedMute=true; r4AtMute=emu.readRegister("r4").longValue()&0xFFFFFFFFL; }
                if(pc==0x0C0489ECL) hitLoop=true;
                if(pc==0x0C048A04L) loopIters++;
                if(pc==0x0C048A48L) hitStop=true;
                boolean inMute=(pc>=BODY_LO&&pc<BODY_HI), inTr=(pc>=TR_LO&&pc<TR_HI);
                if(!inMute && !inTr){
                    // called leaf (or trampoline tail-call landed in mute already handled) -> stub
                    long ret=emu.readRegister("pr").longValue()&0xFFFFFFFFL;
                    if(traced<30){ tr.append(String.format("    [%4d] 0x%08x <STUB-leaf> ->0x%08x\n",count,pc,ret)); traced++; }
                    emu.writeRegister("r0",0L); emu.writeRegister(emu.getPCRegister(),ret); count++; continue;
                }
                if(traced<30){ tr.append(String.format("    [%4d] 0x%08x %s\n",count,pc,instrAt(pc))); traced++; }
                try{ if(!emu.step(mon)){ stop="halt:"+emu.getLastError(); break; } }catch(Throwable t){ stop="exc:"+t; break; }
                count++;
            }
            P("  trace (first 30):"); P(tr.toString().replaceAll("\\n$",""));
            P("  instrs="+count+" stop="+stop);
            P("  reached MUTE_ENTRY="+reachedMute+(reachedMute?(" r4=0x"+Long.toHexString(r4AtMute)):""));
            P("  hitVoiceLoop="+hitLoop+" loopIters~="+loopIters+" hitTransportStop="+hitStop);
            results.put("P2 TRAMPOLINE","reachedMute="+reachedMute+" r4=0x"+Long.toHexString(r4AtMute)
                        +" voiceLoop="+hitLoop+" iters~"+loopIters+" stop="+hitStop+" ret="+stop.startsWith("returned"));
        } finally { emu.dispose(); }
    }

    String instrAt(long a){ try{ var ins=currentProgram.getListing().getInstructionAt(toAddr(a)); return ins!=null?ins.toString():"(no-instr)"; }catch(Throwable t){ return "(?)"; } }
}
