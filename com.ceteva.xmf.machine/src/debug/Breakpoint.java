package debug;

import java.io.PrintStream;

import engine.Machine;

import threads.Thread;


public class Breakpoint {

	private String filename;
	private int line;
	private threads.Thread thread;
	
	public Breakpoint(String filename, int line, Thread thread) {
		this.filename = filename;
		this.line = line;
		this.thread = thread;
	}

	public Thread getThread() {
        return thread;
    }

    public void setThread(Thread thread) {
        this.thread = thread;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public Breakpoint(String filename,int line) {
		this.filename = filename;
		this.line = line;
	}
	
	public String getFilename() {
		return filename;
	}
	
	public int getLine() {
		return line;
	}

	public boolean match(Machine machine, int resourceName, int line) {

		// A breakpoint matches when it ends with the supplied resource name
		// and the lines match...
		
		if(this.line == line) {
			
			// Check the resource matches the filename...
			
			int resourceLength = machine.stringLength(resourceName);
			
			if(resourceLength >= filename.length()) {
				
				// Compare the end of the resource name...
				
				int resourceIndex = resourceLength - 1;
				boolean OK = true;
				
				for(int i = filename.length() - 1; OK && i >= 0; i--) {
					
					// Compare the chars
					
					OK = filename.charAt(i) == machine.stringRef(resourceName, resourceIndex--);
				}
				
				return OK;
			} else return false;
		} else return false;
	}

	public void print(PrintStream out) {
		out.print("Breakpoint in file " + filename + " at line " + line);
	}
	
	public String toString() {
		return "Breakpoint( " + filename + "," + line + ")";
	}
}
