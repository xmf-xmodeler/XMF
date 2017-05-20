package gc;

import java.util.Arrays;

import engine.Machine;

public class AllInstances extends GC {
	
	// This GC plugin calculates all the direct instances of a collection of
	// classes. A direct instance is an object whose .of() value is a member
	// of the supplied classes. The plugin is designed to work with the minimum
	// amount of allocation.

  int[] classes;

  public AllInstances(Machine machine, int[] classes) {
    super(machine, true);
    this.classes = classes;
  }

  public int gcObj(int obj) {
  	
  	// Called when an object is encountered by GC. The object is pushed on 
  	// the stack if its type is a member of the supplied list of classes...
  	
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
  	
  	// Called when the GC is complete. The instances of the classes are
  	// on the stack. They are popped off and consed into a list that is 
  	// returned. Note that hash tables need to be rehashed post GC and
  	// are pushed onto the stack for that purpose...
  	
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
