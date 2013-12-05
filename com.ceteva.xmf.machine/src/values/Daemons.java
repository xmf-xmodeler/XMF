package values;

import engine.Machine;

/******************************************************************************
 *                                                                            *
 *                                 Daemons                                    *
 *             ------------------------------------------------               *
 *                                                                            *
 *  Daemons are used to monitor things that change state. For example when    *
 *  an object updates a slot, if there are any daemons monitoring the slot    *
 *  then they are informed of the change. Daemons have different monitoring   *
 *  modes. The modes are constants defined in this file.                      *
 *                                                                            *
 ******************************************************************************/

public interface Daemons {
	
	// Daemon fires on any slot change...
	
	public static final int DAEMON_ANY = Machine.mkInt(0);

	// Daemon fires when a specifically named slot changes...

	public static final int DAEMON_VALUE = Machine.mkInt(1);

	// Daemon fires when a value is added to the slot...

	public static final int DAEMON_ADD = Machine.mkInt(2);

	// Daemon fires when a value is removed from a slot...

	public static final int DAEMON_SUB = Machine.mkInt(3);

}
