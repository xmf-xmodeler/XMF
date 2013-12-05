package undo;

/******************************************************************************
 *                                                                            *
 *                            Slot Update Command                             *
 *             ------------------------------------------------               *
 *                                                                            *
 *  Records the information necessary to undo a slot update.                  *
 *                                                                            *
 ******************************************************************************/

import engine.Machine;

public class SetSlot extends Command {
    
    int object;
    int slot;
    int newValue;
    int oldValue;
    
    public SetSlot(int object,int slot,int newValue,int oldValue) {
        this.object = object;
        this.slot = slot;
        this.newValue = newValue;
        this.oldValue = oldValue;
    }
    
    public void gc(Machine machine) {
        object = machine.gcObj(object);
        slot = machine.gcCopy(slot);
        newValue = machine.gcCopy(newValue);
        oldValue = machine.gcCopy(oldValue);
    }

    public void redo(Machine machine) {
        machine.objSetAttValue(object,slot,newValue);
    }

    public int size() {
        return 1;
    }

    public void undo(Machine machine) {
        machine.objSetAttValue(object,slot,oldValue);
    }

}
