package undo;

/******************************************************************************
 *                                                                            *
 *                        Array Update Daemons Command                        *
 *             ------------------------------------------------               *
 *                                                                            *
 *  Records the information necessary to undo the update of array daemons.    *
 *                                                                            *
 ******************************************************************************/

import engine.Machine;

public class SetArrayDaemons extends Command {
    
    int array;
    int newDaemons;
    int oldDaemons;

    public SetArrayDaemons(int array, int newDaemons, int oldDaemons) {
        this.array = array;
        this.newDaemons = newDaemons;
        this.oldDaemons = oldDaemons;
    }

    public void gc(Machine machine) {
        array = machine.gcArray(array);
        newDaemons = machine.gcCopy(newDaemons);
        oldDaemons = machine.gcCopy(oldDaemons);
    }

    public void redo(Machine machine) {
        machine.arraySetDaemons(array, oldDaemons);
    }

    public int size() {
        return 1;
    }

    public void undo(Machine machine) {
        machine.arraySetDaemons(array, oldDaemons);
    }


}
