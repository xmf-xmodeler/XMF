package gc;

import threads.Thread;
import values.Value;
import values.ValueStack;
import engine.Machine;

public class GC implements Value {

  // This class implements an extensible garbage collector for XMF. In principle, it can replace
  // the garbage collector in Engine once it has stabilized. The motivation is that this class can
  // be extended in order to support applications that need to sweep the heap. Notice that since
  // the sweep is taking place during a GC, allocation of new data should be minimal. Also notice
  // that the GC sweep leaves hash-tables on the stack so that they can be re-hashed at the end.
  // Therefore, if an extension of GC wants to leave data on the stack, it should tag them in some
  // way to differentiate them from the hash-tables.

  Machine machine;

  boolean silent = false;

  public GC(Machine machine, boolean silent) {
    this.machine = machine;
    this.silent = silent;
  }

  public int gcArray(int array) {
    if (machine.collected(array))
      return machine.forward(ARRAY, array);
    else {
      int newArray = machine.gcArray(array);
      machine.setForward(array, newArray);
      return newArray;
    }
  }

  public int gcBigInt(int bigInt) {
    if (machine.collected(bigInt))
      return machine.forward(BIGINT, bigInt);
    else {
      int newString = gcString(bigInt);
      return Machine.mkPtr(BIGINT, Machine.value(newString));
    }
  }

  public int gcBuffer(int buffer) {
    if (machine.collected(buffer))
      return machine.forward(BUFFER, buffer);
    else {
      int increment = machine.bufferIncrement(buffer);
      int daemons = machine.bufferDaemons(buffer);
      int daemonsActive = machine.bufferDaemonsActive(buffer);
      int storage = machine.bufferStorage(buffer);
      int size = machine.bufferSize(buffer);
      int asString = machine.bufferAsString(buffer);
      machine.swapHeap();
      int newBuffer = machine.mkBuffer();
      machine.bufferSetIncrement(newBuffer, increment);
      machine.bufferSetDaemons(newBuffer, daemons);
      machine.bufferSetDaemonsActive(newBuffer, daemonsActive);
      machine.bufferSetStorage(newBuffer, storage);
      machine.bufferSetSize(newBuffer, size);
      machine.bufferSetAsString(newBuffer, asString);
      machine.swapHeap();
      machine.setForward(buffer, newBuffer);
      return newBuffer;
    }
  }

  public int gcCode(int code) {
    if (machine.collected(code))
      return machine.forward(CODE, code);
    else {
      int newCode = machine.gcCode(code);
      machine.setForward(code, newCode);
      return newCode;
    }
  }

  public int gcCodeBox(int codeBox) {
    if (machine.collected(codeBox))
      return machine.forward(CODEBOX, codeBox);
    else {
      int newCodeBox = machine.gcCodeBox(codeBox);
      machine.setForward(codeBox, newCodeBox);
      return newCodeBox;
    }
  }

