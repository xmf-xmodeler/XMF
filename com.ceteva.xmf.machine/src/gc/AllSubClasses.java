package gc;

import java.util.HashSet;

import engine.Machine;

public class AllSubClasses extends GC {

	int							_class;
	HashSet<Integer>	objects	= new HashSet<Integer>();

	public AllSubClasses(Machine machine, int _class) {
		super(machine, true);
		this._class = _class;
	}

	public int gcObj(int obj) {
		int newObj = super.gcObj(obj);
		if (obj == _class) _class = newObj;
		objects.add(newObj);
		return newObj;
	}

	private boolean isSubClass(int c) {
		int type = machine.objType(c);
		return (c == _class) || (isMetaClass(type) && inherits(c));
	}

	private boolean inherits(int c) {
		int parents = machine.asSeq(machine.objAttValue(c, machine.theSymbolParents));
		while (parents != Machine.nilValue) {
			int parent = machine.consHead(parents);
			parents = machine.consTail(parents);
			if (parent == _class || inherits(parent)) return true;
		}
		return false;
	}

	private boolean isMetaClass(int type) {
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
		super.gcPopStack();
		int allInstances = Machine.nilValue;
		HashSet<Integer> classes = new HashSet<Integer>();
		for(int obj : objects)
			classes.add(machine.objType(obj));
		for (int type : classes) {
			if (isSubClass(type)) allInstances = machine.mkCons(type, allInstances);
		}
		machine.pushStack(allInstances);
	}

}
