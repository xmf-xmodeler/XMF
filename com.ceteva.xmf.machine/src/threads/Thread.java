package threads;

/******************************************************************************
 *                                                                            *
 *                                   Threads                                  *
 *             ------------------------------------------------               *
 *                                                                            *
 *  The XMF VM is multi-threaded. Each thread is scheduled by the VM and gains*
 *  control of the machine until it yields either explicitly or implicitly    *
 *  (for example by blocking on a read). A thread contains the machine stack  *
 *  which is swapped in and out of the VM when the thread is scheduled and    *
 *  de-scheduled. Threads also have properties that indicate the state of the *
 *  thread. Threads are maintained as a circular linked list in the VM so each*
 *  thread has a next and a previous thread.                                  *
 *                                                                            *
 ******************************************************************************/

import java.util.Vector;

import values.ValueStack;
import engine.Machine;
import engine.StackFrame;

public class Thread implements java.io.Serializable, StackFrame {

  // A thread consists of a machine stack and the values of the machine
  // registers that relate to the stack. A machine may have multiple
  // threads, one of which is current at any given time. Threads are
  // maintained in a cyclic doubly linked list so that new threads can
  // easily be inserted and existing threads can be easily removed.

  private static final long     serialVersionUID = 1L;

  // A thread is in one of the following states:

  public static final int       ACTIVE           = 0;

  public static final int       BLOCK_READ       = 1;

  public static final int       BLOCK_ACCEPT     = 2;

  public static final int       SLEEPING         = 3;

  public static final int       DEAD             = 4;

  public static final int       BREAKPOINT       = 5;

  private String                name;                                          // The name of the thread.

  private int                   id;                                            // A unique identifier for the thread.

  private ValueStack            stack;                                         // A stack of call frames.

  private int                   currentFrame;                                  // The current call frame in stack.

  private int                   openFrame;                                     // The current open frame in stack.

  private Thread                next;                                          // The next thread (could be this);

  private Thread                prev;                                          // The previous thread (could be this).

  private int                   state            = ACTIVE;                     // The current state of the thread.

  private int                   inputChannel;                                  // Blocking input channel.

  private String                client;                                        // Blocking client name.

  private Vector<ThreadMonitor> monitors         = new Vector<ThreadMonitor>();

  private ThreadInitiator       initiator        = new ThreadInitiator();      // Starts the thread.

  private ClassLoader           classLoader      = null;                       // Class loader to use during this thread.

  public Thread(int id, String name, ValueStack stack, int currentFrame, int openFrame, int state) {
    this.id = id;
    this.name = name;
    this.stack = stack;
    this.currentFrame = currentFrame;
    this.openFrame = openFrame;
    this.state = state;
    this.next = this;
    this.prev = this;
  }

  public Thread(String name, ValueStack stack, int currentFrame, int openFrame) {
    this.name = name;
    this.stack = stack;
    this.currentFrame = currentFrame;
    this.openFrame = openFrame;
    this.next = this;
    this.prev = this;
  }

  public Thread(String name, ValueStack stack, int currentFrame, int openFrame, ThreadInitiator initiator, ClassLoader classLoader) {
    this.name = name;
    this.stack = stack;
    this.currentFrame = currentFrame;
    this.openFrame = openFrame;
    this.next = this;
    this.prev = this;
    this.initiator = initiator;
    this.classLoader = classLoader;
  }

  public Thread add(Thread thread) {

    // A new thread is added to an existing thread.
    // The new thread is added **after** the current
    // thread and is returned as the new thread.
    // The act of adding the thread to the current
    // thread ring causes the thread to be allocated
    // an identifier.

    Thread existingThread = threadNamed(thread.getName());
    if (thread.isNamed() && existingThread != null) {
      System.err.println("WARNING: replacing thread named " + thread.getName());
      existingThread.overwriteWith(thread);
      return existingThread;
    } else {
      thread.next = next;
      thread.prev = this;
      next.prev = thread;
      this.next = thread;
      thread.id = allocateId();
      return thread;
    }
  }

  public int allocateId() {
    Thread t = this;
    int id = 0;
    do {
      id = Math.max(id, t.id());
      t = t.next;
    } while (t != this);
    return id + 1;
  }

  public String allToString() {
    String s = "";
    Thread thread = this;
    do {
      s = s + thread.toString();
      thread = thread.next;
      if (thread != this) s = s + ",";
    } while (thread != this);
    return s;
  }

  public void blockOnAccept(String client) {

    // The thread is placed in a waiting state and is woken up
    // when the named client connects to XOS.

    state = BLOCK_ACCEPT;
    this.client = client;
  }

  public void blockOnRead(int inputChannel) {

    // The thread is placed in a waiting state and is woken up
    // when the indexed input channel is ready to produce some
    // input.

    state = BLOCK_READ;
    this.client = "";
    this.inputChannel = inputChannel;
  }

  public void blockOnRead(String client) {

    state = BLOCK_READ;
    this.inputChannel = -1;
    this.client = client;
  }

