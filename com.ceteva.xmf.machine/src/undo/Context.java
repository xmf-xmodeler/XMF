package undo;

/******************************************************************************
 *                                                                            *
 *                               Undo Contexts                                *
 *             ------------------------------------------------               *
 *                                                                            *
 *  Used as a marker on the undo stack. This is a dummy command used to       *
 *  delimit sequences of commands on the stack.                               *
 *                                                                            *
 ******************************************************************************/

import engine.Machine;

public class Context extends Command {
    
    public void gc(Machine machine) {}
    
    public void redo(Machine machine) {}
    
    public int size() { return 0; }
    
    public void undo(Machine machine) {}

}
