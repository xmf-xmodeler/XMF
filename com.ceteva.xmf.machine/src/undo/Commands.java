package undo;

/******************************************************************************
 *                                                                            *
 *                      A Group of Undoable Commands                          *
 *             ------------------------------------------------               *
 *                                                                            *
 * Commands can be grouped into an undo transaction in which case they are    *
 * pushed onto the undo stack as an instance of this class.                   *
 *                                                                            *
 ******************************************************************************/

import java.util.Stack;

import engine.Machine;


public class Commands extends Command {
    
    Stack<Command> commands = new Stack<Command>();
    
    public void gc(Machine machine) {
        for(int i = 0; i < commands.size(); i++) 
            commands.elementAt(i).gc(machine);
    }
    
    public void push(Command command) {
        commands.push(command);
    }
    
    public void redo(Machine machine) {
        for(int i = commands.size() - 1; i >= 0; i--) 
            commands.elementAt(i).redo(machine);
    }
    
    public int size() {
        int size = 0;
        for(int i = 0; i < commands.size(); i++) 
            size = size + commands.elementAt(i).size();
        return size;
    }
    
    public void undo(Machine machine) {
        for(int i = 0; i < commands.size(); i++) 
            commands.elementAt(i).undo(machine);
    }

}
