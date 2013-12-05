package engine;

/******************************************************************************
 *                                                                            *
 *                               VM Stack Frame                               *
 *             ------------------------------------------------               *
 *                                                                            *
 *  The VM uses a stack to contain call frames. A call frame is pushed onto   *
 *  the stack when an operation is called or when a message is sent. The frame*
 *  contains space for the args and locals of the call and for the index into *
 *  the executing machine code. The information in the call frame is indexed  * 
 *  using the constants defined in this file. Frames are created at the start *
 *  of a call, at which stage they become the current open frame. A frame is  *
 *  closed, and becomes the current frame, when the argument values have been *
 *  produced and at the point when the operation is entered.                  *
 *                                                                            *
 ******************************************************************************/

public interface StackFrame {
    
    // A link to the previous call frame (or -1)...
	
	public static final int PREVFRAME = 0;

	// A link to the previous open frame...

	public static final int PREVOPENFRAME = 1;
	
	// The code box contains the executing code for the frame...

	public static final int FRAMECODEBOX = 2;
	
	// An index into the code for the current frame...

	public static final int FRAMECODEINDEX = 3;

	// The globals of the current frame are the closed in variables...

	public static final int FRAMEGLOBALS = 4;

	// The dynamics of the current frame are a collection of
	// name-spaces (or their hash-tables) containing imported names...

	public static final int FRAMEDYNAMICS = 5;
	
	// The number of local variables...

	public static final int FRAMELOCALS = 6;
	
	// The current value of 'self'...

	public static final int FRAMESELF = 7;
	
	// A sequence of operations that will be used to continue the
	// message lookup if we ever invoke 'super'...

	public static final int FRAMESUPER = 8;
	
	// An operation used to handle exceptions that are thrown past
	// this frame...

	public static final int FRAMEHANDLER = 9;
	
	// The current line in the source file...

	public static final int FRAMELINECOUNT = 10;
	
	// Obsolete...

	public static final int FRAMECHARCOUNT = 11;

	// The start of the frame locals...

	public static final int FRAMELOCAL0 = 12;

}