  public void breakpoint() {
    state = BREAKPOINT;
  }

  public String client() {
    return client;
  }

  public int currentFrame() {
    return currentFrame;
  }

  public String funName(Machine machine, int frame) {
    return machine.valueToString(machine.codeBoxName(stack.ref(frame + FRAMECODEBOX)));
  }

  public ClassLoader getClassLoader() {
    return classLoader;
  }

  public int getCurrentFrame() {
    return currentFrame;
  }

  public ThreadInitiator getInitiator() {
    return initiator;
  }

  public String getName() {
    return name;
  }

  public int getOpenFrame() {
    return openFrame;
  }

  public ValueStack getStack() {
    return stack;
  }

  public int id() {
    return id;
  }

  public int inputChannel() {
    return inputChannel;
  }

  public boolean isNamed() {
    return name != null && !name.equals("");
  }

  public boolean isSleeping() {
    return state == SLEEPING;
  }

  public void kill() {
    state = DEAD;
    int result = stack.top();
    for (ThreadMonitor m : monitors)
      m.threadDies(this, result);
  }

  public int length() {
    int length = 1;
    Thread thread = this.next();
    while (thread != this) {
      length++;
      thread = thread.next();
    }
    return length;
  }

  public int line(int frame) {
    return Machine.value(stack.ref(frame + FRAMELINECOUNT));
  }

  public int local(int frame, int index) {
    return stack.ref(frame + FRAMELOCAL0 + index);
  }

  public Vector<String> localNames(Machine machine, int frame) {
    int supers = stack.ref(frame + FRAMESUPER);
    int fun = machine.consHead(supers);
    int arity = machine.funArity(fun);
    int args = machine.funArgNames(fun);
    Vector<String> localNames = new Vector<String>();
    while (args != Machine.nilValue) {
      localNames.add(machine.valueToString(machine.consHead(args)));
      args = machine.consTail(args);
    }
    int frameLocals = Machine.value(stack.ref(frame + FRAMELOCALS));
    int locals = (frameLocals - arity) / 2;
    for (int i = 0; i < locals; i++) {
      int localName = stack.ref(frame + FRAMELOCAL0 + arity + locals + i);
      if (localName != Machine.undefinedValue) localNames.add(machine.valueToString(localName));
    }
    return localNames;

  }

  public void monitor(ThreadMonitor monitor) {
    monitors.addElement(monitor);
  }

  public Thread next() {
    return next;
  }

  public int openFrame() {
    return openFrame;
  }

  private void overwriteWith(Thread thread) {
    name = thread.name;
    stack = thread.stack;
    currentFrame = thread.currentFrame;
    openFrame = thread.openFrame;
    state = thread.state;
    inputChannel = thread.inputChannel;
    client = thread.client;
    monitors = thread.monitors;
    initiator = thread.initiator;
    classLoader = thread.classLoader;
  }

  public int prevFrame(int frame) {
    return stack.ref(frame + PREVFRAME);
  }

  public Thread remove() {

    // Remove ourself from the cycle of threads.
    // If we are the only thread then return 'null'.

    if (singleThread())
      return null;
    else {
      prev.next = next;
      next.prev = prev;
      return next;
    }
  }

  public String resourceName(Machine machine, int frame) {
    return machine.valueToString(machine.codeBoxResourceName(stack.ref(frame + FRAMECODEBOX)));
  }

  public void setClassLoader(ClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  public void setClient(String client) {
    this.client = client;
  }

  public void setCurrentFrame(int currentFrame) {
    this.currentFrame = currentFrame;
  }

  public void setInitiator(ThreadInitiator initiator) {
    this.initiator = initiator;
  }

  public void setName(String name) {
    this.name = name;
  }

  public void setOpenFrame(int openFrame) {
    this.openFrame = openFrame;
  }

  public void setStack(ValueStack stack) {
    this.stack = stack;
  }

  public void setState(int state) {
    this.state = state;
  }

  public boolean singleThread() {
    // return true when receiver is the only
    // thread in the cycle.
    return next == this;
  }

  public void sleep() {
    state = SLEEPING;
  }

  public ValueStack stack() {
    return stack;
  }

  public int state() {
    return state;
  }

  public String stateToString() {
    switch (state) {
    case ACTIVE:
      return "ACTIVE";
    case BLOCK_READ:
      return "BLOCK_READ";
    case BLOCK_ACCEPT:
      return "BLOCK_ACCEPT";
    case SLEEPING:
      return "SLEEPING";
    case BREAKPOINT:
      return "BREAKPOINT";
    default:
      return "?";
    }
  }

  public Thread threadNamed(String name) {
    Thread t = this;
    do {
      if (t.getName().equals(name)) return t;
      t = t.next;
    } while (t != this);
    return null;
  }

  public String toString() {
    return "Thread(" + name + "," + id + "," + stateToString() + "," + currentFrame + "," + classLoader + "," + client + ")";
  }

  public void wake(int value) {
    state = ACTIVE;
    stack.push(value);
  }

}