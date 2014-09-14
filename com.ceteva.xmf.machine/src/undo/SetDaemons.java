package undo;

/******************************************************************************
 *                                                                            *
 *                             Set Daemons Command                            *
 *             ------------------------------------------------               *
 *                                                                            *
 *  Records the information necessary to undo the update of an object's       *
 *  daemons.                                                                  *
 *                                                                            *
 ******************************************************************************/

import engine.Machine;

public class SetDaemons extends Command {
    
    int object;
    int newDaemons;
    int oldDaemons;
    
    public SetDaemons(int object,int newDaemons,int oldDaemons) {
        this.object = object;
        this.newDaemons = newDaemons;
        this.oldDaemons = oldDaemons;
    }

    public void gc(Machine machine) {
        object = machine.gcCopy(object);
        newDaemons = machine.gcCopy(newDaemons);
        oldDaemons = machine.gcCopy(oldDaemons);
    }

    public void redo(Machine machine) {
        machine.objSetDaemons(object, oldDaemons);
    }

    public int size() {
        return 1;
    }

    public void undo(Machine machine) {
        machine.objSetDaemons(object, oldDaemons);
    }

}
