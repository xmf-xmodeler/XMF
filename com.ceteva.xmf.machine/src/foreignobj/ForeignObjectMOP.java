package foreignobj;

/******************************************************************************
 *                                                                            *
 *                   Foreign Object Meta Object Protocol                      *
 *             ------------------------------------------------               *
 *                                                                            *
 *  Field access and method invocation (via '.') is provided by a foreign     *
 *  object MOP associated with the type of Java objects that are wrapped as   *
 *  foreign objects in the VM. This class provides the standard MOP that just *
 *  references fields and invokes methods. Sub-classes can be defined to      *
 *  provide bespoke implementations of dot, send, setSlot, hasSlot and equals.*
 *                                                                            *
 ******************************************************************************/

import engine.Machine;
import xjava.XJ;

public class ForeignObjectMOP {

    // This class implement the MOP for a foreign object. The object is
    // implemented in Java, access to its key features required by the
    // VM are implemented by this class. Sub-classes can be defined and
    // mapped to appropriate Java classes in the foreignMOPMapping table
    // maintained by the VM.

    public static final ForeignObjectMOP value = new ForeignObjectMOP();

    public void dot(Machine machine, int object, int name) {

	// Returns the value of the referenced field by pushing the
	// Java field field onto the machine stack. The value is
	// translated to an XMF value as appropriate...

	ForeignObject f = machine.getForeignObject(object);
	String string = machine.valueToString(machine.symbolName(name));
	int value = XJ.getSlot(machine, f.getObject(), string);
	if (value == -1)
	    machine.sendSlotMissing(object, name);
	else
	    machine.pushStack(value);
    }

    public int getOperations(Machine machine, int type, int message, int arity) {
	int ops = machine.operatorPrecedenceList(type);
	return machine.findOperation(ops, message, arity);
    }

    public void noOperationFound(Machine machine, int target, int message, int args) {
	machine.openFrame();
	machine.pushStack(message);
	machine.pushStack(args);
	machine.pushStack(target);
	machine.send(2, machine.mkSymbol("noOperationFound"));
    }

    public void send(Machine machine, int target, int message, int args) {

	// A foreign object has been sent a message. If the message
	// is implemented by the XMF class 'type' then it is handled
	// in the normal way. Otherwise the message may be handled by
	// a Java method. Otherwise, a message not found error is raised.

	if (!handleByXOCL(machine, target, message, args))
	    if (!handleByJava(machine, target, message, args))
		noOperationFound(machine, target, message, args);

    }

    public boolean handleByJava(Machine machine, int target, int message, int args) {

	// Find an appropriate Java method, translate the supplied XMF
	// argument values to Java values and invoke the method. The return
	// value from the method is then translated as the return value...

	ForeignObject foreignObject = machine.getForeignObject(target);
	Object object = foreignObject.getObject();
	String string = machine.valueToString(machine.symbolName(message));
	int result = XJ.send(machine, object, string, args);
	if (result == -1)
	    return false;
	else {
	    machine.pushStack(result);
	    return true;
	}
    }

    public boolean handleByXOCL(Machine machine, int target, int message, int args) {

	// Calculate the operation that can handle the message and
	// return true if one is found and set up on the machine.
	// Return false otherwise...

	ForeignObject object = machine.getForeignObject(target);
	int type = object.getType();
	int arity = machine.consLength(args);
	int ops = getOperations(machine, type, message, arity);

	if (ops == Machine.nilValue)
	    return false;
	else {
	    int op = machine.consHead(ops);
	    machine.openFrame();
	    while (args != Machine.nilValue) {
		machine.pushStack(machine.consHead(args));
		args = machine.consTail(args);
	    }
	    if (Machine.isFun(op)) {
		if (machine.funIsVarArgs(op) == Machine.trueValue)
		    machine.adjustVarArgs(op, arity);
		if (machine.funTraced(op) != Machine.undefinedValue)
		    machine.enterTracedFun(op, arity, target, ops);
		else
		    machine.enterFun(op, arity, target, ops);
	    } else
		machine.invokeObj(op, target, arity);
	    return true;
	}
    }

    public boolean hasSlot(Machine machine, int foreignObj, int name) {
	ForeignObject f = machine.getForeignObject(foreignObj);
	String string = machine.valueToString(machine.symbolName(name));
	return XJ.hasSlot(f.getObject(), string);
    }

    public void set(Machine machine, int obj, int name, int value) {

	// Updates the slot of the object and pushes the object back on
	// the stack. Sends a slotMissing message if the slot does not exist...

	ForeignObject f = machine.getForeignObject(obj);
	String string = machine.valueToString(machine.symbolName(name));
	if (XJ.setSlot(machine, f.getObject(), string, value) == -1)
	    machine.sendSlotMissing(obj, name, value);
	else
	    machine.pushStack(obj);
    }

    public boolean equalObjects(Object o1, Object o2) {

	// Returns true when the underlying obejcts are the same...

	return o1 == o2;
    }

}
