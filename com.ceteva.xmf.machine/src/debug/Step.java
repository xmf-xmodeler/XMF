package debug;

import java.io.PrintStream;

public class Step {
	
	private int threadId;
	
	private int line;
	
	private int frame;
	
	private SteppingMode mode;
	
	private StepListener listener;

	public Step(int threadId, int line, int frame, SteppingMode mode, StepListener listener) {
		super();
		this.threadId = threadId;
		this.line = line;
		this.frame = frame;
		this.mode = mode;
		this.listener = listener;
	}

	public int getThreadId() {
		return threadId;
	}

	public void setThreadId(int threadId) {
		this.threadId = threadId;
	}

	public int getLine() {
		return line;
	}

	public void setLine(int line) {
		this.line = line;
	}

	public int getFrame() {
		return frame;
	}

	public void setFrame(int frame) {
		this.frame = frame;
	}

	public SteppingMode getMode() {
		return mode;
	}

	public void setMode(SteppingMode mode) {
		this.mode = mode;
	}

	public StepListener getListener() {
		return listener;
	}

	public void setListener(StepListener listener) {
		this.listener = listener;
	}

	public void print(PrintStream out) {
		System.out.print("Thread " + threadId + " is stepping mode " + mode + " at line " + line + " frame " + frame);
	}

}
