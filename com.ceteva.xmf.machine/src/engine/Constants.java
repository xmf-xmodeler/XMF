package engine;

/******************************************************************************
 *                                                                            *
 *                             Machine Constants                              *
 *             ------------------------------------------------               *
 *                                                                            *
 *  Various constants used by the machine.                                    *
 *                                                                            *
 ******************************************************************************/

public interface Constants {

	public final static int K = 1024;

	// The default heap size (usually overriden
	// by a command line arg)...

	public final static int HEAPSIZE = 30 * K;

	// The default stack size (usually overriden
	// by a command line arg)...

	public final static int STACKSIZE = 50 * K;

	// The boolean value true...

	public static final int trueValue = Machine.mkBool(1);

	// the boolean value false...

	public static final int falseValue = Machine.mkBool(0);

	// The empty sequence...

	public static final int nilValue = Machine.mkNil();

	// The null value...

	public static final int undefinedValue = Machine.mkUndefined();

	// The largest integer...

	public static final int MAXINT = 0xFFFFFF;

}
