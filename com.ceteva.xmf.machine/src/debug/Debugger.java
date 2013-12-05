package debug;

import java.io.PrintStream;
import java.util.Vector;

import engine.Machine;

import threads.Thread;


public class Debugger {

	// The VM maintains a collection of breakpoints in the following
	// table. When line numbers are changed, the VM checks whether a
	// breakpoint has been reached...

	private Vector<Breakpoint> breakpoints = new Vector<Breakpoint>();

	private Vector<BreakpointListener> breakpointListeners = new Vector<BreakpointListener>();

	private Vector<Step> stepping = new Vector<Step>();

	private Breakpoint lastBreakpoint = null;

	public void addBreakpoint(String filename, int line) {

		// Define a new breakpoint...

		boolean exists = false;
		for (Breakpoint b : breakpoints)
			if (b.getFilename().equals(filename) && b.getLine() == line)
				exists = true;
		if (!exists)
			breakpoints.add(new Breakpoint(filename, line));
	}

	public void addBreakpointListener(BreakpointListener l) {

		// The listener will be informed whenever a breakpoint
		// is activated...

		breakpointListeners.add(l);
	}

	public void clearBreakpoint(String filename, int line) {

		// Remove the breakpoint...

		for (int i = 0; i < breakpoints.size(); i++) {
			Breakpoint breakpoint = breakpoints.get(i);
			if (breakpoint.getFilename().equals(filename)
					&& breakpoint.getLine() == line) {
				breakpoints.remove(breakpoint);
			}
		}
	}

	public void clearBreakpointListener(BreakpointListener l) {
		breakpointListeners.remove(l);
	}

	public void line(Machine machine, int line) {

		// A line has changed in the VM, check to see if any breakpoints
		// have been hit...

		Thread thread = machine.currentThread();
		boolean suspended = false;

		if (isStepping(thread))
			suspended = step(machine, thread, line);
		if (!suspended && !breakpoints.isEmpty())
			checkBreakpoints(machine, machine.frameResourceName(), line);
	}

	private boolean isStepping(Thread thread) {
		return getStep(thread) != null;
	}

	private boolean step(Machine machine, Thread thread, int line) {

		// Checks the stepping mode of the thread. Returns
		// true when the thread is suspended...

		switch (getStep(thread).getMode()) {
		case INTO:
			return stepInto(machine, thread, line);
		case OVER:
			return stepOver(machine, thread, line);
		case RETURN:
			return stepReturn(machine, thread, line);
		default:
			return false;
		}
	}

	private boolean stepReturn(Machine machine, Thread thread, int line) {

		// Called when the current thread is in RETURN step mode...

		Step step = getStep(thread);
		int currentFrame = machine.currentFrame();
		int breakFrame = getStep(thread).getFrame();
		if (currentFrame < breakFrame) {
			removeStep(step);
			machine.breakpoint();
			step.getListener().suspendedStep(thread.id() + "");
			return true;
		} else return false;
	}

	private boolean stepOver(Machine machine, Thread thread, int line) {
		Step step = getStep(thread);
		int currentFrame = machine.currentFrame();
		int breakFrame = getStep(thread).getFrame();
		int breakLine = step.getLine();
		if (currentFrame == breakFrame && line > breakLine) {
			removeStep(step);
			machine.breakpoint();
			step.getListener().suspendedStep(thread.id() + "");
			return true;
		} else if (currentFrame < breakFrame) {
			removeStep(step);
			machine.breakpoint();
			step.getListener().suspendedStep(thread.id() + "");
			return true;
		} else return false;
	}

	private boolean stepInto(Machine machine, Thread thread, int line) {
		Step step = getStep(thread);
		int currentFrame = machine.currentFrame();
		int breakFrame = getStep(thread).getFrame();
		int breakLine = step.getLine();
		if (currentFrame > breakFrame) {
			removeStep(step);
			machine.breakpoint();
			step.getListener().suspendedStep(thread.id() + "");
			return true;
		} else if (currentFrame == breakFrame && line > breakLine) {
			removeStep(step);
			machine.breakpoint();
			step.getListener().suspendedStep(thread.id() + "");
			return true;
		} else {
			removeStep(step);
			return false;
		}
	}

	private void removeStep(Step step) {
		stepping.remove(step);
	}
	
	public void removeStep(String threadId) {
		Step step = getStep(threadId);
		if(step != null)
			removeStep(step);
	}

	private Step getStep(Thread thread) {
		for (Step step : stepping)
			if (step.getThreadId() == thread.id())
				return step;
		return null;
	}

	private Step getStep(String threadId) {
		int id = Integer.parseInt(threadId);
		for (Step step : stepping)
			if (step.getThreadId() == id)
				return step;
		return null;
	}

	public void checkBreakpoints(Machine machine, int resourceName, int line) {

		// A LINE instruction has been hit and there are breakpoints.
		// If we match the current breakpoint then ignore it but don't
		// reset the current breakpoint since there may be more LINE
		// instructions for the same line. If we hit a new breakpoint
		// then update the last known breakpoint and cause the thread to
		// break. Otherwise, the last known breakpoint has been passed
		// so clear it.

		for (Breakpoint b : breakpoints)
			if (b.match(machine, resourceName, line)) {
				if (b == lastBreakpoint)
					return;
				else {
					breakpointHit(machine, b);
					return;
				}
			}
		lastBreakpoint = null;
	}

	private void breakpointHit(Machine machine, Breakpoint b) {
		b.setThread(machine.currentThread());
		activateBreakPoint(b);
		machine.breakpoint();
		lastBreakpoint = b;
	}

	private void activateBreakPoint(Breakpoint b) {

		// Inform everyone who is listening for breakpoints...

		for (BreakpointListener l : breakpointListeners)
			l.activate(b.getThread().id() + "", b.getFilename(), b.getLine());

	}

	public void printBreakpoints(PrintStream out) {

		// Print the breakpoints current set on the machine...

		for (Breakpoint b : breakpoints) {
			b.print(out);
			out.println();
		}
	}

	public void printSteps(PrintStream out) {
		for (Step step : stepping) {
			step.print(out);
			out.println();
		}
	}

	public void stepInto(String threadId, int line, int frame,
			StepListener listener) {
		removeStep(threadId);
		stepping.add(new Step(Integer.parseInt(threadId), line, frame,
				SteppingMode.INTO, listener));
	}

	public void stepOver(String threadId, int line, int frame,
			StepListener listener) {
		removeStep(threadId);
		stepping.add(new Step(Integer.parseInt(threadId), line, frame,
				SteppingMode.OVER, listener));
	}

	public void stepReturn(String threadId, int line, int frame,
			StepListener listener) {
		removeStep(threadId);
		stepping.add(new Step(Integer.parseInt(threadId), line, frame,
				SteppingMode.RETURN, listener));
	}

	public void printStats(PrintStream out) {
		printBreakpoints(out);
		printSteps(out);
	}
}
