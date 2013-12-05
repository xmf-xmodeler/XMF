package debug;

public interface BreakpointListener {
	
	// An interface that is used by clients of the debugger
	// who wish to listen for breakpoint activation...
	
	public void activate(String threadId, String fileName, int line);

}