  public void gcComplete() {

    // Called once the initial roots have been copied.
    // The copied pointer trails the free pointer and
    // refers to the contents of the original heap
    // that must be replaced with newly created elements
    // in the new heap...

    int gcCopiedPtr = machine.getGCCopiedPtr();
    int[] gcHeap = machine.getGCHeap();

    while (machine.getGCFreePtr() != gcCopiedPtr) {
      int value = gcHeap[gcCopiedPtr];
      switch (Machine.tag(value)) {
      case ARRAY:
        gcHeap[gcCopiedPtr++] = gcArray(value);
        break;
      case BUFFER:
        gcHeap[gcCopiedPtr++] = gcBuffer(value);
        break;
      case BOOL:
        gcCopiedPtr++;
        break;
      case CODE:
        gcHeap[gcCopiedPtr++] = gcCode(value);
        break;
      case CODEBOX:
        gcHeap[gcCopiedPtr++] = gcCodeBox(value);
        break;
      case CODELENGTH:
        gcCopiedPtr = gcCopiedPtr + Machine.value(value) + 1;
        break;
      case CONT:
        gcHeap[gcCopiedPtr++] = gcCont(value);
        break;
      case FOREIGNFUN:
        gcCopiedPtr++;
        break;
      case FOREIGNOBJ:
        gcCopiedPtr++;
        break;
      case FUN:
        gcHeap[gcCopiedPtr++] = gcFun(value);
        break;
      case INT:
      case NEGINT:
        gcCopiedPtr++;
        break;
      case OBJ:
        gcHeap[gcCopiedPtr++] = gcObj(value);
        break;
      case STRING:
        gcHeap[gcCopiedPtr++] = gcString(value);
        break;
      case STRINGLENGTH:
        gcCopiedPtr = gcCopiedPtr + (Machine.value(value) / 4) + 1;
        break;
      case UNDEFINED:
        gcCopiedPtr++;
        break;
      case CONS:
        gcHeap[gcCopiedPtr++] = gcCons(value);
        break;
      case NIL:
        gcCopiedPtr++;
        break;
      case SYMBOL:
        gcHeap[gcCopiedPtr++] = gcSymbol(value);
        break;
      case SET:
        gcHeap[gcCopiedPtr++] = gcSet(value);
        break;
      case INPUT_CHANNEL:
      case OUTPUT_CHANNEL:
      case CLIENT:
      case THREAD:
      case DATABASE:
      case QUERYRESULT:
        gcCopiedPtr++;
        break;
      case HASHTABLE:
        gcHeap[gcCopiedPtr++] = gcHashTable(value);
        break;
      case FLOAT:
        gcHeap[gcCopiedPtr++] = gcFloat(value);
        break;
      case DAEMON:
        gcHeap[gcCopiedPtr++] = gcDaemon(value);
        break;
      case FORWARDREF:
        gcHeap[gcCopiedPtr++] = gcForwardRef(value);
        break;
      case ILLEGAL:
        // Arises when a continuation is garbage collected.
        // since the continuation contains a stack copy and
        // the stack contains machine words or the special -1.
        gcCopiedPtr++;
        break;
      case BIGINT:
        gcHeap[gcCopiedPtr++] = gcBigInt(value);
        break;
      default:
        System.err.println("gcComplete: unknown value tag: " + Machine.tag(value));
      }
    }
    machine.setGCCopiedPtr(gcCopiedPtr);
  }

  public int gcCons(int cons) {
    if (machine.collected(cons))
      return machine.forward(CONS, cons);
    else {
      int newCons = machine.gcCons(cons);
      machine.setForward(cons, newCons);
      return newCons;
    }
  }

  public int gcCont(int cont) {
    if (machine.collected(cont))
      return machine.forward(CONT, cont);
    else {
      int length = machine.contLength(cont);
      int currFrame = machine.contCurrentFrame(cont);
      int openFrame = machine.contOpenFrame(cont);
      machine.swapHeap();
      int newCont = machine.mkCont(length);
      machine.contSetCurrentFrame(newCont, Machine.mkInt(currFrame));
      machine.contSetOpenFrame(newCont, Machine.mkInt(openFrame));
      machine.swapHeap();
      for (int i = 0; i < length; i++) {
        int value = machine.contRef(cont, i);
        machine.swapHeap();
        machine.contSet(newCont, i, value);
        machine.swapHeap();
      }
      machine.setForward(cont, newCont);
      return newCont;
    }

  }

  public int gcCopy(int word) {

    // Copy a data structure into the new heap returning a machine word
    // with respect to the new heap...

    switch (Machine.tag(word)) {
    case ARRAY:
      return gcArray(word);
    case BUFFER:
      return gcBuffer(word);
    case BOOL:
      return word;
    case CODEBOX:
      return gcCodeBox(word);
    case CODE:
      return gcCode(word);
    case CONT:
      return gcCont(word);
    case INT:
    case NEGINT:
      return word;
    case FOREIGNFUN:
      return word;
    case FOREIGNOBJ:
      return word;
    case FUN:
      return gcFun(word);
    case OBJ:
      return gcObj(word);
    case STRING:
      return gcString(word);
    case UNDEFINED:
      return word;
    case CONS:
      return gcCons(word);
    case SET:
      return gcSet(word);
    case NIL:
      return word;
    case SYMBOL:
      return gcSymbol(word);
    case INPUT_CHANNEL:
    case OUTPUT_CHANNEL:
    case CLIENT:
    case THREAD:
      return word;
    case HASHTABLE:
      return gcHashTable(word);
    case FLOAT:
      return gcFloat(word);
    case DAEMON:
      return gcDaemon(word);
    case FORWARDREF:
      return gcForwardRef(word);
    default:
      System.err.println("Machine.gcCopy: unknown type tag " + Machine.tag(word));
      return word;
    }
  }

