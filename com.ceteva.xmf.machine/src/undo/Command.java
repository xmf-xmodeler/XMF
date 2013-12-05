package undo;

/******************************************************************************
 *                                                                            *
 *                              Undo Commands                                 *
 *             ------------------------------------------------               *
 *                                                                            *
 *  When the machine is recording commands for a possible undo action it      *
 *  keeps a stack of commands. Each command must implement the methods defined*
 *  in this class.                                                            *
 *                                                                            *
 ******************************************************************************/

import engine.Machine;

public abstract class Command {
    
    // The command may have retained heap points that require
    // update on a garbage collect...
    
    public abstract void gc(Machine machine);
    
    // A redo action causes an undone command to be redone...
    
    public abstract void redo(Machine machine);
    
    // Commands may be atomic or composed into undo groups.
    // The size method returns the number of atomic commands
    // in this command...
    
    public abstract int size();
    
    // Called when the machine wants to undo the effects of
    // this action...
    
    public abstract void undo(Machine machine);

}
