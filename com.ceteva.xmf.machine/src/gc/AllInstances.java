package gc;

import java.util.Arrays;

import engine.Machine;

public class AllInstances extends GC {

  int[] classes;

  public AllInstances(Machine machine, int[] classes) {
    super(machine, true);
    this.classes = classes;
  }

  public int gcObj(int obj) {
    int objType = machine.objType(obj);
    if (isClass(objType)) {
      int newObj = super.gcObj(obj);
      machine.pushStack(newObj);
      return newObj;
    } else return super.gcObj(obj);
  }

  private boolean isClass(int c) {
    for (int i : classes)
      if (i == c) return true;
    return false;
  }

  public void gcPopStack() {
    int allInstances = Machine.nilValue;
    while (machine.getStack().getTOS() != machine.getGCTOS()) {
      int value = machine.getStack().pop();
      switch (Machine.tag(value)) {
      case HASHTABLE:
        machine.rehash(value);
        break;
      case OBJ:
        allInstances = machine.mkCons(value, allInstances);
        break;
      }
    }
    machine.pushStack(allInstances);
  }

}