  public int gcDaemon(int daemon) {
    if (machine.collected(daemon))
      return machine.forward(DAEMON, daemon);
    else {
      int newDaemon = machine.gcDaemon(daemon);
      machine.setForward(daemon, newDaemon);
      return newDaemon;
    }
  }

  public int gcFloat(int f) {
    if (machine.collected(f))
      return machine.forward(FLOAT, f);
    else {
      int str = machine.floatString(f);
      machine.swapHeap();
      int newFloat = machine.mkFloat();
      machine.floatSetString(newFloat, str);
      machine.swapHeap();
      machine.setForward(f, newFloat);
      return newFloat;
    }
  }

  public int gcForwardRef(int forwardRef) {

    // If we can resolve the forward ref then
    // do so silently....

    if (machine.collected(forwardRef))
      return machine.forward(FORWARDREF, forwardRef);
    else {
      int value = machine.forwardRefValue(forwardRef);
      if (value != Machine.undefinedValue)
        return gcCopy(value);
      else {
        int path = machine.forwardRefPath(forwardRef);
        int listeners = machine.forwardRefListeners(forwardRef);
        machine.swapHeap();
        int newForwardRef = machine.mkForwardRef(path);
        machine.forwardRefSetListeners(newForwardRef, listeners);
        machine.swapHeap();
        machine.setForward(forwardRef, newForwardRef);
        return newForwardRef;
      }
    }
  }

  public int gcFun(int fun) {
    if (machine.collected(fun))
      return machine.forward(FUN, fun);
    else {
      int newFun = machine.gcFun(fun);
      machine.setForward(fun, newFun);
      return newFun;
    }
  }

  public int gcHashTable(int table) {

    // To garbage collect a hashtable we must take into
    // account the hash codes of the keys in the table.
    // Garbage collection may cause the hash codes to change
    // since the codes may be computed on the basis of the
    // machine address of the key. It is therefore important
    // to rehash the table into the new space.

    if (machine.collected(table))
      return machine.forward(HASHTABLE, table);
    else {
      int newTable = machine.gcTable(table);
      machine.setForward(table, newTable);
      machine.pushStack(newTable);
      return newTable;
    }
  }

  public int gcObj(int obj) {
    if (machine.collected(obj))
      return machine.forward(OBJ, obj);
    else {
      int newObj = machine.gcCopyObj(obj);
      machine.setForward(obj, newObj);
      return newObj;
    }
  }

  public void gcPopStack() {
    while (machine.getStack().getTOS() != machine.getGCTOS()) {
      int value = machine.getStack().pop();
      switch (Machine.tag(value)) {
      case HASHTABLE:
        machine.rehash(value);
        break;
      }
    }
  }

  public int gcSet(int set) {
    if (machine.collected(set))
      return machine.forward(SET, set);
    else {
      int elements = machine.setElements(set);
      machine.swapHeap();
      int newSet = machine.mkSet(elements);
      machine.swapHeap();
      machine.setForward(set, newSet);
      return newSet;
    }
  }

  public void gcStack() {

    // Most accessible structures are found by following values on the
    // stack. To save time the garbage collector does not bother with
    // stack frames it just zooms up the stack and collects all values...

    Thread t = machine.currentThread().next();
    while (t != machine.currentThread()) {
      ValueStack stack = t.stack();
      gcStack(stack, stack.getTOS());
      t = t.next();
    }
    gcStack(machine.getStack(), machine.getGCTOS());
  }

  public void gcStack(ValueStack stack, int TOS) {
    for (int i = 0; i < TOS; i++) {
      int value = stack.ref(i);

      // Really all values on the stack should be tagged.
      // -1 is used for the initial values of the frame registers...

      if (value != -1) stack.set(i, gcCopy(value));
    }
  }

  public int gcString(int string) {
    if (machine.collected(string))
      return machine.forward(STRING, string);
    else {
      int newString = machine.gcString(string);
      machine.setForward(string, newString);
      return newString;
    }
  }

  public int gcSymbol(int symbol) {
    if (machine.collected(symbol))
      return machine.forward(SYMBOL, symbol);
    else {
      int newSymbol = machine.gcSymbol(symbol);
      machine.setForward(symbol, newSymbol);
      return newSymbol;
    }
  }

  public Machine getMachine() {
    return machine;
  }

  public boolean isSilent() {
	  return silent;
  }

  public void setMachine(Machine machine) {
    this.machine = machine;
  }

  public void setSilent(boolean silent) {
    this.silent = silent;
  }

}
