// ArmHook.java — verify the literal 0x0C0CA130 (arm 0x07 jmp target) is used by ONLY that arm,
// and emulate the full feature-6 patch via the ARM-LITERAL-REPOINT hook:
//   HOOK: change [0x0C0CA130] from 0x0C0C747C to 0x0C13E1C0 (trampoline).
//   TRAMPOLINE (rts-terminated, since the arm already restored r11-r14/pr and does jmp @r2):
//       e406            mov  #0x06,r4
//       d2nn            mov.l @(disp,pc),r2  ; r2=0x0C048956
//       422b            jmp  @r2             ; tail-call mute; mute's rts returns to arm's caller
//       0009            nop
//       .long 0x0C048956
//   (Because the arm does the epilogue THEN jmp @r2 with pr already = caller's return, the mute's
//    own rts returns correctly to FUN_0c0c9e60's caller. So the trampoline can be the SAME tail-
//    call form as before — no extra rts needed. We validate this end-to-end.)
// Emulate: enter FUN_0c0c9e60 with a pad record whose function-code byte (rec+3) = 0x07, the
// patched literal in place, and confirm: switch->arm 0x07->epilogue->jmp tramp->MUTE_ENTRY(r4=6)
// ->voice-clear(100)+transport-stop->rts to sentinel.
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.pcode.memstate.MemoryFaultHandler;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.TaskMonitor;
import java.io.PrintWriter;
import java.util.*;

public class ArmHook01 extends GhidraScript {
    static final long DISPATCH = 0x0C0C9E60L;   // pad-function dispatch
    static final long ARM07    = 0x0C0C9F4EL;   // code 0x01 arm (Kit Increment)
    static final long LIT      = 0x0C0CA120L;   // arm 0x01 jmp-target literal (-> 0x0C0C744E)
    static final long TRAMP    = 0x0C13E1C0L;
    static final long MUTE     = 0x0C048956L;
    static final long PADREC   = 0x0C7E0000L;   // scratch pad assignment record
    static final long P2REC    = 0x0C7E1000L;   // scratch param_2 block
    static final long SP       = 0x0C7F0000L;
    static final long RET      = 0x0C7FFFFEL;
    static final long CODE_LO=0x0C000000L, CODE_HI=0x0C140000L, BUDGET=60000;

    StringBuilder log=new StringBuilder();
    void P(String s){ log.append(s).append("\n"); println(s); }

    public void run() throws Exception {
        ReferenceManager rm=currentProgram.getReferenceManager();
        Listing lst=currentProgram.getListing();
        P("######## Feature-6 ARM-LITERAL-REPOINT hook validation ########");
        // (1) confirm 0x0C0CA130 referenced only by arm 0x07
        P("refs to literal 0x0C0CA130 (must be only arm 0x07 @0x0C0CA016):");
        int n=0; for(Reference r: rm.getReferencesTo(toAddr(LIT))){ P("   "+r.getReferenceType()+"@"+r.getFromAddress()); n++; }
        if(n==0) P("   (Ghidra shows no explicit ref; the mov.l@(0x46,pc) at 0x0C0CA016 computes it.)");

        // Scenario: PATCHED hook, function code 0x07.
        runDispatch("PATCHED dispatch, padFunc=0x01 -> stop-all", true, 0x01);
        // Same, but control-flow-only inside the mute (stub ALL its leaves) to confirm CLEAN RETURN
        // through arm-epilogue-restored pr back to the dispatch's caller (sentinel).
        cfReturn("PATCHED padFunc=0x01 CONTROL-FLOW-ONLY -> clean return to caller", 0x01);
        // Regression: UNPATCHED, code 0x07 -> normal arm (jmp 0x0C0C747C, a display refresh; stubbed).
        runDispatch("UNPATCHED dispatch, padFunc=0x01 -> normal (control)", false, 0x01);
        // Regression: PATCHED, code 0x08 (click) -> must still take its OWN arm, not stop-all.
        runDispatch("PATCHED dispatch, padFunc=0x08 -> click arm unaffected (other codes)", true, 0x08);

        P("\n######## SUMMARY ########");
        for(Map.Entry<String,String> e: results.entrySet()) P("  "+e.getKey()+"  ->  "+e.getValue());
        PrintWriter pw=new PrintWriter("/Users/velvetmotion/Desktop/DTX-M12/firmware/armhook01_emu.txt");
        pw.print(log); pw.close(); P("\nwrote armhook01_emu.txt");
    }

