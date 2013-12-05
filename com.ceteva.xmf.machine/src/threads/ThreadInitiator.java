package threads;

/******************************************************************************
 *                                                                            *
 *                               Thread Initiators                            *
 *             ------------------------------------------------               *
 *                                                                            *
 *  When a thread starts its initiator (when defined) is called. This allows  *
 *  the VM to take special action when required (for example ensuring that    *
 *  the VM thread is run on a specific Java thread for GUI-based activities). *
 *                                                                            *
 ******************************************************************************/

import engine.Machine;

public class ThreadInitiator {
	
	// Starts a thread. The thread is assumed to be current on the machine...
	
	public void start(Machine machine) {
		machine.perform();
	}

}
