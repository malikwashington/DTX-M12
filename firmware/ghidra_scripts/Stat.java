import ghidra.app.script.GhidraScript;
public class Stat extends GhidraScript {
  public void run() throws Exception {
    int n=currentProgram.getFunctionManager().getFunctionCount();
    println("FUNCTION COUNT = "+n);
    println("analyzed? "+ghidra.program.model.listing.Program.ANALYSIS_PROPERTIES);
  }
}