    LinkedHashMap<String,String> results=new LinkedHashMap<>();

    void runDispatch(String name, boolean patched, int code) throws Exception {
        P("============================================================");
        P("SCENARIO "+name); P("============================================================");
        EmulatorHelper emu=new EmulatorHelper(currentProgram);
        try{
            emu.setMemoryFaultHandler(new MemoryFaultHandler(){
                public boolean uninitializedRead(Address a,int size,byte[] buf,int off){ for(int i=0;i<size;i++) buf[off+i]=0; return true; }
                public boolean unknownAddress(Address a,boolean w){ return true; }
            });
            // scratch pad record: param_1 (r4) points to a struct whose [+4] = ptr to assignment
            // record; assignment record[+3] = function code; record[+7] = the value to latch.
            byte[] zero=new byte[0x80];
            emu.writeMemory(toAddr(PADREC),zero);
            emu.writeMemory(toAddr(P2REC),zero);
            // param_1 struct at PADREC: [+4] -> assignment record at PADREC+0x40
            emu.writeMemoryValue(toAddr(PADREC+4),4,PADREC+0x40);
            emu.writeMemoryValue(toAddr(PADREC+0x40+3),1,code);   // function code
            emu.writeMemoryValue(toAddr(PADREC+0x40+7),1,0x55);   // value byte
            // param_2 at P2REC: [+0x26] used as index by PTR_FUN_0c0ca118 (stubbed -> returns r11 ptr)

            // apply hook: repoint literal
            if(patched){
                byte[] before=emu.readMemory(toAddr(LIT),4);
                emu.writeMemory(toAddr(LIT), new byte[]{0x0C,0x13,(byte)0xE1,(byte)0xC0});
                byte[] after=emu.readMemory(toAddr(LIT),4);
                P(String.format("  HOOK @0x%08x: %02x%02x%02x%02x -> %02x%02x%02x%02x",LIT,
                  before[0]&0xFF,before[1]&0xFF,before[2]&0xFF,before[3]&0xFF,
                  after[0]&0xFF,after[1]&0xFF,after[2]&0xFF,after[3]&0xFF));
                // trampoline bytes at TRAMP
                emu.writeMemory(toAddr(TRAMP), new byte[]{
                    (byte)0xe4,0x06, (byte)0xd2,0x02, 0x42,0x2b, 0x00,0x09,
                    0x00,0x00,0x00,0x00, 0x0c,0x04,(byte)0x89,0x56});
                P("  trampoline @0x0C13E1C0: e406 d202 422b 0009 0000 0000 0c048956");
            }

            for(int i=0;i<=14;i++) emu.writeRegister("r"+i,0L);
            emu.writeRegister(emu.getStackPointerRegister(),SP);
            emu.writeRegister("pr",RET);
            emu.writeRegister("r4",PADREC);      // param_1
            emu.writeRegister("r5",P2REC);       // param_2
            emu.setBreakpoint(toAddr(RET));
            emu.writeRegister(emu.getPCRegister(),DISPATCH);

            // stub set: the dispatch's pre-call helper PTR_FUN_0c0ca118 (=FUN_0c0d0ea4) returns a
            // ptr we point at PADREC; the normal-arm display funcs FUN_0c0c74xx; and all mute leaves.
            Set<Long> stubs=new HashSet<>(Arrays.asList(
                0x0C0D0EA4L,  // PTR_FUN_0c0ca118 (puVar2 = f(param_2+0x26)); we make it return PADREC
                0x0C0C747CL, 0x0C0C7448L, 0x0C0C744EL, 0x0C0C7454L, 0x0C0C7486L,
                0x0C0C8074L, 0x0C0C7472L,
                // mute leaves:
                0x0C04088AL,0x0C040D1AL,0x0C041078L,0x0C069CF8L,0x0C06A0F8L,0x0C03F910L,
                0x0C040DD0L,0x0C043ED6L,0x0C04408AL,0x0C040F30L,0x0C0440C6L /*see note*/));
            // NOTE: we stub 0x0C0440C6 here ONLY to keep emu simple; we already proved in f6_emu
            // that it runs. We still DETECT reaching it. Comment: to see voice-loop, do NOT stub
            // MUTE itself; we detect loop via 0x0C048A04.
            stubs.remove(0x0C0440C6L); // keep it live to confirm transport-stop reached

            boolean reachedArm07=false, reachedTramp=false, reachedMute=false, hitLoop=false, hitStop=false, reachedClickArm=false;
            long r4Mute=-1; int loopIters=0; long count=0; String stop="budget";
            StringBuilder tr=new StringBuilder(); int traced=0; TaskMonitor mon=TaskMonitor.DUMMY;
            long MBODY_LO=MUTE, MBODY_HI=0x0C048AE2L;
            while(count<BUDGET){
                long pc=emu.getExecutionAddress().getOffset();
                if(pc==RET){ stop="returned to sentinel — CLEAN"; break; }
                if(pc==ARM07) reachedArm07=true;
                if(pc==0x0C0CA026L) reachedClickArm=true;   // code 0x08 arm
                if(pc>=TRAMP && pc<TRAMP+0x10) reachedTramp=true;
                if(pc==MUTE){ reachedMute=true; if(r4Mute<0) r4Mute=emu.readRegister("r4").longValue()&0xFFFFFFFFL; }
                if(pc==0x0C0489ECL) hitLoop=true;
                if(pc==0x0C048A04L) loopIters++;
                if(pc==0x0C0440C6L) hitStop=true;
                // stub: PTR_FUN_0c0ca118 helper -> return PADREC (so puVar2 = PADREC, writable)
                if(pc==0x0C0D0EA4L){ long ret=emu.readRegister("pr").longValue()&0xFFFFFFFFL;
                    emu.writeRegister("r0",PADREC); emu.writeRegister(emu.getPCRegister(),ret); count++; continue; }
                // stub mute-internal transport leaf calls (boot tables zero) but let mute body run
                if(stubs.contains(pc)){ long ret=emu.readRegister("pr").longValue()&0xFFFFFFFFL;
                    if(traced<50){ tr.append(String.format("    [%4d] 0x%08x <STUB> ->0x%08x\n",count,pc,ret)); traced++; }
                    emu.writeRegister("r0",0L); emu.writeRegister(emu.getPCRegister(),ret); count++; continue; }
                // FUN_0c0440c6 runs but ITS leaves are stubbed above (040f30 etc.); when it tries a
                // leaf not in code we let lazy handler cope. If PC leaves code, stop.
                if(pc<CODE_LO||pc>=CODE_HI){
                    // could be a stubbed-leaf returning to 0 via clobbered pr inside FUN_0c0440c6;
                    // treat as benign end-of-work if we already reached mute+stop.
                    stop="PC left code @0x"+Long.toHexString(pc)+(reachedMute?" (after mute work)":""); break; }
                if(traced<50){ tr.append(String.format("    [%4d] 0x%08x %s\n",count,pc,instrAt(pc))); traced++; }
                try{ if(!emu.step(mon)){ stop="halt:"+emu.getLastError(); break; } }catch(Throwable t){ stop="exc@0x"+Long.toHexString(pc)+":"+t; break; }
                count++;
            }
            P("  trace (first 50):"); P(tr.toString().replaceAll("\\n$",""));
            P("  instrs="+count+" stop="+stop);
            P("  reachedArm07="+reachedArm07+" reachedClickArm(0x08)="+reachedClickArm
              +" reachedTrampoline="+reachedTramp+" reachedMute="+reachedMute+(reachedMute?(" r4=0x"+Long.toHexString(r4Mute)):""));
            P("  hitVoiceLoop="+hitLoop+" loopIters~="+loopIters+" hitTransportStop="+hitStop);
            results.put(name,"arm07="+reachedArm07+" clickArm="+reachedClickArm+" tramp="+reachedTramp
                        +" mute="+reachedMute+" r4=0x"+Long.toHexString(r4Mute)+" loop="+hitLoop+" iters~"+loopIters+" stop="+hitStop);
            P("");
        } finally { emu.dispose(); }
    }
    // Control-flow-only: like runDispatch PATCHED, but stub EVERYTHING outside the dispatch body,
    // the arm, the trampoline, and the mute body -> confirms the whole hooked path RETURNS cleanly.
    void cfReturn(String name, int code) throws Exception {
        P("============================================================");
        P("SCENARIO "+name); P("============================================================");
        EmulatorHelper emu=new EmulatorHelper(currentProgram);
        try{
            emu.setMemoryFaultHandler(new MemoryFaultHandler(){
                public boolean uninitializedRead(Address a,int size,byte[] buf,int off){ for(int i=0;i<size;i++) buf[off+i]=0; return true; }
                public boolean unknownAddress(Address a,boolean w){ return true; }
            });
            byte[] zero=new byte[0x80];
            emu.writeMemory(toAddr(PADREC),zero); emu.writeMemory(toAddr(P2REC),zero);
            emu.writeMemoryValue(toAddr(PADREC+4),4,PADREC+0x40);
            emu.writeMemoryValue(toAddr(PADREC+0x40+3),1,code);
            emu.writeMemory(toAddr(LIT), new byte[]{0x0C,0x13,(byte)0xE1,(byte)0xC0});
            emu.writeMemory(toAddr(TRAMP), new byte[]{
                (byte)0xe4,0x06,(byte)0xd2,0x02,0x42,0x2b,0x00,0x09,0x00,0x00,0x00,0x00,0x0c,0x04,(byte)0x89,0x56});
            for(int i=0;i<=14;i++) emu.writeRegister("r"+i,0L);
            emu.writeRegister(emu.getStackPointerRegister(),SP);
            emu.writeRegister("pr",RET);
            emu.writeRegister("r4",PADREC); emu.writeRegister("r5",P2REC);
            emu.setBreakpoint(toAddr(RET));
            emu.writeRegister(emu.getPCRegister(),DISPATCH);

            long DBODY_LO=DISPATCH, DBODY_HI=0x0C0CA160L;   // dispatch + arms region
            long MBODY_LO=MUTE, MBODY_HI=0x0C048AE2L;
            boolean reachedMute=false, hitLoop=false, hitStop=false, hitEpilogue=false; int loopIters=0;
            long count=0; String stop="budget"; TaskMonitor mon=TaskMonitor.DUMMY;
            while(count<BUDGET){
                long pc=emu.getExecutionAddress().getOffset();
                if(pc==RET){ stop="returned to sentinel — CLEAN"; break; }
                if(pc==MUTE) reachedMute=true;
                if(pc==0x0C0489ECL) hitLoop=true;
                if(pc==0x0C048A04L) loopIters++;
                if(pc==0x0C048A48L) hitStop=true;
                if(pc==0x0C048ADCL) hitEpilogue=true;
                boolean inD=(pc>=DBODY_LO&&pc<DBODY_HI), inM=(pc>=MBODY_LO&&pc<MBODY_HI), inT=(pc>=TRAMP&&pc<TRAMP+0x10);
                // PTR_FUN_0c0ca118 helper -> return PADREC
                if(pc==0x0C0D0EA4L){ long ret=emu.readRegister("pr").longValue()&0xFFFFFFFFL;
                    emu.writeRegister("r0",PADREC); emu.writeRegister(emu.getPCRegister(),ret); count++; continue; }
                if(!inD && !inM && !inT){ long ret=emu.readRegister("pr").longValue()&0xFFFFFFFFL;
                    emu.writeRegister("r0",0L); emu.writeRegister(emu.getPCRegister(),ret); count++; continue; }
                try{ if(!emu.step(mon)){ stop="halt:"+emu.getLastError(); break; } }catch(Throwable t){ stop="exc:"+t; break; }
                count++;
            }
            P("  instrs="+count+" stop="+stop);
            P("  reachedMute="+reachedMute+" hitVoiceLoop="+hitLoop+" loopIters~="+loopIters
              +" hitTransportStop="+hitStop+" reachedMuteEpilogue(0x0C048ADC)="+hitEpilogue);
            results.put(name,"mute="+reachedMute+" loop="+hitLoop+" iters~"+loopIters+" stop="+hitStop
                        +" muteEpilogue="+hitEpilogue+" cleanReturn="+stop.startsWith("returned"));
            P("");
        } finally { emu.dispose(); }
    }

    String instrAt(long a){ try{ var ins=currentProgram.getListing().getInstructionAt(toAddr(a)); return ins!=null?ins.toString():"(no-instr)"; }catch(Throwable t){ return "(?)"; } }
}
