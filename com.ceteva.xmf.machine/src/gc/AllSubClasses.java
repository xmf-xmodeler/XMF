package gc;

import java.util.HashSet;

import engine.Machine;

public class AllSubClasses extends GC {
	
	// This GC class calculates all the subclasses of a supplied class _class.
	// It does so by making a pass over the heap and capturing all
	// objects then filtering those objects that are both classes
	// and that are either _class or reference _class in their parents.
	// Note that this is inefficient in the sense that it allocates a large
	// Java HashSet in order to retain the XMF objects before filtering them.
	// The filtering cannot take place until the GC is complete since the 
	// symbols and objects that are used in the filter need to be collected
	// before they can be referenced.

	int								_class;
	boolean						resetClass	= false;
	HashSet<Integer>	objects			= new HashSet<Integer>();

	public AllSubClasses(Machine machine, int _class) {
		super(machine, true);
		this._class = _class;
	}

	public int gcObj(int obj) {
		if (machine.collected(obj))
			return machine.forward(OBJ, obj);
		else {
			
			// Process the supplied object in the normal way.
			// If the object is the class we are looking for then
			// update the reference to be the copied object.
			// Keep a reference ot the object for filtering...
			
			int newObj = super.gcObj(obj);
			if (obj == _class && !resetClass) {
				resetClass = true;
				_class = newObj;
			}
			objects.add(newObj);
			return newObj;
		}
	}

	private boolean isSubClass(int c, int parent) {
		int type = machine.objType(c);
		return (c == parent) || (isMetaClass(type) && inherits(c,parent));
	}

	private boolean inherits(int c, int parent) {
		
		// Returns true when c has a transitive 'parents' link
		// to the class parent...
		
		int parents = machine.asSeq(machine.objAttValue(c, machine.theSymbolParents));
		while (parents != Machine.nilValue) {
			int p = machine.consHead(parents);
			parents = machine.consTail(parents);
			if (p == parent || inherits(p,parent)) return true;
		}
		return false;
	}

	private boolean isMetaClass(int type) {
		
		// Returns true when the supplied type is a meta-class. This is
		// defined to be when type is Class or when it inherits from
		// Class. Note that really this should be Classifier instead of 
		// Class, but machine does not provide a direct reference to
		// Classifier and most cases should be covered by Class...
		
		if (type == machine.theClassClass)
			return true;
		else {
			int parents = machine.asSeq(machine.objAttValue(type, machine.theSymbolParents));
			while (parents != Machine.nilValue) {
				int parent = machine.consHead(parents);
				parents = machine.consTail(parents);
				if (isMetaClass(parent)) return true;
			}
			return false;
		}
	}

	public void gcPopStack() {
		
		// Called when the GC is complete. All the objects are filtered
		// to select the objects that are classes and that inherit from
		// the supplied _class...
		
		super.gcPopStack();
		int allInstances = Machine.nilValue;
		HashSet<Integer> classes = new HashSet<Integer>();
		for (int obj : objects)
			if (isMetaClass(machine.objType(obj))) classes.add(obj);
		for (int type : classes) {
			if (isSubClass(type, _class)) allInstances = machine.mkCons(type, allInstances);
		}
		machine.pushStack(allInstances);
	}

}
