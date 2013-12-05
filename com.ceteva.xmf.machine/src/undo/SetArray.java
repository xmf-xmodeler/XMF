package undo;

/******************************************************************************
 *                                                                            *
 *                            Array Update Command                            *
 *             ------------------------------------------------               *
 *                                                                            *
 *  Records the information necessary to undo an array update.                *
 *                                                                            *
 ******************************************************************************/

import engine.Machine;

public class SetArray extends Command {
    
    int array;
    int index;
    int newValue;
    int oldValue;
    
    public SetArray(int array,int index,int newValue,int oldValue) {
        this.array = array;
        this.index = index;
        this.newValue = newValue;
        this.oldValue = oldValue;
    }
    
    public void gc(Machine machine) {
        array = machine.gcArray(array);
        newValue = machine.gcCopy(newValue);
        oldValue = machine.gcCopy(oldValue);
    }

    public void redo(Machine machine) {
        machine.arraySet(array,index,newValue);
    }

    public int size() {
        return 1;
    }

    public void undo(Machine machine) {
        machine.arraySet(array,index,oldValue);
    }

}
