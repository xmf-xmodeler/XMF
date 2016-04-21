/******************************************************************************
 *                                                                            *
 *                         The XMF Virtual Machine                            *
 *                         -----------------------                            *
 *                                                                            *
 *  The XMF VM runs a byte-coded instruction set on a stack-based machine     *
 *  with a single heap. The VM manages a fixed collection of data types that  *
 *  can represent a wide-variety of languages. The key features of the data   *
 *  types are from OOP and FP. The heap is represented as a vector of byte    *
 *  tagged integers. This allows very quick storage and retrieval of saved    *
 *  heap structures. The VM is multi-threaded.                                *
 *                                                                            *
 *  The VM does not manage its own IO, or the scheduling of its threads. That *
 *  is left to the underlying operating-system called XOS.                    *
 *                                                                            *
 *  Data structures are allocated on the heap and not explicitly freed by the *
 *  languages running on the VM. When the heap is exhausted the VM will       *
 *  invoke the garbage collector. This is a stop-and-copy scheme whereby all  *
 *  reachable data is copied into a new heap leaving unreachable data behind. *
 *                                                                            *
 *  It is possible to have multiple VMs running at the same time. The threads *
 *  on the same VM all share the same heap. Different VMs are completely      *
 *  separate.                                                                 *
 *                                                                            *
 *  A VM has two life-cycle modes: (1) from scratch, a VM is started and a    *
 *  file of instructions is loaded and run. This is how an initial VM image   *
 *  is created and saved to an img file. (2) loading an img file which        *
 *  continues executing from the point at which it was saved.                 *                                                                                                                                                                                                                             
 *                                                                            *
 ******************************************************************************/

package engine;

import images.Header;
import images.ImageSerializer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Stack;
import java.util.Vector;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import sun.misc.Signal;
import sun.misc.SignalHandler;
import threads.Thread;
import threads.ThreadInitiator;
import threads.ThreadMonitor;
import undo.UndoEngine;
import util.QuickSort;
import values.Daemons;
import values.Memory;
import values.ObjectProperties;
import values.Serializer;
import values.Value;
import values.ValueStack;
import xjava.XJ;
import xos.DChannel;
import xos.DataInputStream;
import xos.Message;
import xos.MessagePacket;
import xos.OperatingSystem;
import xos.TChannel;
import xos.XData;
import clients.ClientResult;
import debug.Debugger;
import debug.StepListener;
import errors.Errors;
import errors.MachineError;
import foreignfuns.ForeignFun;
import foreignfuns.ForeignFuns;
import foreignobj.ForeignObject;
import foreignobj.ForeignObjectMOP;
import gc.GC;

public final class Machine implements Words, Constants, ObjectProperties, Daemons, StackFrame, Instr, Value, Errors, SignalHandler {

  // The VM executes machine instructions with respect to a stack and
  // a heap. Machine words are Java integers in which the top byte is
  // used as a tag field and the rest is used to encode data. Stacks
  // and heaps are all integer arrays, therefore pointers are Java ints
  // that index array elements. The machine implements its own heap
  // management including a stop and copy garbage collector.

  // The heap is allocated to be heapSize words, unless the image that is
  // loaded requires a larger heap. Note that since XMF has a stop-and-
  // copy garbage collector then the actual allocation is twice heapSize...

  public int                         heapSize                  = HEAPSIZE;

  // The stack is allocated to be stackSize words. Increasing this will
  // allow increasing depths of call chains. Note that since XOCL supports
  // tail calling you should not need to make this too large...

  public int                         stackSize                 = STACKSIZE;

  // The amount of heap that is used before a garbage collect is invoked...

  public int                         gcLimit                   = HEAPSIZE - K;

  // The amount of heap we would like to keep free...

  public int                         freeHeap                  = 10 * K;

  // the amount of heap that is increased if necessary...

  public int                         incHeap                   = 10 * K;

  // When messages are sent, the VM will cache the operator found for a
  // given class and arity. This double level caching mechanism is implemented
  // using an operator table. The size of the operator table is set here...

  public int                         operatorTableSize         = K;

  // The VM will handle class instantiation if the class meets the
  // appropriate criteria. The constructor table is used to cache
  // information about the constructors for classes...

  public int                         constructorTableSize      = 100;

  // Set to be the file to load on startup. Should be a binary file...

  public String                      initFile                  = null;

  // Set to the image file to load on startup...

  public String                      imageFile                 = null;

  // Image args are supplied on startup in the form NAME:VALUE. These are
  // parsed and placed in a vector for processing...

  public Vector<String>              imageArgs                 = new Vector<String>();

  // The interrupt flag is set when the user causes an interrupt (usually
  // ^C). The VM handles the flag approprately...

  public static boolean /*static for testing because of infinity loop */                    interrupt                 = false;

  // Lots of occurrences of the empty array - so preallocate and reuse...

  public int                         emptyArray                = mkUndefined();

  // Lots of occurrences of the empty set - so preallocate and reuse...

  public int                         emptySet                  = mkUndefined();

  // Not used...

  public boolean                     stats                     = false;

  // Not used...

  public boolean                     stackDump                 = false;

  // Used to control the maximum size of a string when the system uses
  // the varioous toString methods...

  public int                         maxPrintElements          = 30;

  // Not used...

  public int                         maxPrintDepth             = 20;

  // Used to calculate relative time...

  public long                        time                      = System.currentTimeMillis();

  // The start of time as far as the VM is concerned...

  private long                       time0                     = System.currentTimeMillis();

  // The following argument specifications define the VM arguments that will
  // be
  // processed on startup. Each entry is of the form -NAME:ARGCOUNT and
  // specifies the name of a command line argument and the number of arguments
  // that are supplied...

  private String[]                   XVMargSpecs               = { "-instr:0", "-frames:0", "-stats:0", "-heapSize:1", "-stackSize:1", "-initFile:1", "-freeHeap:1", "-stackDump:0", "-image:1", "-arg:1" };

  // VM can be prined showing how many items of a given type have
  // been allocated. The memory table is used toc ontain the amount of
  // memory allocated to each type of data...

  public Memory                      memory                    = new Memory(time, null);

  // The number of instructions performed since startup...

  public int                         instrsPerformed           = 0;

  // The number of dynamic lookup steps performed since startup...

  public int                         dynamicLookupSteps        = 0;

  // The number of calls since startup...

  public int                         calls                     = 0;

  // The heap is an integer array represented as heap. Memory is
  // allocated (but not freed) until the heap is exhausted when a
  // garbage collect happens. The garbage collector swaps over
  // the heap and copies the used data into the new heap...

  private int[]                      words;

  // A pointer into the heap showing the next freely available
  // word...

  private int                        freePtr                   = 0;

  // The gcWords is a heap used to copy the current heap into
  // when the system garbage collects...

  private int[]                      gcWords;

  // The free pointer into the garbage collecting heap...

  private int                        gcFreePtr                 = 0;

  // Used by the garbage collector...

  private int                        gcCopiedPtr               = 0;

  // Thegarbage collector must record the top of the stack at the start
  // of a gc() since it uses the stack as a means of temporary
  // storage. When it exits the collector must ensure that the stack
  // is reset to the original TOS...

  private int                        gcTOS                     = 0;

  // Symbols are maintained in the symbol table where the keys are
  // the names of the symbols. When a new symbol is required, the
  // VM looks into the symbol table to see if there is already a
  // symbol with the required name...

  public int                         symbolTable;

  // The loader may encounter name lookups which existed when the
  // object was serialized, but does not exist when the value is loaded.
  // If this happens then the loader creates a forward reference
  // on the forwardRefs list. The upper levels can then process the
  // forward refs and replace the values appropriately...

  private int                        forwardRefs               = Machine.nilValue;

  // The underlying operating system is XOS. This supplies all
  // the machinery for handling threads and IO. The VM runs a thread
  // until it yields and then returns control to the operating system.
  // The operating system schedules a thread on the machine and then
  // starts the machine performing the instructions. When the VM is
  // requested to perform IO it asks the operating system to do it...

  private OperatingSystem            XOS;

  // The threads that are currently running on the VM...

  private Thread                     threads;

  // Set by a thread when it wants to yield. The VM will simply stop
  // and return to the operating system ...

  private boolean                    yield                     = false;

  // The call stack...

  private ValueStack                 valueStack;

  // The current frame executing on the call stack...

  private int                        currentFrame              = -1;

  // The frame currently under construction on the call stack...

  private int                        openFrame                 = -1;

  private Stack<ForeignFun>          foreignFuns               = new Stack<ForeignFun>();

  private Stack<ForeignObject>       foreignObjects            = new Stack<ForeignObject>();

  // A serializer encodes a VM data structure to an output stream.
  // The encoding is in the XMF byte coded serialization language...

  private Serializer                 serializer                = new Serializer(this);

  // The garbage collector provides a mechanism for weeping the heap. This defaults
  // to a mark-and-sweep, but can be modified and extended...

  private GC                         gc                        = new GC(this, false);

  // Various classes and types are known to the VM. Many of these are
  // simply so that VM managed data structures can return a particular
  // class when an of() is performed by XOCL. Some of the classes are
  // used to manage message passing and slot access (the MOP for example).
  // These variables are setup as part of the boot process by Kernel.xmf...

  public int                         theClassElement           = mkUndefined();

  public int                         theClassException         = mkUndefined();

  public int                         theClassForeignObject     = mkUndefined();

  public int                         theClassForeignOperation  = mkUndefined();

  public int                         theClassForwardRef        = mkUndefined();

  public int                         theClassBind              = mkUndefined();

  public int                         theClassBuffer            = mkUndefined();

  public int                         theClassCodeBox           = mkUndefined();

  public int                         theClassClass             = mkUndefined();

  public int                         theClassPackage           = mkUndefined();

  public int                         theClassDataType          = mkUndefined();

  public int                         theClassDaemon            = mkUndefined();

  public int                         theClassCompiledOperation = mkUndefined();

  public int                         theClassSymbol            = mkUndefined();

  public int                         theClassTable             = mkUndefined();

  public int                         theClassThread            = mkUndefined();

  public int                         theClassVector            = mkUndefined();

  public int                         theTypeBoolean            = mkUndefined();

  public int                         theTypeInteger            = mkUndefined();

  public int                         theTypeFloat              = mkUndefined();

  public int                         theTypeString             = mkUndefined();

  public int                         theTypeSeqOfElement       = mkUndefined();

  public int                         theTypeSetOfElement       = mkUndefined();

  public int                         theTypeSeq                = mkUndefined();

  public int                         theTypeSet                = mkUndefined();

  public int                         theTypeNull               = mkUndefined();

  // Various symbols are used by the VM. Rather than create them each time
  // they are used, they are cached in the following variables...

  public int                         theSymbolArity            = mkUndefined();

  public int                         theSymbolAttributes       = mkUndefined();

  public int                         theSymbolContents         = mkUndefined();

  public int                         theSymbolDefault          = mkUndefined();

  public int                         theSymbolDot              = mkUndefined();

  public int                         theSymbolInvoke           = mkUndefined();

  public int                         theSymbolInit             = mkUndefined();

  public int                         theSymbolMachineInit      = mkUndefined();

  public int                         theSymbolName             = mkUndefined();

  public int                         theSymbolOperations       = mkUndefined();

  public int                         theSymbolOwner            = mkUndefined();

  public int                         theSymbolParents          = mkUndefined();

  public int                         theSymbolType             = mkUndefined();

  public int                         theSymbolFire             = mkUndefined();

  public int                         theSymbolValue            = mkUndefined();

  public int                         theSymbolDocumentation    = mkUndefined();

  public int                         theSymbolHead             = mkUndefined();

  public int                         theSymbolTail             = mkUndefined();

  public int                         theSymbolIsEmpty          = mkUndefined();

  public int                         theSymbolNewListener      = mkUndefined();

  // Functions have a dynamics structure that contains the imported names.
  // When a function is initially created it has a default dynamics that
  // contains globals. This is predefined and shared between all functions...

  public int                         globalDynamics            = nilValue;

  // The operator table contains cached operators against classes. It is
  // used to enhance lookup performance. When a message is sent to an object
  // if the object's classifier is in the table then the operator name is
  // looked up without having to process the classifier and its operators...

  public int                         operatorTable             = mkUndefined();

  // Class instantiation can be optimized when a class has simple
  // constructors and does not implement any init operations. If so then
  // the constructors are cached in this table against the class...

  public int                         constructorTable          = mkUndefined();

  // Not used...

  private int                        newListenersTableSize     = K;

  // Not used...

  public int                         newListenersTable         = mkUndefined();

  // Used to manage zip files...

  private Hashtable<String, ZipFile> zipFiles                  = new Hashtable<String, ZipFile>();

  // A saved image contains a header that includes various properties of the
  // image. When the system starts up a default header is created and then
  // saved. This can be modified, before the image is re-saved...

  private Header                     header                    = defaultHeader();

  // Where to save a panic dump...

  private String                     backtraceDumpFile         = "XMFDump";

  // Updates to data structures in the machine can be undone within an
  // undoable transaction. When the transaction is active, the heap
  // modifications
  // are recorded. When the transaction is complete, the most recently
  // recorded transaction can be undone which will restore the data values
  // to their state before the transaction started. Undoable transactions are
  // saved on the undo engine...

  public UndoEngine                  undo                      = new UndoEngine();

  // the client name buffer is used to store a client name for communication
  // via the operating system. It is done this way so that XMF strings do not
  // have to be translated into Java strings with the ensuing resource
  // implications...

  private StringBuffer               clientName                = new StringBuffer();

  // Call back from Java is provided via the client interface. The client
  // interface table is a table mapping Java-exposed client names to
  // operations that will handle the call back...

  public int                         clientInterface           = undefinedValue;

  private static final int           clientInterfaceSize       = 100;

  // Instances of Java classes are implemented as foreign objects. By default
  // the classifier for these objects is ForeignObject, however the VM can
  // be told that it is something different by populating the TypeMapping
  // table with a mapping from the Java class name to the XMF class that
  // should be returned by of().

  public int                         foreignTypeMapping        = undefinedValue;

  private static final int           foreignTypeMappingSize    = 100;

  // Instances of Java classes are foreign objects that implement the slot
  // access, update and message passing via a MOP. There is a default MOP
  // implemented by the VM, but new MOPS can be implemented as Java classes
  // and registered against the Java class name whose instances should use the
  // MOP in the MOP mapping table...

  public int                         foreignMOPMapping         = undefinedValue;

  private static final int           foreignMOPMappingSize     = 100;

  private static StringBuffer        buffer                    = new StringBuffer();

  // The VM may be used as a service by the outside world. This occurs, for
  // example when Java code uses the clientSend call-back service. Startup
  // and initialisation of the machine may take some time and it is important
  // that services offered by a VM are not used until the machine is ready.
  // The following boolean flag can be queried by Java code and can be set
  // from user code via Kernel_ready.

  private boolean                    ready                     = false;

  // The VM manages a debug interface that can be used by external tools
  // to handle debugging...

  private Debugger                   debugger                  = new Debugger();

  public Machine(OperatingSystem XOS) {
    this.XOS = XOS;
  }

  // Machine words are split into 4 bytes....

  public static final int byte1(int word) {
    return word & BYTE1;
  }

  public static final int byte2(int word) {
    return (word & BYTE2) >>> 8;
  }

  public static final int byte3(int word) {
    return (word & BYTE3) >>> 16;
  }

  public static final int byte4(int word) {
    return (word & BYTE4) >>> 24;
  }

  public static final int getBit(int word, int bit) {
    int mask = 1 << bit;
    return (word & mask) >> bit;
  }

  public static final int tag(int word) {
    // Get the type tag on a machine word.
    return (word & BYTE4) >>> 24;
  }

  public final void set(int ptr, int value) {

    // Set the contents of the heap location at
    // 'ptr' to be value...

    words[ptr] = value;
  }

  public static final int ptr(int word) {

    // Get the pointer part of a machine word...

    return word & PTR;
  }

  public static final int value(int word) {

    // Get the value part of a machine word...

    return word & DATA;
  }

  public final int ref(int ptr) {

    // Return the machine word at the given heap location...

    return words[ptr];
  }

  public void setFirstWord(int word, int value) {
    set(ptr(word), value);
  }

  public static final int setBit(int word, int bit, int value) {
    int allSet = 0xFFFFFF;
    int reset = (1 << bit) ^ allSet;
    int resetWord = word & reset;
    return resetWord | (value << bit);
  }

  public int alloc(int length) {

    // Allocate 'length' words of heap return a
    // raw pointer to the new storage.

    int ptr = -1;
    if ((freePtr + length) < words.length) {
      ptr = freePtr;
      freePtr = freePtr + length;
      return ptr;
    } else return allocFails(length);
  }

  public int allocFails(int requested) {

    // Try to extend the heap. This may fail due to the OS
    // refusing to allocate more memory. If so we print a
    // message and die.

    if (!extendHeap()) {
      System.out.println("\n****** Alloc Fails **********\n");
      System.out.println("Request to allocate " + requested + " words of memory failed.");
      System.out.println("Available memory is " + (words.length - freePtr) + " out of " + words.length + " words");
      System.out.println("Current GC limit is " + gcLimit + " (available < " + freeHeap + ") words allocated before GC.");
      System.out.println("Current value of freePtr is " + freePtr);
      saveBacktrace(currentFrame);
      System.out.println("Backtrace Saved in " + backtraceDumpFile);
      throw new MachineError(ALLOCERROR, "Memory allocation failed.");

    }
    return alloc(requested);
  }

  public boolean extendHeap() {

    // Called when the system runs out of memory and wants to extend the
    // heap by the amount defined by incHeap.

    System.out.println("[ Heap exhausted, increase by " + incHeap + " words. ]");
    System.out.println("[ Re-allocating heap at " + (words.length + incHeap) + " words. ]");
    return extendHeap(incHeap);
  }

  public boolean extendHeap(int amount) {

    // Extend the heap by the supplied amount. Returns true when the
    // extension
    // is successful and false otherwise.

    try {
      int[] newWords = new int[words.length + amount];
      for (int i = 0; i < words.length; i++)
        newWords[i] = words[i];
      words = newWords;
      gcWords = new int[words.length + amount];
      heapSize = words.length;
      gcLimit = heapSize - freeHeap;
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  public boolean needsGC() {
    return freePtr > gcLimit;
  }

  // Machine words in the heap have tags. The tags indicate the type of
  // value that the word represents. Words are split into immediates
  // and pointers. The immediates encode the value directly in the
  // word and pointer words encode an address in the heap. The following
  // predicates check the tag on a machine word to check if the value
  // is a specific type...

  public static boolean isArray(int word) {
    return tag(word) == ARRAY;
  }

  public static final boolean isBool(int word) {
    return tag(word) == BOOL;
  }

  public static final boolean isBigInt(int word) {
    return tag(word) == BIGINT;
  }

  public static final boolean isBuffer(int value) {
    return tag(value) == BUFFER;
  }

  public static boolean isCode(int word) {
    return tag(word) == CODE;
  }

  public static final boolean isCodeBox(int word) {
    return tag(word) == CODEBOX;
  }

  public static boolean isCons(int word) {
    return tag(word) == CONS;
  }

  public static boolean isCont(int word) {
    return tag(word) == CONT;
  }

  public boolean isDefaultGetMOP(int obj) {
    return getBit(value(objProperties(obj)), OBJ_DEFAULT_GET_MOP) == 1;
  }

  public boolean isDefaultSendMOP(int obj) {
    return getBit(value(objProperties(obj)), OBJ_DEFAULT_SEND_MOP) == 1;
  }

  public boolean isDefaultSetMOP(int obj) {
    return getBit(value(objProperties(obj)), OBJ_DEFAULT_SET_MOP) == 1;
  }

  public boolean isDynamicTable(int cell) {
    return dynamicCellType(cell) == DYNAMIC_TABLE;
  }

  public boolean isDynamicValue(int cell) {
    return dynamicCellType(cell) == DYNAMIC_VALUE;
  }

  public static final boolean isDaemon(int value) {
    return tag(value) == DAEMON;
  }

  public static final boolean isFloat(int value) {
    return tag(value) == FLOAT;
  }

  public static boolean isForeignFun(int word) {
    return tag(word) == FOREIGNFUN;
  }

  public static final boolean isForeignObj(int word) {
    return tag(word) == FOREIGNOBJ;
  }

  public static final boolean isForwardRef(int word) {
    return tag(word) == FORWARDREF;
  }

  public static final boolean isFun(int word) {
    return tag(word) == FUN;
  }

  public boolean isHotLoad(int obj) {
    return getBit(value(objProperties(obj)), OBJ_HOT_LOAD) == 1;
  }

  public static boolean isInputChannel(int value) {
    return tag(value) == INPUT_CHANNEL;
  }

  public final static boolean isNum(int word) {
    return isInt(word) || isBigInt(word);
  }

  public final static boolean isInt(int word) {
    return isPosInt(word) || isNegInt(word);
  }

  public final static boolean isNegInt(int word) {
    return tag(word) == NEGINT;
  }

  public static boolean isNil(int word) {
    return tag(word) == NIL;
  }

  public static boolean isNull(int word) {
    return tag(word) == UNDEFINED;
  }

  public static boolean isObj(int word) {
    return tag(word) == OBJ;
  }

  public static boolean isOutputChannel(int value) {
    return tag(value) == OUTPUT_CHANNEL;
  }

  public final static boolean isPosInt(int word) {
    return tag(word) == INT;
  }

  public static boolean isSaveIndex(int word) {
    return tag(word) == SAVEINDEX;
  }

  public static boolean isSeq(int word) {
    return isCons(word) || isNil(word);
  }

  public static boolean isSet(int value) {
    return tag(value) == SET;
  }

  public static boolean isString(int word) {
    return tag(word) == STRING;
  }

  public static final boolean isSymbol(int value) {
    return tag(value) == SYMBOL;
  }

  public static final boolean isTable(int word) {
    return tag(word) == HASHTABLE;
  }

  public final static boolean isThread(int word) {
    return tag(word) == THREAD;
  }

  // Clients are used to connect to servers....

  // All of the data structures in the heap go thorugh an allocation
  // method. This allocates the space required for the structure on
  // the heap, performs any initialization and then returns a tagged
  // pointer to the structure or an immediate. Each of the constructors
  // have the form mkTYPE...

  public int mkArray(int length) {

    // An array is the length of the array, its daemons
    // and then its storage...

    if (length == 0 && emptyArray != undefinedValue) return emptyArray;
    memory.alloc(ARRAY, length + ARRAY_HEADER);
    int ptr = alloc(length + ARRAY_HEADER);
    set(ptr, mkInt(value(length)));
    set(ptr + 1, trueValue);
    set(ptr + 2, nilValue);
    return mkPtr(ARRAY, ptr);
  }

  public int gcArray(int array) {
    int length = arrayLength(array);
    System.arraycopy(words, ptr(array), gcWords, gcFreePtr, length + ARRAY_HEADER);
    int ptr = mkPtr(ARRAY, gcFreePtr);
    gcFreePtr += length + ARRAY_HEADER;
    return ptr;
  }

  public int mkAttribute() {

    // Object attributes are encoded as cons pairs.
    // Seq{Name | Value}...

    return mkCons(undefinedValue, undefinedValue);
  }

  public int mkAttribute(int name, int value) {

    // Initialise the attribute...

    int attribute = mkAttribute();
    attributeSetName(attribute, name);
    attributeSetValue(attribute, value);
    return attribute;
  }

  public int attributeName(int field) {
    return consHead(field);
  }

  public void attributeSetName(int field, int name) {
    consSetHead(field, name);
  }

  public void attributeSetValue(int field, int value) {
    consSetTail(field, value);
  }

  public int attributeValue(int field) {
    return consTail(field);
  }

  public int mkBigInt(long l) {
    return mkBigInt(new Long(l).toString());
  }

  public int mkBigInt(String string) {

    // Big integers are represented as a string...

    int s = mkString(string);
    return mkPtr(BIGINT, value(s));
  }

  public static int mkBool(boolean value) {

    // Booleans are encoded in the heap as integers
    // true = 1 and false = 0...

    if (value)
      return mkBool(1);
    else return mkBool(0);
  }

  public static int mkBool(int value) {

    // Return a machine word for a boolean...

    return mkImmediate(BOOL, value);
  }

  public int mkBuffer() {

    // A buffer is a dynamic array. The storage for a buffer is a list of
    // arrays. Each array has a size specified in the dynamic array header.
    // Values are indexed in the list of arrays as though they were
    // contiguous
    // storage. If an element is added beyond the current storage then
    // the buffer storage is extended to contain the indexed element.
    // The SIZE of a buffer is the current number of elements it contains.
    // The LENGTH of a buffer is the total amount of storage it has
    // available.
    // If the buffer is to be interpreted as a string then the asString flag
    // is set. XMF should then treat the buffer as a normal string except
    // that
    // characters can be destructively added to the string...

    int buffer = mkPtr(BUFFER, alloc(BUFFER_SIZE));
    memory.alloc(BUFFER, BUFFER_SIZE);
    bufferSetIncrement(buffer, mkInt(0));
    bufferSetDaemons(buffer, nilValue);
    bufferSetDaemonsActive(buffer, trueValue);
    bufferSetStorage(buffer, nilValue);
    bufferSetSize(buffer, mkInt(0));
    bufferSetAsString(buffer, falseValue);
    return buffer;
  }

  public int mkBuffer(int increment) {

    // The increment indicates how much to grow the buffer by
    // each time it needs to expand...

    int buffer = mkBuffer();
    bufferSetIncrement(buffer, mkInt(increment));
    bufferSetDaemons(buffer, nilValue);
    bufferSetDaemonsActive(buffer, trueValue);
    bufferSetStorage(buffer, mkCons(mkArray(increment), nilValue));
    bufferSetSize(buffer, mkInt(0));
    bufferSetAsString(buffer, falseValue);
    return buffer;
  }

  public int mkCode(int length) {

    // A code vector contains machine instructions...

    memory.alloc(CODE, value(length) + CODE_HEADER);
    int ptr = alloc(value(length) + CODE_HEADER);
    set(ptr, mkImmediate(CODELENGTH, value(length)));
    return mkPtr(CODE, ptr);
  }

  public int gcCode(int code) {
    int length = codeLength(code);
    System.arraycopy(words, ptr(code), gcWords, gcFreePtr, length + CODE_HEADER);
    int ptr = mkPtr(CODE, gcFreePtr);
    gcFreePtr += length + CODE_HEADER;
    return ptr;
  }

  public int mkCodeBox(int locals) {

    // A code box is used to contain program code and associated information
    // necessary to perform the instructions. A code box has the following:
    // components:
    //
    // o An array of constants used as operands to instructions. The
    // instructions in the code box have operands that are indexes into
    // the constants array.
    //
    // o A number of locals that are required to perform the code. When the
    // machine starts to perform the code in the code box it will allocate
    // storage for the locals in the stack frame.
    //
    // o A code array containing the instructions.
    //
    // o The name of the owner of the code box. If the owner is not known or
    // is an unnamed function then the name is "anonymous".
    //
    // o The source code for the code box (or null).
    //
    // o The resource name for the code box (or null).

    memory.alloc(CODEBOX, CODEBOX_SIZE);
    int ptr = alloc(CODEBOX_SIZE);
    set(ptr, mkInt(value(locals)));
    return mkPtr(CODEBOX, ptr);
  }

  public int gcCodeBox(int codeBox) {
    System.arraycopy(words, ptr(codeBox), gcWords, gcFreePtr, CODEBOX_SIZE);
    int ptr = mkPtr(CODEBOX, gcFreePtr);
    gcFreePtr += CODEBOX_SIZE;
    return ptr;
  }

  public int mkCodeBox(int constArray, int locals, int instrs) {

    // Create an populate a code box...

    int codeBox = mkCodeBox(locals);
    codeBoxSetConstants(codeBox, constArray);
    codeBoxSetInstrs(codeBox, instrs);
    return codeBox;
  }

  public int mkCons(int head, int tail) {

    // A cons pair has a head and a tail...

    int ptr = alloc(CONS_SIZE);
    memory.alloc(CONS, CONS_SIZE);
    set(ptr, head);
    set(ptr + 1, tail);
    return mkPtr(CONS, ptr);
  }

  public int gcCons(int cons) {
    System.arraycopy(words, ptr(cons), gcWords, gcFreePtr, CONS_SIZE);
    int ptr = mkPtr(CONS, gcFreePtr);
    gcFreePtr += CONS_SIZE;
    return ptr;
  }

  public int mkCont(int length) {

    // A continuation is a function + a stack. When the
    // continuation is called the associated stack replaces
    // the calling stack and allows the function to return to
    // a different place to that from which it was called...

    int ptr = alloc(length + CONT_HEADER);
    memory.alloc(CONT, length + CONT_HEADER);
    set(ptr, mkInt(value(length)));
    return mkPtr(CONT, ptr);
  }

  public int mkDaemon() {

    // A daemon monitors changes in data structures. Daemons
    // can be added to objects (monitoring slots) and arrays
    // (monitoring fields)...

    int daemon = mkPtr(DAEMON, alloc(DAEMON_SIZE));
    memory.alloc(DAEMON, DAEMON_SIZE);
    daemonSetId(daemon, undefinedValue);
    daemonSetType(daemon, DAEMON_ANY);
    daemonSetSlot(daemon, undefinedValue);
    daemonSetAction(daemon, undefinedValue);
    daemonSetPersistent(daemon, falseValue);
    daemonSetTraced(daemon, falseValue);
    daemonSetTarget(daemon, frameSelf());
    return daemon;
  }

  public int gcDaemon(int daemon) {
    System.arraycopy(words, ptr(daemon), gcWords, gcFreePtr, DAEMON_SIZE);
    int ptr = mkPtr(DAEMON, gcFreePtr);
    gcFreePtr += DAEMON_SIZE;
    return ptr;
  }

  public int mkDataInputChannel(int inputChannel) {
    if (isInputChannel(inputChannel)) {
      int tinch = XOS.newDataInputChannel(value(inputChannel));
      if (tinch == -1) throw new MachineError(TYPE, "Cannot create data input channel.");
      return mkInputChannel(tinch);
    } else throw new MachineError(TYPE, "Data input channels are based on input channels: " + valueToString(inputChannel));
  }

  public int mkDataOutputChannel(int outputChannel) {
    if (isOutputChannel(outputChannel)) {
      int tinch = XOS.newDataOutputChannel(value(outputChannel));
      if (tinch == -1) throw new MachineError(TYPE, "Cannot create data output channel.");
      return mkOutputChannel(tinch);
    } else throw new MachineError(TYPE, "Data output channels are based on output channels: " + valueToString(outputChannel));
  }

  // Dynamic variables are essentially global variables with scoping. When
  // a function is created it captures the current collection of dynamic
  // variables
  // and their values. When the function is called, the dynamic variables
  // captured by the function are made available.
  //
  // There are two ways in which dynamic variables can be created:
  // (1) Globally add a new dynamic. Everyone sees the new dynamic variable.
  // These dynamic variables have global scope and extent.
  // (2) Locally add a new dynamic. The dynamic is available to the currently
  // executing expression. These dynamic variables have local scope and
  // extent.
  //
  // There are three types of dynamic varaible:
  // (1) Dynamic values. An association between a symbol and a value.
  // (2) Dynamic tables. A dynamic table has the effect of making all the
  // keys (symbols) and values in scope.
  // (3) Dynamic slots. The slot names of 'self' in the current stack frame
  // take precedence over all other dynamic variables.
  //
  // Dynamic scoping is implemented as a linked list of dynamic variables in
  // the stack frame. Dynamic variable reference looks up the name in the
  // stack frame.

  public int mkDynamicBinding(int name, int value) {

    // Functions have imports via dynamic binding cells.
    // A name is locally imported into a function via a
    // dynamic binding...

    int binding = mkCons(name, value);
    return mkDynamicCell(DYNAMIC_VALUE, binding);
  }

  public int mkDynamicCell(int type, int value) {

    // A dynamic cell is just a cons cell. Functions
    // have dynamic cells for their imports. A cell may
    // be a name-space table (all thenames are imported)
    // or may just be a single name. The two cases are
    // distinguished via the type in the cell...

    int cell = mkCons(type, value);
    return cell;
  }

  public int mkDynamicTable(int table) {

    // An imported table of bindings...

    return mkDynamicCell(DYNAMIC_TABLE, table);
  }

  public int mkEmptyArray() {

    // An array with no elements in it...

    int ptr = alloc(3);
    set(ptr, mkInt(0)); // length
    set(ptr + 1, trueValue); // daemonsActive
    set(ptr + 2, nilValue); // daemons
    return mkPtr(ARRAY, ptr);
  }

  public int mkEmptySet() {

    // The empty set...

    int ptr = alloc(1);
    set(ptr, nilValue);
    return mkPtr(SET, ptr);
  }

  public int mkException(String message) {

    // When the machine wants to raise an exception to the user
    // it calls this to create the exception object. The class exception
    // is actually MachineException from XCore. The machine sets up the
    // backtrace etc in the exception and returns it to the upper levels
    // where it is processed...

    int exception = mkObj();
    objSetType(exception, theClassException);
    addSlots(exception, theClassException);
    objSetAttValue(exception, mkSymbol("backtrace"), stackFrames());
    objSetAttValue(exception, mkSymbol("message"), mkString(message));
    objSetAttValue(exception, mkSymbol("lineCount"), frameLineCount());
    objSetAttValue(exception, mkSymbol("charCount"), frameCharCount());
    objSetAttValue(exception, mkSymbol("resourceName"), codeBoxResourceName(frameCodeBox()));
    return exception;
  }

  public int stackFrames() {
    return stackFrames(currentFrame);
  }

  public int stackFrames(int frame) {

    // Construct and return a sequence of stack frames. Each stack frame is
    // represented as a sequence of values. This will return a sopy of the
    // stack frame elements from the frame given as an argument in the
    // order of youngest frame first.

    if (frame == -1)
      return nilValue;
    else {
      return mkCons(stackFrame(frame), stackFrames(valueStack.ref(frame + PREVFRAME)));
    }
  }

  public int stackFrame(int frame) {
    int codeBox = valueStack.ref(frame + FRAMECODEBOX);
    int index = valueStack.ref(frame + FRAMECODEINDEX);
    int globals = valueStack.ref(frame + FRAMEGLOBALS);
    int dynamics = valueStack.ref(frame + FRAMEDYNAMICS);
    int locals = valueStack.ref(frame + FRAMELOCALS);
    int self = valueStack.ref(frame + FRAMESELF);
    int supers = valueStack.ref(frame + FRAMESUPER);
    int handler = valueStack.ref(frame + FRAMEHANDLER);
    int localValues = stackLocalValues(frame, locals);
    int lineCount = valueStack.ref(frame + FRAMELINECOUNT);
    int charCount = valueStack.ref(frame + FRAMECHARCOUNT);
    int stackFrame = nilValue;
    stackFrame = mkCons(mkInt(charCount), stackFrame);
    stackFrame = mkCons(mkInt(lineCount), stackFrame);
    stackFrame = mkCons(localValues, stackFrame);
    stackFrame = mkCons(handler, stackFrame);
    stackFrame = mkCons(supers, stackFrame);
    stackFrame = mkCons(self, stackFrame);
    stackFrame = mkCons(mkInt(locals), stackFrame);
    stackFrame = mkCons(dynamics, stackFrame);
    stackFrame = mkCons(globals, stackFrame);
    stackFrame = mkCons(mkInt(index), stackFrame);
    stackFrame = mkCons(codeBox, stackFrame);
    return stackFrame;
  }

  public int mkFileInputChannel(String fileName) {
    int finch = XOS.newFileInputChannel(fileName);
    if (finch == -1)
      throw new MachineError(TYPE, "Cannot create input channel to file: " + fileName);
    else {
      return mkInputChannel(finch);
    }
  }

  public int mkFileOutputChannel(String fileName) {
    int foutch = XOS.newFileOutputChannel(fileName);
    if (foutch == -1)
      throw new MachineError(TYPE, "Cannot create output channel to file: " + fileName);
    else {
      return mkImmediate(OUTPUT_CHANNEL, foutch);
    }
  }

  public int mkFloat() {

    // Floats are represented as strings...

    return mkFloat("0.0");
  }

  public int mkFloat(double d) {
    return mkFloat("" + d);
  }

  public int mkFloat(float f) {
    return mkFloat("" + f);
  }

  public int mkFloat(int prePoint, int postPoint) {
    return mkFloat(intValue(prePoint) + "." + intValue(postPoint));
  }

  public int mkFloat(String value) {

    // The VM allocates a string to represent a float.
    // Float operations decode and encode the strings
    // as necessary (how efficient is that!)...

    memory.alloc(FLOAT, FLOAT_SIZE);
    int ptr = alloc(FLOAT_SIZE);
    int str = mkString(value);
    set(ptr, str);
    return mkPtr(FLOAT, ptr);
  }

  public int mkForeignFun(int index) {

    // Foreign functions are Java methods that can
    // be called from XMF. They live in a table and
    // are represented as an immediate...

    return mkImmediate(FOREIGNFUN, index);
  }

  public int mkForeignObj(int index) {

    // Foreign objects are represented in a table and
    // encoded as an immediate with an index into the
    // table...

    return mkImmediate(FOREIGNOBJ, index);
  }

  public int mkForwardRef(int path) {

    // Forward references are created by the loader when it tries to
    // reference a path that no-longer exists. If P::Q does not exist
    // at load time then the loader creates a forward reference to P::Q
    // and uses that for the loaded value instead. Later on, forward
    // references can be replaced via a walker for a real value for P::Q...

    int ptr = alloc(FORWARDREF_SIZE);
    forwardRefSetPath(ptr, path);
    forwardRefSetValue(ptr, undefinedValue);
    forwardRefSetListeners(ptr, nilValue);
    return mkPtr(FORWARDREF, ptr);
  }

  public int mkFun() {

    // Make a function. All the field values are set to defaults...

    memory.alloc(FUN, FUN_SIZE);
    int ptr = alloc(FUN_SIZE);
    int fun = mkPtr(FUN, ptr);

    // The owner of a fun is either 'null' in which case there is no
    // designated owner or it is a named element. The 'owner' link
    // is used to resolve NameSpaceRef instructions...

    funSetOwner(fun, undefinedValue);
    funSetProperties(fun, nilValue);
    funSetDynamics(fun, globalDynamics);
    funSetSig(fun, nilValue);
    return fun;
  }

  public int gcFun(int fun) {
    System.arraycopy(words, ptr(fun), gcWords, gcFreePtr, FUN_SIZE);
    int ptr = mkPtr(FUN, gcFreePtr);
    gcFreePtr += FUN_SIZE;
    return ptr;
  }

  public int mkGlobals() {

    // The globals of a function are its closed in variables.
    // The globals are a linked list of arrays. Each array is
    // owned by a function and linked to the parent global list
    // of the immediately enclosing function...

    return mkArray(2);
  }

  public int mkGlobals(int array, int prev) {

    // Make a linked list of globals...

    int globals = mkGlobals();
    globalsSetArray(globals, array);
    globalsSetPrev(globals, prev);
    return globals;
  }

  public int mkGZipInputChannel(int inputChannel) {
    if (isInputChannel(inputChannel)) {
      int ginch = XOS.newGZipInputChannel(value(inputChannel));
      if (ginch == -1)
        throw new MachineError(TYPE, "Cannot create gzip input channel.");
      else {
        return mkInputChannel(ginch);
      }
    } else throw new MachineError(TYPE, "GZip input channels are based on input channels: " + valueToString(inputChannel));
  }

  public int mkGZipOutputChannel(int outputChannel) {
    if (isOutputChannel(outputChannel)) {
      int gouch = XOS.newGZipOutputChannel(value(outputChannel));
      if (gouch == -1)
        throw new MachineError(TYPE, "Cannot create gzip output channel.");
      else {
        return mkImmediate(OUTPUT_CHANNEL, gouch);
      }
    } else throw new MachineError(TYPE, "GZip output channels are based on output channels: " + valueToString(outputChannel));
  }

  public int mkHashtable(int size) {

    // A hash table is actually an array used in a specific way.
    // Each field in the array is a bucket: a sequence of pairs.
    // Hashing into the table indexes a bucket which is traversed
    // to find the pair with the required key...

    int table = mkArray(size);
    hashTableClear(table);
    return mkPtr(HASHTABLE, table);
  }

  public int gcTable(int table) {
    return mkPtr(HASHTABLE, gcArray(table));
  }

  public static final int mkImmediate(int tag, int value) {

    // Combine the tag in the top byte of the word
    // with the value in the bottom 24 bits. Returns
    // a machine word...

    return (tag << 24) | (value & DATA);
  }

  public static final int mkInputChannel(int index) {

    // An input channel lives in a table at a given index.
    // The index is tagged as an immediate...

    return mkImmediate(INPUT_CHANNEL, index);
  }

  public static final int mkInt(int value) {

    // Return a machine word for an integer. Negative values are
    // represented as their absolute values tagged with NEGINT.
    // Positive values are represented as the value tagged with
    // INT. Use the appropriate tag tester below for type testing
    // Use intValue to make sure you get an appropriate Java int.

    if (value < 0)
      return mkImmediate(NEGINT, Math.abs(value));
    else return mkImmediate(INT, value);
  }

  public final int mkInputChannel(InputStream in) {
    return mkInputChannel(XOS.newInputChannel(in));
  }

  public int mkObj() {

    // Objects have classifiers (returned by of()), daemons,
    // properties and slots....

    memory.alloc(OBJ, OBJ_SIZE);
    int ptr = alloc(OBJ_SIZE);
    set(ptr, undefinedValue);
    set(ptr + 1, nilValue);
    set(ptr + 2, nilValue);
    set(ptr + 3, mkInt(OBJ_DEFAULT_PROPS));
    return mkPtr(OBJ, ptr);
  }

  public int gcCopyObj(int objPtr) {
    // The GC wants to copy an existing object from the old heap to the
    // new heap...
    System.arraycopy(words, ptr(objPtr), gcWords, gcFreePtr, OBJ_SIZE);
    int ptr = mkPtr(OBJ, gcFreePtr);
    gcFreePtr += OBJ_SIZE;
    return ptr;
  }

  public int mkObj(int type) {
    int obj = mkObj();
    objSetType(obj, type);
    return obj;
  }

  public final int mkOutputChannel(OutputStream out) {
    return mkOutputChannel(XOS.newOutputChannel(out));
  }

  public int mkSaveIndex(int index) {
    return mkImmediate(SAVEINDEX, index);
  }

  public int mkSAXInputChannel(int inputChannel) {
    if (isInputChannel(inputChannel)) {
      int xinch = XOS.newSAXInputChannel(value(inputChannel));
      if (xinch == -1)
        throw new MachineError(TYPE, "Cannot create a SAX input channel: " + valueToString(inputChannel));
      else {
        return mkInputChannel(xinch);
      }
    } else throw new MachineError(TYPE, "SAX input channel expects an input channel as an argument: " + valueToString(inputChannel));
  }

  public int mkSet(int elements) {

    // A set is actually a retagged sequence...

    if (elements == nilValue && emptySet != undefinedValue) return emptySet;
    memory.alloc(SET, SET_SIZE);
    int ptr = alloc(SET_SIZE);
    set(ptr, elements);
    return mkPtr(SET, ptr);
  }

  public int mkString(CharSequence string) {

    // Return a machine string created from a Java string.

    int length = string.length();
    int str = mkString(length);
    for (int i = 0; i < length; i++)
      stringSet(str, i, string.charAt(i));
    return str;
  }

  public int mkString(int chars) {

    // Return a machine word for a freshly allocated
    // string. The first word of the string contains
    // the length and the rest are chars encoded as
    // bytes.

    int words = (chars / 4) + 1;
    memory.alloc(STRING, words + STRING_HEADER);
    int ptr = alloc(words + STRING_HEADER);
    set(ptr, mkImmediate(STRINGLENGTH, value(chars)));
    return mkPtr(STRING, ptr);
  }

  public int gcString(int string) {
    int l = (stringLength(string) / 4) + 1;
    System.arraycopy(words, ptr(string), gcWords, gcFreePtr, STRING_HEADER + l);
    int ptr = mkPtr(STRING, gcFreePtr);
    gcFreePtr += STRING_HEADER + l;
    return ptr;
  }

  public int mkStringInputChannel(int string) {
    String s = valueToString(string);
    int sinch = XOS.newStringInputChannel(s);
    if (sinch == -1)
      throw new MachineError(TYPE, "Cannot create input channel to string: " + s);
    else {
      return mkInputChannel(sinch);
    }
  }

  public int mkSymbol() {

    // Symbols are retagged strings. Symbols live in a table so that two
    // identical strings can be mapped toexactly the same symbol.
    // Symbols are therefore only = when they are identical in memory.
    // This makes lookup on symbols efficient and is why symbols are
    // used as the keys in name-space contents tables...

    memory.alloc(SYMBOL, SYMBOL_SIZE);
    int ptr = alloc(SYMBOL_SIZE);
    int symbol = mkPtr(SYMBOL, ptr);
    symbolSetName(symbol, undefinedValue);
    symbolSetValue(symbol, undefinedValue);
    return symbol;
  }

  public int gcSymbol(int symbol) {
    System.arraycopy(words, ptr(symbol), gcWords, gcFreePtr, SYMBOL_SIZE);
    int ptr = mkPtr(SYMBOL, gcFreePtr);
    gcFreePtr += SYMBOL_SIZE;
    return ptr;
  }

  public int mkSymbol(CharSequence name) {

    // If we look up a symbol via a Java name then try to match the
    // characters
    // with an existing symbol before allocating a new string and a symbol
    // with the same name...

    int symbol = findSymbol(name);
    if (symbol == -1)
      return mkSymbol(mkString(name));
    else return symbol;
  }

  public int findSymbol(CharSequence s) {

    // Used to look up a symbol that might exist. The CharSequence
    // could be a String or a StringBuffer. Return -1 if no symbol
    // exists with the given name.

    return hashTableGet(symbolTable, s);
  }

  public int findSymbol(int name) {

    // Use equalValues to compare the supplied string with the
    // name of an existing symbol. Return -1 if no symbol exists
    // with the given name.

    return hashTableGet(symbolTable, name);
  }

  public int mkSymbol(int name) {
    int symbol = findSymbol(name);
    if (symbol == -1) {
      symbol = mkSymbol();
      symbolSetName(symbol, name);
      setSymbol(name, symbol);
    }
    return symbol;
  }

  public int mkTokenInputChannel(int inputChannel) {
    if (isInputChannel(inputChannel)) {
      int tinch = XOS.newTokenInputChannel(value(inputChannel));
      if (tinch == -1) throw new MachineError(TYPE, "Cannot create token input channel.");
      return mkInputChannel(tinch);
    } else throw new MachineError(TYPE, "Token input channels are based on input channels: " + valueToString(inputChannel));
  }

  public int mkURLInputChannel(String url) {
    int urlin = XOS.newURLInputChannel(url);
    if (urlin == -1)
      throw new MachineError(TYPE, "Cannot create input channel to URL: " + url);
    else {
      return mkInputChannel(urlin);
    }
  }

  public int mkZipInputChannel(int fileName, int entryName) {
    try {
      ZipFile zipFile = zipFile(fileName);
      String label = valueToString(entryName);
      ZipEntry entry = new ZipEntry(label);
      InputStream in = zipFile.getInputStream(entry);
      if (in == null) {
        String entries = "";
        Enumeration<? extends ZipEntry> e = zipFile.entries();
        while (e.hasMoreElements()) {
          entries = entries + " " + e.nextElement();
        }
        throw new MachineError(TYPE, "No zip entry named " + label + " entries are: " + entries);
      } else return mkInputChannel(XOS.newInputChannel(in));
    } catch (IOException ioe) {
      throw new MachineError(TYPE, ioe.getMessage());
    }
  }

  public ZipFile zipFile(int fileName) {
    String name = valueToString(fileName);
    ZipFile zipFile = null;
    try {
      if (zipFiles.containsKey(name))
        return (ZipFile) zipFiles.get(name);
      else {
        File file = new File(name);
        if (file.exists()) {
          zipFile = new ZipFile(file);
          zipFiles.put(name, zipFile);
        } else throw new MachineError(TYPE, "Cannot find zip file " + name);
      }
    } catch (ZipException e) {
      throw new MachineError(TYPE, e.getMessage() + " " + name);
    } catch (IOException e) {
      new MachineError(TYPE, e.getMessage() + " " + name);
    }
    return zipFile;
  }

  public void zipNewEntry(int zipOutputChannel, int name) {
    String label = valueToString(name);
    if (isOutputChannel(zipOutputChannel)) {
      OutputStream out = this.outputChannel(zipOutputChannel);
      if (out instanceof ZipOutputStream) {
        ZipOutputStream zout = (ZipOutputStream) out;
        ZipEntry entry = new ZipEntry(label);
        try {
          zout.putNextEntry(entry);
        } catch (IOException e) {
          throw new MachineError(TYPE, "IO error creating new zip entry " + label);
        }
      } else throw new MachineError(TYPE, "New zip entry requires a zip output channel");
    } else throw new MachineError(TYPE, "Create new zip entry requires an output channel");
  }

  public int mkZipOutputChannel(int outputChannel) {
    if (isOutputChannel(outputChannel)) {
      int zouch = XOS.newZipOutputChannel(value(outputChannel));
      if (zouch == -1)
        throw new MachineError(TYPE, "Cannot create zip output channel.");
      else {
        return mkImmediate(OUTPUT_CHANNEL, zouch);
      }
    } else throw new MachineError(TYPE, "Zip output channels are based on output channels: " + valueToString(outputChannel));
  }

  public static int mkNil() {

    // The empty sequence is a special type of value...

    return mkImmediate(NIL, 0);
  }

  public static final int mkOutputChannel(int index) {

    // An output channel lives in a table at a iven index.
    // The index is tagged as an immediate...

    return mkImmediate(OUTPUT_CHANNEL, index);
  }

  public static final int mkPtr(int tag, int value) {

    // Combine the tag in the top byte of the word
    // with the pointer in the bottom 24 bits. Returns
    // a machine word...

    return (tag << 24) | (value & DATA);
  }

  public final static int mkThread(int threadId) {

    // Threads live in a circular list in the machine. Each
    // thread has a unique id. A thread is represented as an
    // immediate encoding the id...

    return mkImmediate(THREAD, threadId);
  }

  public static int mkUndefined() {

    // The value null...

    return mkImmediate(UNDEFINED, 0);
  }

  public static final int mkWord(int byte4, int byte3, int byte2, int byte1) {
    return byte1(byte4) << 24 | byte1(byte3) << 16 | byte1(byte2) << 8 | byte1(byte1);
  }

  // Each data structure defines a collection of accessors and updaters.
  // These shield the user from the VM word manipulation and the specific
  // offsets required to perform the required heap manipulation...

  public int arrayAsSeq(int array) {

    // Returns an array as a sequence...

    int seq = nilValue;
    for (int i = arrayLength(array) - 1; i >= 0; i--)
      seq = mkCons(arrayRef(array, i), seq);
    return seq;
  }

  public int arrayAsString(int array) {

    // Assumes that the array contains a collection
    // of ASCII character codes. Returns the string
    // based on the array...

    int string = mkString(arrayLength(array));
    for (int i = 0; i < arrayLength(array); i++)
      stringSet(string, i, value(arrayRef(array, i)));
    return string;
  }

  public int arrayDaemons(int array) {

    // The daemons that are currently monitoring the
    // array fields...

    return ref(ptr(array) + 2);
  }

  public int arrayDaemonsActive(int array) {

    // A boolean determining whether the daemons will
    // run or not when the array is updated...

    return ref(ptr(array) + 1);
  }

  public int arrayLength(int word) {

    // The length of the array...

    return value(ref(ptr(word)));
  }

  public int arrayRef(int word, int index) {

    // Reference the machine word at the index
    // in the array.

    return ref(ptr(word) + index + 3);
  }

  public void arraySet(int word, int index, int value) {

    // Set the machine word at the given index
    // of the array...

    undo.setArray(word, index, value, ref(ptr(word) + index + 3));
    set(ptr(word) + index + 3, value);
  }

  public void arraySetDaemons(int array, int daemons) {

    // Update the daemons that are currently monitoring the array...

    undo.setArrayDaemons(array, daemons, arrayDaemons(array));
    set(ptr(array) + 2, daemons);
  }

  public void arraySetDaemonsActive(int array, int active) {

    // Update whether the array will run its daemons
    // when updated or not...

    set(ptr(array) + 1, active);
  }

  public String arrayToString(int word, int depth) {

    // Return a string representation of an array...

    String s = "Array(";
    for (int i = 0; i < arrayLength(word) && i < maxPrintElements; i++) {
      s = s + valueToString(arrayRef(word, i), depth + 1);
      if ((i + 1) < arrayLength(word)) s = s + ",";
    }
    if (arrayLength(word) > maxPrintElements) s = s + "...";
    return s + ")";
  }

  public static String boolToString(int word) {
    if (value(word) == 1)
      return "true";
    else return "false";
  }

  public boolean bigIntIsNegative(int bigInt) {

    // Return true or false...

    return asBigInteger(bigInt).compareTo(new BigInteger("0")) < 0;
  }

  public BigInteger asBigInteger(int bigInt) {
    int string = mkPtr(STRING, value(bigInt));
    return new BigInteger(valueToString(string));
  }

  public int asBigInt(BigInteger bigInteger) {
    return mkBigInt(bigInteger.toString());
  }

  public BigInteger ensureBigInt(int value) {
    if (isInt(value) || isNegInt(value))
      return asBigInteger(mkBigInt(intValue(value)));
    else if (isBigInt(value))
      return asBigInteger(value);
    else throw new Error("Cannot create big integer from " + valueToString(value));
  }

  public int bigIntSub(int v1, int v2) {
    return asBigInt(ensureBigInt(v1).subtract(ensureBigInt(v2)));
  }

  public int bufAsString(int buffer) {

    // The buffer is assumed to be a character buffer and is
    // translated into a string...

    int size = value(bufferSize(buffer));
    int string = mkString(size);
    for (int i = 0; i < size; i++) {
      int c = bufferRef(buffer, i);
      stringSet(string, i, (char) c);
    }
    return string;
  }

  public int bufferAsSeq(int buffer) {

    // Translate the buffer into a sequence of its elements..

    int seq = nilValue;
    for (int i = value(bufferSize(buffer)) - 1; i >= 0; i--)
      seq = mkCons(bufferRef(buffer, i), seq);
    return seq;
  }

  public int bufferAsString(int buffer) {

    // Returns a boolean that indicates whether the
    // buffer is to be viewed as a character buffer or
    // a general element buffer...

    return ref(ptr(buffer) + 5);
  }

  public int bufferDaemons(int buffer) {

    // The daemons that may run when the buffer is updated...

    return ref(ptr(buffer) + 1);
  }

  public int bufferDaemonsActive(int buffer) {

    // A boolean that determines whether or not the
    // daemons will run when the buffer is updated...

    return ref(ptr(buffer) + 2);
  }

  public void bufferGrow(int buffer) {

    // Called when the buffer must increment its storage by one
    // array...

    int increment = value(bufferIncrement(buffer));
    int array = mkArray(increment);
    int storage = bufferStorage(buffer);
    storage = consAppend(storage, mkCons(array, nilValue));
    bufferSetStorage(buffer, storage);
  }

  public int bufferIncrement(int buffer) {

    // The amount of extra storage that the buffer should grow
    // by when it runs out of storage...

    return ref(ptr(buffer));
  }

  public int bufferLength(int buffer) {

    // The total available storage in the buffer...

    return value(bufferIncrement(buffer)) * consLength(bufferStorage(buffer));
  }

  public int bufferRef(int buffer, int index) {

    // Reference the indexed element in the buffer. If the indexed element
    // does not exist then this is an error...

    int size = value(bufferSize(buffer));
    index = value(index);
    if (index >= size)
      throw new MachineError(ARRAYACCESS, "Index " + index + " out of range 0 - " + size + " in buffer.");
    else {
      int increment = value(bufferIncrement(buffer));
      int array = bufferStorageArray(buffer, index);
      int localIndex = index % increment;
      return arrayRef(array, localIndex);
    }
  }

  public void bufferSet(int buffer, int index, int value) {

    // Set the element in the buffer. If the indexed element does not
    // exist then the buffer is extended by one array until it does
    // exist...

    index = value(index);
    int size = value(bufferSize(buffer));
    if (index < size)
      bufferSetElement(buffer, index, value);
    else {
      while (bufferLength(buffer) < index + 1)
        bufferGrow(buffer);
      bufferSetElement(buffer, index, value);
    }
  }

  public void bufferSetAsString(int buffer, int asString) {

    // Change the status of whteher the buffer is a character
    // buffer or not...

    set(ptr(buffer) + 5, asString);
  }

  public void bufferSetDaemons(int buffer, int daemons) {

    // Update the daemons on the buffer...

    set(ptr(buffer) + 1, daemons);
  }

  public void bufferSetDaemonsActive(int buffer, int active) {

    // Change the status of whether the daemons run or not...

    set(ptr(buffer) + 2, active);
  }

  public void bufferSetElement(int buffer, int index, int value) {

    // Change the value of an element in the buffer. The buffer
    // is grown if necessary..

    int array = bufferStorageArray(buffer, value(index));
    int increment = value(bufferIncrement(buffer));
    int localIndex = value(index) % increment;
    arraySet(array, localIndex, value);
    if (value(index) >= value(bufferSize(buffer))) bufferSetSize(buffer, mkInt(value(index) + 1));
  }

  public void bufferSetIncrement(int buffer, int increment) {

    // Change the size by which the buffer storage grows.
    // Dont do this!...

    set(ptr(buffer), increment);
  }

  public void bufferSetSize(int buffer, int size) {

    // Set the amount of currently used storage in the buffer...

    set(ptr(buffer) + 4, size);
  }

  public void bufferSetStorage(int buffer, int storage) {

    // Change the buffer storage. Should be a sequence of arrays...

    set(ptr(buffer) + 3, storage);
  }

  public int bufferSize(int buffer) {

    // The amount of used storage...

    return ref(ptr(buffer) + 4);
  }

  public int bufferStorage(int buffer) {

    // The sequence of arrays used for storage...

    return ref(ptr(buffer) + 3);
  }

  public int bufferStorageArray(int buffer, int index) {

    // Returns the array that contains the indexable element.
    // Assumes that the array exists and that the index is an
    // untagged Java integer...

    int increment = value(bufferIncrement(buffer));
    int storage = bufferStorage(buffer);
    int drop = index / increment;
    storage = drop(storage, drop);
    return consHead(storage);
  }

  public boolean bufferStringEqual(int buffer, int string) {

    // Compare the elements in the buffer with the supplied string.
    // The buffer should be a string buffer...

    if (bufferAsString(buffer) == trueValue) {
      if (value(bufferSize(buffer)) == stringLength(string)) {
        for (int i = 0; i < value(bufferSize(buffer)); i++)
          if (value(bufferRef(buffer, i)) != stringRef(string, i)) return false;
        return true;
      } else return false;
    } else return false;
  }

  public String bufferToString(int buffer, int depth) {

    // Return a string representation of the buffer...

    String s = "";
    if (bufferAsString(buffer) == trueValue) {
      for (int i = 0; i < value(bufferSize(buffer)); i++)
        s = s + (char) value(bufferRef(buffer, i));
      return s;
    } else return "<buffer size = " + value(bufferSize(buffer)) + " length = " + bufferLength(buffer) + ">";
  }

  public int bufferCharCount(int buffer, int charPos) {
    int chars = 0;
    for (int i = 0; i < charPos; i++) {
      int c = bufferRef(buffer, i);
      if (c == '\n')
        chars = 0;
      else chars++;
    }
    return chars;
  }

  public int bufferLineCount(int buffer, int charPos) {
    int lines = 1;
    for (int i = 0; i < charPos; i++) {
      int c = bufferRef(buffer, i);
      if (value(c) == '\n') lines++;
    }
    return lines;
  }

  public int codeLength(int word) {

    // The number of instructions in a code vector...

    return value(ref(ptr(word)));
  }

  public int codeRef(int word, int index) {

    // Return the instruction word at the given index...

    return ref(ptr(word) + index + 1);
  }

  public void codeSet(int word, int index, int value) {

    // Set the instruction at the given index...

    set(ptr(word) + index + 1, value);
  }

  public String codeToString(int word) {

    // return the code vector as a string without knowledge of
    // the referenced constants...

    String s = "Code(";
    for (int i = 0; i < codeLength(word); i++) {
      s = s + tag(codeRef(word, i));
      if ((i + 1) < codeLength(word)) s = s + ",";
    }
    return s + ")";
  }

  public String codeToString(int code, int constants) {

    // Return the code vector as a string with reference to
    // the supplied constants...

    String s = "Code(";
    for (int i = 0; i < codeLength(code); i++) {
      s = s + instrToString(codeRef(code, i), constants);
      if ((i + 1) < codeLength(code)) s = s + ",";
    }
    return s + ")";
  }

  public int codeBoxConstants(int word) {

    // The constants referenced in a code box. This is a
    // sequence of values indexed by the instructions...

    return ref(ptr(word) + 1);
  }

  public int codeBoxInstrs(int word) {

    // The code vector in the code box...

    return ref(ptr(word) + 2);
  }

  public int codeBoxLocals(int word) {

    // The number of locals required by the instructions in the
    // code box. Used to allocate space in a stack frame when the
    // code box is executed...

    return value(ref(ptr(word)));
  }

  public int codeBoxName(int codeBox) {

    // The name of the code box. This is used when a stack
    // frame is referenced that is executing the code...

    return ref(ptr(codeBox) + 3);
  }

  public int codeBoxResourceName(int codeBox) {

    // Where the code box was compiled from...

    return ref(ptr(codeBox) + 5);
  }

  public void codeBoxSetConstants(int codeBox, int constArray) {

    // Update the constants in the code box...

    set(ptr(codeBox) + 1, constArray);
  }

  public void codeBoxSetInstrs(int codeBox, int instrs) {

    // Set the code vector in the code box...

    set(ptr(codeBox) + 2, instrs);
  }

  public void codeBoxSetName(int codeBox, int name) {

    // Set tne name ofthe code box....

    set(ptr(codeBox) + 3, name);
  }

  public void codeBoxSetResourceName(int codeBox, int resourceName) {

    // Change where the code box was compiled from...

    set(ptr(codeBox) + 5, resourceName);
  }

  public void codeBoxSetSource(int codeBox, int source) {

    // Set the source code (a string)...

    set(ptr(codeBox) + 4, source);
  }

  public int codeBoxSource(int codeBox) {

    // The source code (a string)...

    return ref(ptr(codeBox) + 4);
  }

  public int codeBoxToFun(int codeBox, int arity, int dynamics) {

    // A code box is the underlying executable component of a function. The
    // function adds arguments and upper-level features. A code box can
    // be elevated to a function...

    int fun = mkFun();
    funSetArity(fun, mkInt(arity));
    funSetGlobals(fun, undefinedValue);
    funSetCode(fun, codeBox);
    funSetSelf(fun, undefinedValue);
    funSetOwner(fun, undefinedValue);
    funSetSupers(fun, mkCons(fun, nilValue));
    funSetDynamics(fun, dynamics);
    return fun;
  }

  public String codeBoxToString(int codeBox, int depth) {

    // Return the code box represented as a string...

    int constants = codeBoxConstants(codeBox);
    return "CodeBox(" + valueToString(codeBoxName(codeBox), depth + 1) + "," + codeBoxLocals(codeBox) + "," + codeToString(codeBoxInstrs(codeBox), constants) + ")";
  }

  public int consAsString(int cons) {

    // Translate a cons into a string...

    int length = consLength(cons);
    int string = mkString(length);
    for (int i = 0; i < length; i++) {
      stringSet(string, i, value(consHead(cons)));
      cons = consTail(cons);
    }
    return string;
  }

  public int consHead(int cons) {

    // Take the head of a pair...

    return words[cons & DATA];
  }

  public int consLength(int l) {

    // Return the length of a sequence. Stops when the sequence
    // is no longer a cons pair...

    int length = 0;
    while (isCons(l)) {
      length++;
      l = consTail(l);
    }
    return length;
  }

  public int consRemove(int cons, int value) {

    // Remove an element from a sequence. Note that this is recursive.
    // Java is not tail recursive and so you may need to extend the
    // Java stack if you have very long sequences...

    if (cons == nilValue)
      return cons;
    else if (equalValues(consHead(cons), value))
      return consTail(cons);
    else return mkCons(consHead(cons), consRemove(consTail(cons), value));
  }

  public void consSetHead(int cons, int value) {

    // Update the head of a pair...

    set(ptr(cons), value);
  }

  public void consSetTail(int cons, int value) {

    // Update the tail of a pair...

    set(ptr(cons) + 1, value);
  }

  public int consTail(int cons) {

    // Return the tail of a pair..

    return words[(cons & DATA) + 1];
  }

  public String consToString(int cons, int depth) {

    // Return a sequence as a Java string...

    if (properList(cons)) {
      String s = "Seq{";
      int index = 0;
      while (cons != nilValue) {
        index++;
        if (index > maxPrintElements) {
          cons = nilValue;
          s = s + "...";
        } else {
          s = s + valueToString(consHead(cons), depth + 1);
          cons = consTail(cons);
          if (cons != nilValue) s = s + ",";
        }
      }
      return s + "}";
    } else return "Seq{" + valueToString(consHead(cons)) + "|" + valueToString(consTail(cons)) + "}";
  }

  public boolean properList(int cons) {

    // Returns true when a list ends with Seq{}...

    while (true) {
      if (tag(consTail(cons)) == NIL) return true;
      if (tag(consTail(cons)) == CONS)
        cons = consTail(cons);
      else return false;
    }
  }

  public int contCurrentFrame(int cont) {

    // The saved current frame pointer...

    return value(ref(ptr(cont) + 1));
  }

  public int contLength(int cont) {

    // The length of the stack...

    return value(ref(ptr(cont)));
  }

  public int contOpenFrame(int cont) {

    // The saved open frame pointer...

    return value(ref(ptr(cont) + 2));
  }

  public int contRef(int cont, int index) {

    // Index into the saved stack...

    return ref(ptr(cont) + index + 3);
  }

  public void contSet(int cont, int index, int value) {

    // Set a value in the saved stack...

    set(ptr(cont) + index + 3, value);
  }

  public void contSetCurrentFrame(int cont, int value) {

    // Set the current frame pointer...

    set(ptr(cont) + 1, value);
  }

  public void contSetOpenFrame(int cont, int value) {

    // Set the open frame pointer...

    set(ptr(cont) + 2, value);
  }

  public String contToString(int cont) {
    return "<cont>";
  }

  public int daemonAction(int daemon) {

    // The operation that implements the daemon action...

    return ref(ptr(daemon) + 3);
  }

  public int daemonId(int daemon) {

    // The id of the daemon...

    return ref(ptr(daemon));
  }

  public boolean daemonIsPersistent(int daemon) {

    // Will the daemon be saved by the serializer?...

    return daemonPersistent(daemon) == trueValue;
  }

  public int daemonPersistent(int daemon) {

    // The persistent state of the daemon...

    return ref(ptr(daemon) + 4);
  }

  public void daemonSetAction(int daemon, int fun) {

    // Set the function to be run...

    set(ptr(daemon) + 3, fun);
  }

  public void daemonSetId(int daemon, int id) {

    // Set the id to any value...

    set(ptr(daemon), id);
  }

  public void daemonSetPersistent(int daemon, int bool) {

    // Set whether the daemon will be serialized...

    set(ptr(daemon) + 4, bool);
  }

  public void daemonSetSlot(int daemon, int slot) {

    // The name (symbol) that is being monitored...

    set(ptr(daemon) + 2, slot);
  }

  public void daemonSetTarget(int daemon, int target) {

    // Not used in XMF...

    set(ptr(daemon) + 6, target);
  }

  public void daemonSetTraced(int daemon, int bool) {

    // Is the daemon traced?...

    set(ptr(daemon) + 5, bool);
  }

  public void daemonSetType(int daemon, int type) {

    // Set the daemon type. Does it monitor a single slot, a collection
    // slot or all slots...

    set(ptr(daemon) + 1, type);
  }

  public int daemonSlot(int daemon) {

    // The name of the monitored slot (symbol)...

    return ref(ptr(daemon) + 2);
  }

  public int daemonTarget(int daemon) {

    // Not used in XMF...

    return ref(ptr(daemon) + 6);
  }

  public String daemonToString(int daemon) {

    // Return the daemon as a Java string...

    if (isSymbol(daemonId(daemon)))
      return "<Daemon " + valueToString(symbolName(daemonId(daemon))) + ">";
    else return "<Daemon " + valueToString(symbolName(funName(daemonAction(daemon)))) + " => " + valueToString(daemonId(daemon)) + ">";
  }

  public int daemonTraced(int daemon) {

    // Is the daemon traced...

    return ref(ptr(daemon) + 5);
  }

  public int daemonType(int daemon) {

    // The type of daemon...

    return ref(ptr(daemon) + 1);
  }

  public int dynamicBindingName(int binding) {

    // The name of a dynamic in a function...

    return consHead(binding);
  }

  public void dynamicBindingSetValue(int binding, int value) {

    // Change the value associated with a dynamic in a function...

    consSetTail(binding, value);
  }

  public int dynamicBindingValue(int binding) {

    // The value of a dynamic name in a function...

    return consTail(binding);
  }

  public int dynamicCellType(int cell) {

    // Encoded as a dynamic name binding or a table of bindings...

    return consHead(cell);
  }

  public int dynamicCellValue(int cell) {

    // The value of a cell...

    return consTail(cell);
  }

  public int floatAdd(int f1, int f2) {
    return mkFloat(Float.toString(Float.parseFloat(valueToString(f1)) + Float.parseFloat(valueToString(f2))));
  }

  public int floatDiv(int f1, int f2) {
    return mkFloat(Float.toString(Float.parseFloat(valueToString(f1)) / Float.parseFloat(valueToString(f2))));
  }

  public int floatFloor(int f) {
    return mkInt((int) Math.floor(Double.parseDouble(valueToString(f))));
  }

  public boolean floatGreater(int f1, int f2) {
    float float1 = Float.parseFloat(valueToString(f1));
    float float2 = Float.parseFloat(valueToString(f2));
    return float1 > float2;
  }

  public boolean floatLess(int f1, int f2) {
    float float1 = Float.parseFloat(valueToString(f1));
    float float2 = Float.parseFloat(valueToString(f2));
    return float1 < float2;
  }

  public int floatMul(int f1, int f2) {
    return mkFloat(Float.toString(Float.parseFloat(valueToString(f1)) * Float.parseFloat(valueToString(f2))));
  }

  public int floatRound(int f) {
    return mkInt((int) Math.round(Double.parseDouble(valueToString(f))));
  }

  public void floatSetString(int f, int str) {
    set(ptr(f), str);
  }

  public int floatSqrt(int f) {
    float float1 = Float.parseFloat(valueToString(f));
    float float2 = (float) Math.sqrt((double) float1);
    return mkFloat("" + float2);
  }

  public int floatString(int f) {
    return ref(ptr(f));
  }

  public int floatSub(int f1, int f2) {
    return mkFloat(Float.toString(Float.parseFloat(valueToString(f1)) - Float.parseFloat(valueToString(f2))));
  }

  public String floatToString(int f) {
    return valueToString(floatString(f));
  }

  public ForeignFun foreignFun(int index) {

    // Foreign funs live in the foreignFuns vector and are
    // referenced by index. They are essentially Java methods
    // that expect to be supplied with the current VM state
    // and can do what they like with it...

    return (ForeignFun) foreignFuns.elementAt(index);
  }

  public int foreignFunArity(int fun) {

    // The number of arguments expected by the foreign
    // function...

    return foreignFun(value(fun)).arity();
  }

  public int foreignFunIndex(int word) {

    // Access the index of the foreign fun...

    return value(word);
  }

  public int foreignFunName(int fun) {

    // The name of the foreign fun. Essentially the
    // same as the name ofthe underlying method...

    return mkString(foreignFun(value(fun)).name());
  }

  public String foreignFunToString(int word) {
    return "ForeignFun(" + foreignFunIndex(word) + ")";
  }

  public ForeignObject foreignObjectAt(int index) {

    // Foreign objects live in the foreignObjects table and
    // are indexed...

    return foreignObjects.elementAt(index);
  }

  public Object foreignObj(int index) {
    return foreignObjects.elementAt(index).getObject();
  }

  public int foreignObjIndex(int word) {
    return value(word);
  }

  public String foreignObjToString(int word) {
    return foreignObj(foreignObjIndex(word)).toString();
  }

  public int forwardRefListeners(int ref) {
    return ref(value(ref) + 2);
  }

  public int forwardRefPath(int ref) {
    return ref(value(ref));
  }

  public int forwardRefs() {
    return forwardRefs;
  }

  public void forwardRefSetListeners(int ref, int listeners) {
    set(value(ref) + 2, listeners);
  }

  public void forwardRefSetPath(int ref, int path) {
    set(value(ref), path);
  }

  public void forwardRefSetValue(int ref, int value) {
    set(value(ref) + 1, value);
  }

  public int forwardRefValue(int ref) {
    return ref(value(ref) + 1);
  }

  public int funArity(int fun) {

    // The number of arguments required by a function...

    return value(ref(ptr(fun)));
  }

  public int funCode(int fun) {

    // The function code box...

    return ref(ptr(fun) + 2);
  }

  public int funDocumentation(int fun) {

    // The documentation for the function...

    int doc = funGetStringProperty(fun, "Doc");
    if (doc == -1)
      return mkString("");
    else return doc;
  }

  public int funDynamics(int fun) {

    // Each function has dynamics that contain imported variables that
    // are available to the function. The dynamics are encoded as a
    // sequence of pairs where the head of the pair is a tag defining
    // whether the dynamic is a variable or a table imported from a
    // name-space...

    return ref(ptr(fun) + 6);
  }

  public int funGetStringProperty(int fun, String name) {

    // Functions have properties that are implemented as
    // sequences of pairs...

    int cell = funGetStringPropertyCell(fun, name);
    if (cell == -1)
      return -1;
    else return consTail(cell);
  }

  public int funGetStringPropertyCell(int fun, String name) {

    // Get the property cell with the given name from the function
    // property list...

    int properties = funProperties(fun);
    while (properties != nilValue) {
      int cell = consHead(properties);
      int value = consHead(cell);
      if (isString(value) && stringEqual(value, name)) {
        return cell;
      } else properties = consTail(properties);
    }
    return -1;
  }

  public int funGlobals(int fun) {

    // the globals of a function are the closed in variables...

    return ref(ptr(fun) + 1);
  }

  public int funInstLevel(int fun) {

    // The number of arguments required by a function...
     int instLevel = funGetStringProperty(fun, "instLevel");
    return instLevel;
  }
  
  public int funIsIntrinsic(int fun) {

    int isIntrinsic = funGetStringProperty(fun, "Intrinsic");
    if (isIntrinsic == -1)
      return falseValue;
    else return isIntrinsic;
  }
  
  public int funIsVarArgs(int fun) {

	    // A function may have var=args in which case extra argument
	    // that are supplied tothe function are packaged up as a single
	    // sequence and supplied as the last argument to the function...

	    int varArgs = funGetStringProperty(fun, "VarArgs");
	    if (varArgs == -1)
	      return falseValue;
	    else return varArgs;
	  }

  public int funName(int fun) {

    // The name of the function (from the code box)...

    return codeBoxName(funCode(fun));
  }

  public int funOwner(int fun) {

    // The function owner. Note that there are some
    // VM instructions that use this to resolve variable
    // references...

    return ref(ptr(fun) + 4);
  }

  public boolean funPersistentDaemon(int fun) {

    // Finds whether the function has a persistent daemon
    // property. Used to calculate the collection of persistent
    // daemons for an object.

    int properties = funProperties(fun);
    while (properties != nilValue) {
      int property = consHead(properties);
      properties = consTail(properties);
      if (consHead(property) == mkSymbol("persistentDaemon")) return true;
    }
    return false;
  }

  public int funProperties(int fun) {

    // The sequence of properties...

    return ref(ptr(fun) + 8);
  }

  public void funRemoveStringProperty(int fun, String name) {

    // Get rid of a property...

    int cell = funGetStringPropertyCell(fun, name);
    int props = funProperties(fun);
    if (cell != -1) funSetProperties(fun, consRemove(props, cell));
  }

  public int funSelf(int fun) {

    // Thevalue used as 'self' during the execution of a function.
    // This value is set when the function is created...

    return ref(ptr(fun) + 3);
  }

  public void funSetArity(int fun, int arity) {

    // Change the number of expected positional args...

    set(ptr(fun), mkInt(value(arity)));
  }

  public void funSetCode(int fun, int codeBox) {

    // Set the code box...

    set(ptr(fun) + 2, codeBox);
  }

  public void funSetDocumentation(int fun, int doc) {

    // Set the documentation ...

    if (doc == undefinedValue)
      funRemoveStringProperty(fun, "Doc");
    else funSetStringProperty(fun, "Doc", doc);
  }

  public void funSetDynamics(int fun, int dynamics) {

    // Set the dynamics list...

    set(ptr(fun) + 6, dynamics);
  }

  public void funSetGlobals(int fun, int globals) {

    // Set the globals vector list..

    set(ptr(fun) + 1, globals);
  }

  
  public void funSetInstLevel(int fun, int instLevel) {

    // Change the InstLevel...
	  funSetStringProperty(fun, "instLevel", instLevel);
//    set(ptr(fun), mkInt(value(instLevel)));
  }
  
  public void funSetIsIntrinsic(int fun, int isIntrinsic) {

    // Set the boolean to be isIntrinsic...

    if (isIntrinsic == falseValue)
      funRemoveStringProperty(fun, "Intrinsic");
    else funSetStringProperty(fun, "Intrinsic", isIntrinsic);
  }

  public void funSetIsVarArgs(int fun, int isVarArgs) {

    // Set the boolean to be isVarArgs...

    if (isVarArgs == falseValue)
      funRemoveStringProperty(fun, "VarArgs");
    else funSetStringProperty(fun, "VarArgs", isVarArgs);
  }
  public void funSetName(int fun, int name) {

    // Set the name in the code box...

    codeBoxSetName(funCode(fun), name);
  }

  public void funSetOwner(int fun, int owner) {

    // Set the owner of the function...

    set(ptr(fun) + 4, owner);
  }

  public void funSetProperties(int fun, int properties) {

    // Set the property list of the function...

    set(ptr(fun) + 8, properties);
  }

  public void funSetSelf(int fun, int self) {

    // Set the self value...

    set(ptr(fun) + 3, self);
  }

  public void funSetSig(int fun, int args) {

    // Set the function signature...

    set(ptr(fun) + 7, args);
  }

  public void funSetStringProperty(int fun, String name, int value) {

    // Set the valueof a property in the function...

    int cell = funGetStringPropertyCell(fun, name);
    if (cell != -1)
      consSetTail(cell, value);
    else {
      int props = funProperties(fun);
      funSetProperties(fun, mkCons(mkCons(mkString(name), value), props));
    }
  }

  public void funSetSupers(int fun, int supers) {

    // Set the supers list of the function...

    set(ptr(fun) + 5, supers);
  }

  public void funSetTraced(int fun, int traced) {

    // Set whether the function is traced or not...

    if (traced == undefinedValue)
      funRemoveStringProperty(fun, "Traced");
    else funSetStringProperty(fun, "Traced", traced);
  }

  public int funSig(int fun) {

    // A function has a signature that encodes the names of the
    // arguments, their types and the return type of the function.
    // the encoding is definedin Classifier...

    return ref(ptr(fun) + 7);
  }

  public int funArgNames(int fun) {

    // Returns a sequence of strings that name the arguments of
    // the function. These are calculated from the signature...

    int sig = funSig(fun);
    if (sig == undefinedValue || sig == nilValue)
      return nilValue;
    else return consFunArgNames(sig);
  }

  public int consFunArgNames(int sig) {
    if (consTail(sig) == nilValue)
      return nilValue;
    else {
      int argNames = consFunArgNames(consTail(sig));
      int entry = consHead(sig);
      return mkCons(consHead(entry), argNames);
    }
  }

  public int funSupers(int fun) {

    // A function has a sequence of functions that allows it to
    // continue the lookup when it executes 'super'. By convention
    // this list always contains the function itself at the head
    // since this allows the VM to reference the executing function
    // in a stack frame...

    return ref(ptr(fun) + 5);
  }

  public String funToString(int fun) {

    // Represent a function as a Java string...

    return "Fun(" + valueToString(codeBoxName(funCode(fun))) + ")";
  }

  public int funTraced(int fun) {

    // Is the function traced?...

    int traced = funGetStringProperty(fun, "Traced");
    if (traced == -1)
      return undefinedValue;
    else return traced;
  }

  public int globalsArray(int globals) {

    // The storage for closure variables...

    return arrayRef(globals, 0);
  }

  public int globalsPrev(int globals) {

    // The previous globals structure. Links to the enclosing
    // binding scope for globals...

    return arrayRef(globals, 1);
  }

  public void globalsSetArray(int globals, int array) {
    arraySet(globals, 0, array);
  }

  public void globalsSetPrev(int globals, int prev) {
    arraySet(globals, 1, prev);
  }

  public static String intToString(int word) {

    // return a Java string representation of an int...

    return "" + (intValue(word));
  }

  public final static int intValue(int word) {

    // Negative integers are represented differently
    // to positive integers...

    if (isNegInt(word))
      return -value(word);
    else return value(word);
  }

  public String nilToString() {
    return "()";
  }

  public void objAddAttribute(int obj, int att) {

    // Add an attribute to an object...

    int hasAtt = objAttribute(obj, attributeName(att));
    if (hasAtt != -1)
      attributeSetValue(hasAtt, attributeValue(att));
    else objSetAttributes(obj, mkCons(att, objAttributes(obj)));
  }

  public void objAddAttribute(int obj, int name, int value) {

    // Create and add an attribute to an object...

    if (isString(name)) name = mkSymbol(name);
    int att = mkAttribute();
    attributeSetName(att, name);
    attributeSetValue(att, value);
    objAddAttribute(obj, att);
  }

  public int objAttribute(int obj, int name) {

    // Get the attribute with the given name in an object or
    // return -1...

    int atts = objAttributes(obj);
    int att = 0;
    boolean found = false;
    while (atts != nilValue && !found) {
      att = consHead(atts);
      if (equalValues(attributeName(att), name))
        found = true;
      else atts = consTail(atts);
    }
    if (found)
      return att;
    else return -1;
  }

  public int objAttributes(int word) {

    // Get all the attributes of an object. Note that
    // the attributes are the SLOTS of an object at
    // the XOCL level...

    return ref(ptr(word) + 1);
  }

  public int objAttValue(int obj, int name) {

    // Get the value of an attribute in an object. The
    // name should be a symbol. If theattribute does not
    // exist then -1 is returned...

    int att = objAttribute(obj, name);
    if (att == -1)
      return -1;
    else return attributeValue(att);
  }

  public int objDaemons(int obj) {

    // Get the daemons monitoring this object...

    return ref(ptr(obj) + 2);
  }

  public int objDaemonsActive(int obj) {

    // Are the daemons active on this object?...

    int propertyMask = value(objProperties(obj));
    if (getBit(propertyMask, OBJ_DAEMONS_ACTIVE) == 1)
      return trueValue;
    else return falseValue;
  }

  public int objGetContents(int obj) {

    // Important that this returns -1 if the object does not
    // have an attribute 'owner'. Can then be used as a test
    // for an owned element...

    return objAttValue(obj, theSymbolContents);
  }

  public int objGetName(int obj) {

    // Important that this returns -1 if the object does not
    // have an attribute 'name'. Can then be used as a test
    // for a named element...

    return objAttValue(obj, theSymbolName);
  }

  public int objGetOwner(int obj) {

    // Important that this returns -1 if the object does not
    // have an attribute 'owner'. Can then be used as a test
    // for an owned element...

    return objAttValue(obj, theSymbolOwner);
  }

  public boolean objHasAtt(int obj, int name) {

    // Return true when the object has an attribute with the supplied
    // name. The name should be a symbol...

    return objAttribute(obj, name) != -1;
  }

  public int objHotLoad(int obj) {

    // Hot loaded objects become active when they are loaded by the
    // serializer...

    int propertyMask = value(objProperties(obj));
    if (getBit(propertyMask, OBJ_HOT_LOAD) == 1)
      return trueValue;
    else return falseValue;
  }

  public int objPersistentDaemons(int obj) {

    // The daemons on an object are invocable things.
    // Any operations that do no have their 'persistent'
    // property set are not returned by this method...

    int pdaemons = nilValue;
    int daemons = objDaemons(obj);
    while (daemons != nilValue) {
      int daemon = consHead(daemons);
      daemons = consTail(daemons);
      if (isFun(daemon) && funPersistentDaemon(daemon)) pdaemons = mkCons(daemon, pdaemons);
      if (isDaemon(daemon) && daemonIsPersistent(daemon)) pdaemons = mkCons(daemon, pdaemons);
    }
    return pdaemons;
  }

  public int objProperties(int obj) {

    // The properties of an object are encoded in a single
    // machine word. The properties are bit positions in this
    // word...

    return ref(ptr(obj) + 3);
  }

  public void objRemoveAttribute(int obj, int name) {

    // Remove the attribute of an object...

    if (isString(name)) name = mkSymbol(name);
    int att = objAttribute(obj, name);
    int atts = objAttributes(obj);
    if (att != -1) {
      objSetAttributes(obj, this.consRemove(atts, att));
    }
  }

  public void objSetAttributes(int obj, int attributes) {

    // set the list of attributes in an object...

    set(ptr(obj) + 1, attributes);
  }

  public int objSetAttValue(int obj, int name, int value) {

    // Set the value of an attributein an object...

    int att = objAttribute(obj, name);
    if (att == -1)
      return -1;
    else {
      undo.setSlot(obj, name, value, attributeValue(att));
      attributeSetValue(att, value);
      return value;
    }
  }

  public void objSetDaemons(int obj, int daemons) {

    // Set the list of daemons monitoring the object...

    undo.setDaemons(obj, daemons, objDaemons(obj));
    set(ptr(obj) + 2, daemons);
  }

  public void objSetDaemonsActive(int obj, int bool) {

    // Set whether the daemons are active in an object...

    int propertyMask = value(objProperties(obj));
    if (bool == trueValue)
      objSetProperties(obj, mkInt(setBit(propertyMask, OBJ_DAEMONS_ACTIVE, 1)));
    else objSetProperties(obj, mkInt(setBit(propertyMask, OBJ_DAEMONS_ACTIVE, 0)));
  }

  public void objSetDefaultGetMOP(int obj, boolean bool) {

    // Objects are accessed via a MOP. The default MOP is implemented by the
    // VM. However, if the object's class is an instance of a non-standard
    // meta-class that redefines various operations, then slot access,
    // update
    // and message delivery is implemented differently. In order to make the
    // processing of the MOP efficient, the VM caches the information
    // relating
    // to whether it can handle the MOP or whether the upper levels handle
    // the MOP. This operation sets the slot value property of the MOP
    // cache...

    int propertyMask = value(objProperties(obj));
    if (bool)
      objSetProperties(obj, mkInt(setBit(propertyMask, OBJ_DEFAULT_GET_MOP, 1)));
    else objSetProperties(obj, mkInt(setBit(propertyMask, OBJ_DEFAULT_GET_MOP, 0)));
  }

  public void objSetDefaultSendMOP(int obj, boolean bool) {

    // Objects are accessed via a MOP. The default MOP is implemented by the
    // VM. However, if the object's class is an instance of a non-standard
    // meta-class that redefines various operations, then slot access,
    // update
    // and message delivery is implemented differently. In order to make the
    // processing of the MOP efficient, the VM caches the information
    // relating
    // to whether it can handle the MOP or whether the upper levels handle
    // the MOP. This operation sets the message property of the MOP cache...

    int propertyMask = value(objProperties(obj));
    if (bool)
      objSetProperties(obj, mkInt(setBit(propertyMask, OBJ_DEFAULT_SEND_MOP, 1)));
    else objSetProperties(obj, mkInt(setBit(propertyMask, OBJ_DEFAULT_SEND_MOP, 0)));
  }

  public void objSetDefaultSetMOP(int obj, boolean bool) {

    // Objects are accessed via a MOP. The default MOP is implemented by the
    // VM. However, if the object's class is an instance of a non-standard
    // meta-class that redefines various operations, then slot access,
    // update
    // and message delivery is implemented differently. In order to make the
    // processing of the MOP efficient, the VM caches the information
    // relating
    // to whether it can handle the MOP or whether the upper levels handle
    // the MOP. This operation sets the slot update property of the MOP
    // cache...

    int propertyMask = value(objProperties(obj));
    if (bool)
      objSetProperties(obj, mkInt(setBit(propertyMask, OBJ_DEFAULT_SET_MOP, 1)));
    else objSetProperties(obj, mkInt(setBit(propertyMask, OBJ_DEFAULT_SET_MOP, 0)));
  }

  public void objSetHotLoad(int obj, int bool) {

    // Sets whether the object should invoke its hotLoaded() operation when
    // it is deserialized...

    int propertyMask = value(objProperties(obj));
    if (bool == trueValue)
      objSetProperties(obj, mkInt(setBit(propertyMask, OBJ_HOT_LOAD, 1)));
    else objSetProperties(obj, mkInt(setBit(propertyMask, OBJ_HOT_LOAD, 0)));
  }

  public void objSetNotVMNew(int obj, boolean bool) {

    // Class instantiation can be handled by the VM under certain
    // circumstances.
    // This occurs when the class does not implement any special
    // initialisation
    // operations (for example as part of the constructor). If the class can
    // be
    // instantiated by the VM then this property is set in the class and
    // then
    // instantiation is very efficient...

    int propertyMask = value(objProperties(obj));
    if (bool)
      objSetProperties(obj, mkInt(setBit(propertyMask, OBJ_NOT_VM_NEW, 1)));
    else objSetProperties(obj, mkInt(setBit(propertyMask, OBJ_NOT_VM_NEW, 0)));
  }

  public void objSetProperties(int obj, int properties) {

    // Set the property word in an object...

    set(ptr(obj) + 3, properties);
  }

  public void objSetSaveAsLookup(int obj, boolean bool) {

    // Not used...

    int propertyMask = value(objProperties(obj));
    if (bool)
      objSetProperties(obj, mkInt(setBit(propertyMask, OBJ_SAVE_AS_LOOKUP, 1)));
    else objSetProperties(obj, mkInt(setBit(propertyMask, OBJ_SAVE_AS_LOOKUP, 0)));
  }

  public void objSetType(int obj, int type) {

    // The type of an object is the classifier that is returned when
    // the upport levels perform of(). This allows the type of an
    // object to be set...

    set(ptr(obj), type);
  }

  public String objToString(int obj, int depth) {

    // Return a string representation of the object...

    if (objType(obj) == theClassClass)
      return classToString(obj);
    else return basicObjToString(obj, depth);
  }

  public String basicObjToString(int obj, int depth) {
    int atts = objAttributes(obj);
    String s = valueToString(symbolName(objGetName(type(obj)))) + "[fields=";
    while (atts != nilValue) {
      int att = consHead(atts);
      atts = consTail(atts);
      int name = symbolName(attributeName(att));
      s = s + valueToString(name, depth + 1);
      if (atts != nilValue) s = s + ",";
    }
    return s + "]";
  }

  public String classToString(int c) {
    int name = objAttValue(c, theSymbolName);
    if (name == -1)
      return "<Class ???>";
    else return "<Class " + valueToString(symbolName(name)) + ">";
  }

  public int objType(int obj) {

    // Get the classifier of an object...
    return ref(ptr(obj));
  }

  public int seqDifference(int seq1, int seq2) {

    // One sequence with all the elements of the other sequence
    // removed. Returns a new sequence...

    while (seq2 != nilValue) {
      seq1 = seqExcluding(seq1, consHead(seq2));
      seq2 = consTail(seq2);
    }
    return seq1;
  }

  public int seqExcluding(int seq, int element) {

    // Remove an element from a sequence and produce a new sequence...

    if (seqIncludes(seq, element))
      return seqExcluding1(seq, element);
    else return seq;
  }

  public int seqExcluding1(int seq, int element) {

    // Implemented using a loop since Java is not tail recursive...

    int head = nilValue;
    int tail = nilValue;
    boolean done = false;
    while (seq != nilValue && !done) {
      if (equalValues(element, consHead(seq))) {
        if (head == nilValue)
          head = consTail(seq);
        else consSetTail(tail, consTail(seq));
        done = true;
      } else if (head == nilValue) {
        head = mkCons(consHead(seq), nilValue);
        tail = head;
        seq = consTail(seq);
      } else {
        consSetTail(tail, mkCons(consHead(seq), nilValue));
        tail = consTail(tail);
        seq = consTail(seq);
      }
    }
    return head;
  }

  public boolean seqIncludes(int seq, int element) {

    // Does the sequence include the element?...

    while (seq != nilValue) {
      if (equalValues(consHead(seq), element))
        return true;
      else seq = consTail(seq);
    }
    return false;
  }

  public int setDifference(int set1, int set2) {

    // Remove all the elements of one set from another returning a new
    // set...

    int elements = setElements(set2);
    while (elements != nilValue) {
      set1 = setExcluding(set1, consHead(elements));
      elements = consTail(elements);
    }
    return set1;
  }

  public int setElements(int set) {

    // The elements in a set. The elements of a set are a sequence...

    return ref(ptr(set));
  }

  public int setExcluding(int set, int element) {

    // Remove an element from a set returning a new set...

    if (includes(set, element))
      return setExcluding1(set, element);
    else return set;
  }

  public int setExcluding1(int set, int element) {

    // Remove the element iteratively...

    int elements = setElements(set);
    int newElements = nilValue;
    while (elements != nilValue) {
      if (!equalValues(consHead(elements), element)) newElements = mkCons(consHead(elements), newElements);
      elements = consTail(elements);
    }
    return mkSet(newElements);
  }

  public int setHashCode(int set) {
    int elements = setElements(set);
    int hashCode = 0;
    while (elements != nilValue) {
      hashCode = hashCode | hashCode(consHead(elements));
      elements = consTail(elements);
    }
    return hashCode;
  }

  public String setToString(int set, int depth) {
    int list = setElements(set);
    String s = "Set{";
    int index = 0;
    while (list != nilValue) {
      index++;
      if (index > maxPrintElements) {
        list = nilValue;
        s = s + "...";
      } else {
        s = s + valueToString(consHead(list), depth + 1);
        list = consTail(list);
        if (list != nilValue) s = s + ",";
      }
    }
    s = s + "}";
    return s;
  }

  public int stringAppend(int s1, int s2) {

    // Takes two strings 's1' and 's2' and returns a new string
    // that contains the characters of 's1' followed by the
    // characters of 's2'...

    int stringLength1 = stringLength(s1);
    int stringLength2 = stringLength(s2);
    int wordLength1 = (stringLength1 / 4) + 1;
    // int wordLength2 = (stringLength2 / 4) + 1;
    int length = stringLength1 + stringLength2;
    int newString = mkString(length);
    for (int i = 0; i < wordLength1; i++)
      set(ptr(newString) + 1 + i, ref(ptr(s1) + 1 + i));
    for (int i = 0; i < stringLength2; i++)
      stringSet(newString, stringLength1 + i, stringRef(s2, i));
    return newString;
  }

  public int stringAsSeq(int string) {

    // Turn a string into a sequence of integers...

    int seq = nilValue;
    for (int i = stringLength(string) - 1; i >= 0; i--) {
      int c = stringRef(string, i);
      seq = mkCons(mkInt(c), seq);
    }
    return seq;
  }

  public int stringAsSet(int string) {

    // Turn a string into a set of integers...

    return asSet(stringAsSeq(string));
  }

  public int stringLineCount(int string, int charPos) {
    int lines = 1;
    for (int i = 0; i < charPos; i++) {
      int c = stringRef(string, i);
      if (c == '\n') lines++;
    }
    return lines;
  }

  public int asSet(int value) {
    switch (tag(value)) {
    case SET:
      return value;
    case CONS:
    case NIL:
      return mkSet(removeDuplicates(value));
    case STRING:
      return stringAsSet(value);
    default:
      throw new MachineError(TYPE, "asSet: expecting a collection.", value, theTypeSetOfElement);
    }
  }

  public int removeDuplicates(int seq) {
    if (seq == nilValue)
      return nilValue;
    else return mkCons(consHead(seq), excluding(removeDuplicates(consTail(seq)), consHead(seq)));
  }

  public boolean stringEqual(int s1, CharSequence s2) {

    // Whether a machine string is equivalent to a supplied character
    // sequence...

    int l1 = stringLength(s1);
    int l2 = s2.length();
    if (l1 == l2) {
      for (int i = 0; i < l1; i++)
        if (stringRef(s1, i) != s2.charAt(i)) return false;
      return true;
    } else return false;
  }

  public boolean stringGreater(int s1, int s2) {

    // Is one string lexicographically greater than another string...

    int l1 = stringLength(s1);
    int l2 = stringLength(s2);
    int length = Math.min(l1, l2);
    for (int i = 0; i < length; i++) {
      int c1 = stringRef(s1, i);
      int c2 = stringRef(s2, i);
      if (c1 < c2) return false;
      if (c1 > c2) return true;
    }
    return l1 > l2;
  }

  public int stringHashCode(CharSequence string) {

    // A hash-code for a string adds up the character codes...

    int hashCode = 0;
    for (int i = 0; i < string.length(); i++)
      hashCode = hashCode + string.charAt(i);
    return hashCode;
  }

  public int stringHashCode(int string) {

    // A hash-code for a string adds up the character codes...

    int hashCode = 0;
    for (int i = 0; i < stringLength(string); i++)
      hashCode = hashCode + stringRef(string, i);
    return hashCode;
  }

  public boolean stringIncludes(int string, int c) {

    // Does the string include the character?...

    for (int i = 0; i < stringLength(string); i++)
      if (stringRef(string, i) == value(c)) return true;
    return false;
  }

  public int stringLength(int word) {

    // The length of the string...

    return value(ref(ptr(word)));
  }

  public boolean stringLess(int s1, int s2) {

    // Is one string less than the other in a lexicographic ordering...

    int l1 = stringLength(s1);
    int l2 = stringLength(s2);
    int length = Math.min(l1, l2);
    for (int i = 0; i < length; i++) {
      int c1 = stringRef(s1, i);
      int c2 = stringRef(s2, i);
      if (c1 > c2) return false;
      if (c1 < c2) return true;
    }
    return l1 < l2;
  }

  public int stringRef(int word, int index) {

    // Strings are encoded as an integer (the character length)
    // followed by chars encoded as bytes rounded up to the
    // smallest word boundary...

    int wordIndex = index / 4;
    int charIndex = (index % 4) * 8;
    int chars = ref(ptr(word) + wordIndex + 1);
    int mask = BYTE1 << charIndex;
    return (chars & mask) >>> charIndex;
  }

  public void stringSet(int word, int index, int value) {

    // Update the character at position 'index' with the
    // character whose code is 'value'...

    int wordIndex = index / 4;
    int charIndex = (index % 4) * 8;
    int chars = ref(ptr(word) + wordIndex + 1);
    chars = chars | (value << charIndex);
    set(ptr(word) + wordIndex + 1, chars);
  }

  public String stringToString(int word) {

    // return the machine string as a Java string...

    return basicStringToString(word);
  }

  public String basicStringToString(int word) {
    String s = "";
    int length = stringLength(word);
    for (int i = 0; i < length; i++)
      s = s + (char) stringRef(word, i);
    return s;
  }

  public int stringCharCount(int string, int charPos) {
    int chars = 0;
    for (int i = 0; i < charPos; i++) {
      int c = stringRef(string, i);
      if (c == '\n')
        chars = 0;
      else chars++;
    }
    return chars;
  }

  public int symbolName(int symbol) {

    // The name of a symbol is a string...

    return ref(ptr(symbol));
  }

  public void symbolSetName(int symbol, int string) {

    // Change the name of a symbol...

    set(ptr(symbol), string);
  }

  public void symbolSetValue(int symbol, int value) {

    // Symbols have values...

    set(ptr(symbol) + 1, value);
  }

  public String symbolToString(int symbol) {

    // Translate a symbol to a Java string...

    return "'" + valueToString(symbolName(symbol)) + "'";
  }

  public int symbolValue(int symbol) {

    // The value associated with a symbol...

    return ref(ptr(symbol) + 1);
  }

  public int hashTableBucket(int table, CharSequence key) {

    // Get the bucket in the table indexed by the character sequence...

    return arrayRef(table, hashTableIndex(table, key));
  }

  public int hashTableBucket(int table, int key) {

    // Get the bucket indexed by the key (any value)...

    return arrayRef(table, hashTableIndex(table, key));
  }

  public void hashTableClear(int table) {

    // Empty the table...

    int size = arrayLength(table);
    for (int i = 0; i < size; i++)
      arraySet(table, i, nilValue);
  }

  public int hashTableContents(int table) {

    // Construct a set of all the values in the table...

    int set = emptySet;
    int length = arrayLength(table);
    for (int i = 0; i < length; i++) {
      int bucket = arrayRef(table, i);
      while (bucket != nilValue) {
        int cell = consHead(bucket);
        set = setIncluding(set, consTail(cell));
        bucket = consTail(bucket);
      }
    }
    return set;
  }

  public int hashTableGet(int table, CharSequence key) {

    // Get the value of the key in the table. Return -1 if
    // the key does not exist...

    int cell = hashTableGetCell(table, key);
    if (cell == -1)
      return -1;
    else return consTail(cell);
  }

  public int hashTableGet(int table, int key) {

    // Get the valueof the key in the table. Return -1 if
    // the key does not exist...

    int cell = hashTableGetCell(table, key);
    if (cell == -1)
      return -1;
    else return consTail(cell);
  }

  public int hashTableGetCell(int table, CharSequence key) {

    // Get the cell associated with the key in the table. Return
    // -1 if the key does not exist...

    int bucket = hashTableBucket(table, key);
    boolean found = false;
    while ((bucket != nilValue) && !found) {
      int pair = consHead(bucket);
      int pairKey = consHead(pair);
      if (isString(pairKey) && stringEqual(pairKey, key)) return pair;
      bucket = consTail(bucket);
    }
    return -1;
  }

  public int hashTableGetCell(int table, int key) {

    // Get the cell associated with the key in the table. Return
    // -1 if the key does not exist...

    int bucket = hashTableBucket(table, key);
    boolean found = false;
    while ((bucket != nilValue) && !found) {
      int pair = consHead(bucket);
      if (equalValues(consHead(pair), key)) return pair;
      bucket = consTail(bucket);
    }
    return -1;
  }

  public boolean hashTableHasKey(int table, int key) {

    // Return true when the table contains the key...

    return hashTableGet(table, key) != -1;
  }

  public boolean hashTableHasValue(int table, int value) {

    // Return true when the table contains the value...

    int length = arrayLength(table);
    for (int i = 0; i < length; i++) {
      int bucket = arrayRef(table, i);
      while (bucket != nilValue) {
        int cell = consHead(bucket);
        int cellValue = consTail(cell);
        if (equalValues(cellValue, value)) return true;
        bucket = consTail(bucket);
      }
    }
    return false;
  }

  public int hashTableIndex(int table, CharSequence key) {

    // Return an index into the table based on the hash code
    // of the key...

    return hashCode(key) % arrayLength(table);
  }

  public int hashCode(CharSequence value) {
    return stringHashCode(value);
  }

  public int hashTableIndex(int table, int key) {

    // Return an index into the table based on the hash code
    // of the key...

    return hashCode(key) % arrayLength(table);
  }

  public boolean hashTableIsEmpty(int table) {

    // Return true when the table is empty...

    boolean isEmpty = true;
    int size = arrayLength(table);
    for (int i = 0; i < size && isEmpty; i++)
      isEmpty = arrayRef(table, i) == nilValue;
    return isEmpty;
  }

  public int hashTableKeys(int table) {

    // Return a sequence of all the keys in the table...

    int keys = nilValue;
    int length = arrayLength(table);
    for (int i = 0; i < length; i++) {
      int bucket = arrayRef(table, i);
      while (bucket != nilValue) {
        int cell = consHead(bucket);
        int key = consHead(cell);
        keys = mkCons(key, keys);
        bucket = consTail(bucket);
      }
    }
    return keys;
  }

  public void hashTablePut(int table, int key, int value) {

    // Update the value of a key in the table...

    int cell = hashTableGetCell(table, key);
    if (cell == -1) {
      int newCell = mkCons(key, value);
      int index = hashCode(key) % arrayLength(table);
      int bucket = arrayRef(table, index);
      arraySet(table, index, mkCons(newCell, bucket));
    } else {
      undo.setTable(table, key, value, consTail(cell));
      consSetTail(cell, value);
    }
  }

  public int hashTableRemove(int table, int key) {

    // Delete a key from the table...

    int length = arrayLength(table);
    for (int i = 0; i < length; i++) {
      int bucket = arrayRef(table, i);
      int newBucket = nilValue;
      while (bucket != nilValue) {
        int cell = consHead(bucket);
        int k = consHead(cell);
        if (!equalValues(key, k)) newBucket = mkCons(cell, newBucket);
        bucket = consTail(bucket);
      }
      arraySet(table, i, newBucket);
    }
    return table;
  }

  public String hashTableToString(int table) {

    // Return the table as a Java string...

    String s = "<Table ";
    int length = arrayLength(table);
    int entries = 0;
    int usedBuckets = 0;
    int maxBucketLength = 0;
    for (int i = 0; i < length; i++) {
      int bucket = arrayRef(table, i);
      int bucketLength = 0;
      if (bucket != nilValue) usedBuckets++;
      while (bucket != nilValue) {
        entries++;
        bucketLength++;
        bucket = consTail(bucket);
      }
      maxBucketLength = Math.max(bucketLength, maxBucketLength);
    }
    int percentFull = (int) (((double) usedBuckets / (double) length) * 100);
    s = s + "length = " + length;
    s = s + " entries = " + entries;
    s = s + " full = " + percentFull + "%";
    s = s + " max bucket length = " + maxBucketLength + ">";
    return s;
  }

  public final static int threadId(int thread) {

    // Each thread has an identifier...

    return value(thread);
  }

  public final static String threadToString(int word) {

    // Return a Java representation of a thread...

    return "<Thread " + threadId(word) + ">";
  }

  public int copy(int value) {

    // All elements at the XOCL level implement a copy()
    // operation that returns a copy of the receiver. By default
    // the copy() operation calls this Java operation that
    // dispatches to type-specific copy operations...

    switch (tag(value)) {
    case ARRAY:
      return copyArray(value);
    case INT:
    case BOOL:
    case FLOAT:
    case CLIENT:
    case INPUT_CHANNEL:
    case OUTPUT_CHANNEL:
    case DAEMON:
      return value;
    case OBJ:
      return copyObj(value);
    case FUN:
      return copyFun(value);
    case STRING:
      return copyString(value);
    case CODEBOX:
      return copyCodeBox(value);
    case CONS:
      return mkCons(consHead(value), copy(consTail(value)));
    case NIL:
      return value;
    case SET:
      return value;
    case HASHTABLE:
      return copyHashTable(value);
    case BUFFER:
      return copyBuffer(value);
    case UNDEFINED:
      return value;
    default:
      throw new MachineError(ERROR, "machine.copy: unknown type to copy. " + tag(value));
    }
  }

  public int copyArray(int array) {

    // Copy the array but not the elements...

    int length = arrayLength(array);
    int newArray = mkArray(length);
    for (int i = 0; i < length; i++)
      arraySet(newArray, i, arrayRef(array, i));
    return newArray;
  }

  public int copyAttribute(int att) {

    // Copy the attribute but not the value...

    return mkAttribute(attributeName(att), attributeValue(att));
  }

  public int copyBucket(int bucket) {

    // This is called when we want to copy a bucket prior to
    // setting a value in the bucket. For example, this occurs
    // when daemons are fired...

    int newBucket = nilValue;
    while (bucket != nilValue) {
      int cell = consHead(bucket);
      bucket = consTail(bucket);
      int newCell = mkCons(consHead(cell), consTail(cell));
      newBucket = mkCons(newCell, newBucket);
    }
    return newBucket;
  }

  public int copyBuffer(int buffer) {

    // Copy the storage but not the elements...

    int copy = mkBuffer(value(bufferIncrement(buffer)));
    bufferSetDaemons(copy, bufferDaemons(buffer));
    bufferSetDaemonsActive(copy, bufferDaemonsActive(buffer));
    bufferSetStorage(copy, copyBufferStorage(bufferStorage(buffer)));
    bufferSetSize(copy, bufferSize(buffer));
    bufferSetAsString(copy, bufferAsString(buffer));
    return copy;
  }

  public int copyBufferStorage(int arrays) {
    if (arrays == nilValue)
      return arrays;
    else {
      int array = consHead(arrays);
      return mkCons(copy(array), copyBufferStorage(consTail(arrays)));
    }
  }

  public int copyCodeBox(int codeBox) {

    // Not sure why we need this...

    int newBox = mkCodeBox(codeBoxLocals(codeBox));
    codeBoxSetConstants(newBox, codeBoxConstants(codeBox));
    codeBoxSetInstrs(newBox, codeBoxInstrs(codeBox));
    codeBoxSetName(newBox, codeBoxName(codeBox));
    codeBoxSetSource(newBox, codeBoxSource(codeBox));
    codeBoxSetResourceName(newBox, codeBoxResourceName(codeBox));
    return newBox;
  }

  public void copyContStack(int cont) {

    // Copy a continuation stack...

    int length = value(contLength(cont));
    for (int i = 0; i < length; i++)
      valueStack.set(i, contRef(cont, i));
    valueStack.setTOS(length);
  }

  public int copyFun(int fun) {

    // Copy a function. The globals should really be copied
    // and the properties...

    int newFun = mkFun();
    funSetArity(newFun, funArity(fun));
    funSetGlobals(newFun, funGlobals(fun));
    funSetCode(newFun, copy(funCode(fun)));
    funSetSelf(newFun, funSelf(fun));
    funSetOwner(newFun, funOwner(fun));
    funSetSupers(newFun, funSupers(fun));
    funSetDynamics(newFun, funDynamics(fun));
    funSetSig(newFun, funSig(fun));
    funSetProperties(newFun, funProperties(fun));
    return newFun;
  }

  public int copyHashTable(int table) {

    // Copy a hash table but not the elements in
    // the table...

    int copy = copyArray(table);
    int length = arrayLength(table);
    for (int i = 0; i < length; i++)
      arraySet(copy, i, copyHashTableBucket(arrayRef(copy, i)));
    return mkPtr(HASHTABLE, copy);
  }

  public int copyHashTableBucket(int bucket) {
    if (bucket == nilValue)
      return bucket;
    else return mkCons(copy(consHead(bucket)), copyHashTableBucket(consTail(bucket)));
  }

  public int copyObj(int obj) {

    // Copy the slot storage for an object but not the
    // values in the slots...

    int atts = objAttributes(obj);
    int newObj = mkObj(objType(obj));
    int newAtts = nilValue;
    while (atts != nilValue) {
      int att = copyAttribute(consHead(atts));
      newAtts = mkCons(att, newAtts);
      atts = consTail(atts);
    }
    objSetAttributes(newObj, newAtts);
    return newObj;
  }

  public int copyString(int s) {

    // Should not really need this since we should not
    // be updating strings...

    int newString = mkString(stringLength(s));
    for (int i = 0; i < stringLength(s); i++)
      stringSet(newString, i, stringRef(s, i));
    return newString;
  }

  public boolean equalFloats(int f1, int f2) {

    // Two floats are equal when their string representations
    // are equal...

    return valueToString(f1).equals(valueToString(f2));
  }

  public boolean equalNumbers(int n1, int n2) {

    // Handle equality for different types of numbers...

    if (isFloat(n1) && isFloat(n2)) return equalFloats(n1, n2);
    if (isInt(n1) && isInt(n2)) return n1 == n2;
    if (isFloat(n1) && isInt(n2)) return equalFloats(n1, mkFloat(n2, 0));
    if (isInt(n1) && isFloat(n2)) return equalFloats(mkFloat(n1, 0), n2);
    if (isBigInt(n1) && isNum(n2)) return asBigInteger(n1).compareTo(ensureBigInt(n2)) == 0;
    if (isNum(n1) && isBigInt(n2)) return ensureBigInt(n1).compareTo(asBigInteger(n2)) == 0;
    return false;
  }

  public boolean equalRefPaths(int p1, int p2) {

    // Ref paths are implemented as sequences of strings.
    // Two paths are equal when they have the same strings in
    // the same order...

    if (p1 == nilValue && p2 == nilValue)
      return true;
    else if (p1 == nilValue || p2 == nilValue)
      return false;
    else {
      int n1 = consHead(p1);
      int n2 = consHead(p2);
      if (n1 == n2)
        return equalRefPaths(consTail(p1), consTail(p2));
      else return false;
    }
  }

  public boolean equalSets(int set1, int set2) {

    // Two sets are equal when they are subsets of each other...

    return subSet(set1, set2) && subSet(set2, set1);
  }

  public boolean subSet(int set1, int set2) {

    // One set is a subset of another when it contains all the
    // elements...

    int elements = setElements(set1);
    while (elements != nilValue) {
      int element = consHead(elements);
      if (setMember(element, set2))
        elements = consTail(elements);
      else return false;
    }
    return true;
  }

  public boolean equalStrings(int s1, int s2) {

    // Compare strings character by character...

    if (stringLength(s1) == stringLength(s2)) {
      int length = stringLength(s1);
      int index = 0;
      boolean same = true;
      while ((index < length) && same)
        if (stringRef(s1, index) != stringRef(s2, index))
          same = false;
        else index++;
      return same;
    } else return false;
  }

  public boolean equalValues(int v1, int v2) {

    // The = operator is implemented by this operation.
    // Two things are = when they are the same in memory except
    // for strings, sets, slots and immediates...

    if (isString(v1) && isString(v2)) return equalStrings(v1, v2);
    if (isSet(v1) && isSet(v2)) return equalSets(v1, v2);
    if (isFloat(v1) || isFloat(v2)) return equalNumbers(v1, v2);
    if (isBigInt(v1) || isBigInt(v2))
      return equalNumbers(v1, v2);
    else return v1 == v2;
  }

  public synchronized void perform() {

    // The entry point for starting a run of a single XMF thread.
    // The loop performs instructions until one of the following
    // situations arises:
    //
    // (1) The thread dies naturally (no more code to perform).
    // In this case XOS will unschedule the thread and its
    // monitors will be informed.
    //
    // (2) The thread yields. This means that XOS will schedule
    // any other threads that are READY.
    //
    // (3) An exception is thrown and is caught. XMF will try
    // to recover from an error by translating it into a
    // user-level exception.
    //
    // This Java method is synchronized since it must not be
    // called by two different Java threads (for example by the
    // client calling mechanism scheduling an XMF thread).

    yield = false;
    while (!terminatedThread() && !yield)
      try {
        performInstrs();
      } catch (ArrayIndexOutOfBoundsException boundsException) {

        // Probably a stack overflow...

        handleArrayException();
      } catch (MachineError machineError) {

        // If we receive a machine error then we create an
        // exception and throw it to the nearest handler...

        error(machineError.getError(), machineError.getMessage(), machineError.getData());
      } catch (Throwable throwable) {

        // If we receive an error we don't know about then deal as
        // best we can...
        handleThrow(throwable);
      }
  }

  public void handleArrayException() {

    // If we get an array index out of bounds then most
    // likely it has occurred due to a stack overflow...

    if (currentFrame >= 0) {
      valueStack.setTOS(currentFrame);
      currentFrame = prevFrame();
      error(ERROR, "Stack Overflow");
    }
  }

  public void handleThrow(Throwable throwable) {
    if (stackDump) {
      System.out.println("Java Stack Dump:");
      throwable.printStackTrace();
    }
    // printDynamics();
    saveBacktrace(currentFrame);
    System.err.println("Backtrace Saved.");
    error(INSTR, throwable.toString());
  }

  public void performInstrs() {

    // This method is the main workhorse of the VM. It assumes that a
    // thread has been installed and is ready to start. The thread is
    // then performed until it either terminates or decides to stop
    // (for example calls yield()).
    //
    // NB a number of instructions have been in-lined in this method
    // and the various calls have been expanded. In most cases the
    // original calls to instruction implementations have been left as
    // comments.

    // Registers...

    int instr;
    int tag;
    int R0;
    int R1;

    while (currentFrame != -1 && !yield) {

      // Get the next instruction. Perform any debugging and then
      // dispatch to an instruction routine based on the type tag
      // of the instruction.

      if (interrupt) interrupt();

      // Fetch an instruction and dispatch on the instruction tag.
      // The following single call has been macro-expanded to the
      // several lines of code that follow. Change back if ever
      // the format of stack frames changes; re-macro-expand if
      // necessary,
      //
      // int instr = fetchInstr();
      // ...
      // switch (tag(instr)) {

      R0 = valueStack.elements[currentFrame + FRAMECODEINDEX] & DATA;
      valueStack.elements[currentFrame + FRAMECODEINDEX] = (valueStack.elements[currentFrame + FRAMECODEINDEX] + 1) & DATA;
      instrsPerformed++;
      instr = words[(words[(valueStack.elements[currentFrame + FRAMECODEBOX] & PTR) + 2] & PTR) + R0 + 1];
      tag = (instr & BYTE4) >>> 24;

      switch (tag) {

      case MKCONS:
        // Push a cons pair...
        cons();
        break;
      case MKSEQ:
        // Pop elements and create a sequence...
        mkSeqFrom(value(instr));
        break;
      case MKSET:
        // Pop elements and create a set...
        mkSetFrom(value(instr));
        break;
      case PUSHINT:
        // Push a constant positive integer...
        valueStack.elements[valueStack.index++] = INT_MASK | (instr & DATA);
        break;
      case PUSHTRUE:
        // Push the value true...
        valueStack.elements[valueStack.index++] = trueValue;
        break;
      case PUSHFALSE:
        // Push the value false...
        valueStack.elements[valueStack.index++] = falseValue;
        break;
      case PUSHSTR:
        // Push a string found in the constants box...
        R0 = words[(valueStack.elements[currentFrame + FRAMECODEBOX] & DATA) + 1];
        valueStack.elements[valueStack.index++] = arrayRef(R0, instr & DATA);
        break;
      case RETURN:
        // Return from the current frame, passing back the value at
        // TOS...
        popFrame();
        break;
      case ADD:
        // Add various data structures...
        add();
        break;
      case SUB:
        // Subtract various data structures...
        sub();
        break;
      case MUL:
        // Multiply various data structures...
        mul();
        break;
      case DIV:
        // Divide various data structures...
        div();
        break;
      case GRE:
        // Numeric >.
        gre();
        break;
      case LESS:
        // < on various data structures...
        less();
        break;
      case EQL:
        // The same machine value...
        eql();
        break;
      case AND:
        // And various data structures...
        and();
        break;
      case IMPLIES:
        // Boolean implies...
        implies();
        break;
      case OR:
        // Or various data structures...
        or();
        break;
      case NOT:
        // Boolean not...
        not();
        break;
      case DOT:
        // Field reference. Object is at the TOS, name
        // is in frame constants...
        dot(frameConstant(value(instr)));
        break;
      case SELF:
        // The current target of the message in the
        // stack frame...
        valueStack.elements[valueStack.index++] = frameSelf();
        break;
      case SKPF:
        // Skip instructions if the TOS is false...
        // skpf(value(instr));
        R0 = valueStack.elements[--valueStack.index];
        if ((R0 >> 24) == BOOL) {
          if (R0 == falseValue) valueStack.elements[currentFrame + FRAMECODEINDEX] = (valueStack.elements[currentFrame + FRAMECODEINDEX] & DATA) + (instr & DATA);
        } else throw new MachineError(TYPE, "Machine.skpf: expecting a boolean", R0, theTypeBoolean);
        break;
      case SKP:
        // Skip froward a number of instructions...
        // skp(value(instr));
        valueStack.elements[currentFrame + FRAMECODEINDEX] += (instr & DATA);
        break;
      case DYNAMIC:
        // Push the value of a dynamic variable...
        dynamic(frameConstant(value(instr)));
        break;
      case MKFUN:
        // Make a function. The global variable values are the top n
        // stack values...
        if (needsGC()) gc();
        mkfun(value(instr));
        break;
      case MKFUNE:
        // Make a function. The global variable values are the top n
        // stack values...
        if (needsGC()) gc();
        mkfune(byte3(instr), byte2(instr));
        break;
      case LOCAL:
        // Refer to a local variable value in the current stack
        // frame.
        valueStack.elements[valueStack.index++] = valueStack.elements[currentFrame + FRAMELOCAL0 + (instr & DATA)];
        break;
      case STARTCALL:
        // Set up a stack frame. The fixed size part of the frame is
        // pushed ready for the argument values.
        if (needsGC()) gc();
        openFrame();
        break;
      case ENTER:
        // The current open frame is complete, make it current...
        enter(value(instr));
        break;
      case TAILENTER:
        // The arguments and function of the last call have been pushed.
        // Overwrite the current frame...
        tailEnter(value(instr));
        break;
      case SETLOC:
        // Set the value of a local variable in the current frame...
        setFrameLocal(value(instr), valueStack.top());
        break;
      case SEND:
        // Send the object at the top of the stack a message...
        send(popStack(), byte2(instr), frameConstant(byte3(instr) << 8 | byte1(instr)));
        break;
      case SENDSELF:
        // Send self a message...
        send(frameSelf(), byte2(instr), frameConstant(byte3(instr) << 8 | byte1(instr)));
        break;
      case SENDLOCAL:
        // Send a local a message...
        send(frameLocal(byte1(instr)), byte3(instr), frameConstant(byte2(instr)));
        break;
      case TAILSEND:
        // Send the object at the top of the stack a tail message...
        tailSend(byte2(instr), frameConstant(byte3(instr) << 8 | byte1(instr)));
        break;
      case SEND0:
        // Send the object at the top of the stack a message with 0
        // args...
        send0(frameConstant(value(instr)));
        break;
      case TAILSEND0:
        // Send the object at the top of the stack a tail message with 0
        // args...
        tailSend0(frameConstant(value(instr)));
        break;
      case POP:
        // Pop the TOS...
        valueStack.index--;
        break;
      case GLOBAL:
        // Push the value of a global variable in the current stack
        // frame...
        global(value(instr));
        break;
      case SETSLOT:
        // Set the value of the named attribute to be the TOS value...
        setSlot(frameConstant(value(instr)));
        break;
      case SETGLOB:
        // Set the value of the global to be the TOS value...
        setGlob(byte2(instr), byte1(instr));
        break;
      case MKARRAY:
        // Create a new array initialised from stack values...
        mkFixedArray(value(instr));
        break;
      case SUPER:
        // Continue the current lookup with possibly different args...
        sendSuper(value(instr));
        break;
      case TAILSUPER:
        // As for SUPER, but reuse the call frame...
        tailSuper(value(instr));
        break;
      case NAMESPACEREF:
        // Refer to a name in a containing namespace...
        nameSpaceRef(byte3(instr), frameConstant(byte1(instr) << 8 | byte2(instr)));
        break;
      case HEAD:
        // Push head of sequence...
        head();
        break;
      case TAIL:
        // Push tail of sequence...
        tail();
        break;
      case SIZE:
        // Push size of sequence...
        size();
        break;
      case DROP:
        // Drop elements from sequence...
        drop();
        break;
      case ISEMPTY:
        // Check whether sequence is empty...
        isEmpty();
        break;
      case INCLUDES:
        // Check whether a sequence includes an element...
        includes();
        break;
      case EXCLUDING:
        // Remove an element from a sequence...
        excluding();
        break;
      case INCLUDING:
        // Add an element to a collection...
        including();
        break;
      case SEL:
        // Select an element from a collection...
        sel();
        break;
      case UNION:
        // Form the union of two sets...
        union();
        break;
      case ASSEQ:
        // Transform a collection to a sequence...
        asSeq();
        break;
      case AT:
        // Index a sequence...
        at();
        break;
      case SKPBACK:
        // Jump back through the instruction stream.
        // Take into account that we have moved on by 1 instruction...
        // skpBack(value(instr) + 1);
        R0 = valueStack.elements[currentFrame + FRAMECODEINDEX] & DATA;
        valueStack.elements[currentFrame + FRAMECODEINDEX] = R0 - ((instr & DATA) + 1);
        break;
      case NULL:
        // The constant....
        valueStack.elements[valueStack.index++] = undefinedValue;
        break;
      case OF:
        // Pop an element, push its classifier...
        valueStack.push(type(valueStack.pop()));
        break;
      case THROW:
        // Pop an element and throw to nearest catch...
        throwIt();
        break;
      case TRY:
        // Pop handler, pop free vars, call code box...
        tryIt(byte2(instr), frameConstant(byte3(instr) << 8 | byte1(instr)));
        break;
      case ISKINDOF:
        // Test whether an element is of a given type...
        isKindOf();
        break;
      case SOURCEPOS:
        // Deprecated...
        break;
      case GETELEMENT:
        // Get a named element from a name space...
        getElement(frameConstant(value(instr)));
        break;
      case SETHEAD:
        // Set the head of a pair...
        setHead();
        break;
      case SETTAIL:
        // Set the tail of a pair...
        setTail();
        break;
      case READ:
        // Read from an input channel. Note that the read
        // instruction will cause the current thread to yield
        // if the input channel would block...
        read();
        break;
      case ACCEPT:
        // Accept a socket connection. Note that the accept
        // instruction will cause the current thread to yield
        // if the connection would block...
        accept();
        break;
      case ARRAYREF:
        // Index into an array...
        arrayRef();
        break;
      case ARRAYSET:
        // Update an array...
        arraySet();
        break;
      case TABLEGET:
        // Access a hash table...
        tableGet();
        break;
      case TABLEPUT:
        // Update a hash table...
        tablePut();
        break;
      case NOOP:
        // Do nothing...
        break;
      case SLEEP:
        // Put a thread to sleep...
        sleep();
        break;
      case CONST:
        // Push a constant onto the stack...
        pushStack(frameConstant(value(instr)));
        break;
      case SYMBOLVALUE:
        // Acces a symbol value...
        pushStack(symbolValue(frameConstant(value(instr))));
        break;
      case SETLOCPOP:
        // Set the value of a local variable in the current
        // frame and remove the value from the top of the stack.
        valueStack.elements[currentFrame + FRAMELOCAL0 + (instr & DATA)] = valueStack.elements[--valueStack.index];
        break;
      case DISPATCH:
        // Perform an indexed jump using a jump table from the
        // constants area. The top of the stack should be an
        // integer suitable as an index into the table...
        dispatch(frameConstant(value(instr)), valueStack.pop());
        break;
      case INCSELFSLOT:
        // Increment the named slot in the value of self...
        incSelfSlot(frameConstant(value(instr)));
        break;
      case DECSELFSLOT:
        // Decrement the named slot in the value of self...
        decSelfSlot(frameConstant(value(instr)));
        break;
      case INCLOCAL:
        // Increment the specified local by 1...
        // setFrameLocal(instr & DATA, mkInt(intValue(frameLocal(instr &
        // DATA)) + 1));
        R0 = valueStack.elements[currentFrame + FRAMELOCAL0 + (instr & DATA)];
        if ((R0 >> 24) == INT)
          R0 = R0 & DATA;
        else R0 = -(R0 & DATA);
        R0++;
        if (R0 < 0)
          R0 = NEGINT_MASK | -R0;
        else R0 = INT_MASK | R0;
        valueStack.elements[currentFrame + FRAMELOCAL0 + (instr & DATA)] = R0;
        valueStack.elements[valueStack.index++] = R0;
        break;
      case DECLOCAL:
        // Decrement the specified local by 1...
        setFrameLocal(value(instr), mkInt(intValue(frameLocal(value(instr))) - 1));
        pushStack(frameLocal(value(instr)));
        break;
      case ADDLOCAL:
        // Add to the value of a local...
        pushStack(mkInt(intValue(frameLocal(value(instr))) + 1));
        break;
      case SUBLOCAL:
        // Subtract 1 from the value of a local...
        pushStack(mkInt(intValue(frameLocal(value(instr))) - 1));
        break;
      case PREPEND:
        // Like CONS except TOS must be a sequence...
        prepend();
        break;
      case ENTERDYN:
        // The args are at the top of the stack. Close the call frame
        // and
        // enter the dynamic variable...
        enterDynamic(frameConstant(byte3(instr) << 8 | byte1(instr)), byte2(instr));
        break;
      case TAILENTERDYN:
        // The args are at the top of the stack. Close the call frame
        // and
        // enter the dynamic variable...
        tailEnterDynamic(frameConstant(byte3(instr) << 8 | byte1(instr)), byte2(instr));
        break;
      case ISNOTEMPTY:
        // Equivalent to not S->isEmpty...
        // isNotEmpty();
        R0 = valueStack.elements[--valueStack.index];
        R1 = (R0 >> 24);
        switch (R1) {
        case CONS:
        case NIL:
          valueStack.elements[valueStack.index++] = R0 == nilValue ? falseValue : trueValue;
          break;
        case SET:
          valueStack.elements[valueStack.index++] = setElements(R0) == nilValue ? falseValue : trueValue;
          break;
        case HASHTABLE:
          valueStack.push(hashTableIsEmpty(R0) ? falseValue : trueValue);
          break;
        case OBJ:
        default:
          valueStack.push(R0);
          send0(mkSymbol("isNotEmpty"));
          break;
        }
        break;
      case LOCALHEAD:
        // Refer to a local variable value in the current stack
        // frame and push head...
        R0 = valueStack.elements[currentFrame + FRAMELOCAL0 + (instr & DATA)];
        switch (R0 >> 24) {
        case NIL:
          throw new MachineError(TYPE, "Cannot take the head of Seq{}", nilValue, theTypeSeqOfElement);
        case CONS:
          valueStack.elements[valueStack.index++] = consHead(R0);
          break;
        default:
          valueStack.push(R0);
          send0(theSymbolHead);
        }
        break;
      case LOCALTAIL:
        // Refer to a local variable value in the current stack frame
        // and push tail...
        // pushTail(frameLocal(value(instr)));
        R0 = valueStack.elements[currentFrame + FRAMELOCAL0 + (instr & DATA)];
        switch (R0 >> 24) {
        case NIL:
          throw new MachineError(TYPE, "Cannot take the tail of Seq{}", nilValue, theTypeSeqOfElement);
        case CONS:
          valueStack.elements[valueStack.index++] = consTail(R0);
          break;
        default:
          valueStack.push(R0);
          send0(theSymbolTail);
        }
        break;
      case LOCALASSEQ:
        // Push the indexed local as a sequence...
        pushAsSeq(frameLocal(value(instr)));
        break;
      case LOCALISEMPTY:
        // Push whether the local is empty...
        // pushIsEmpty(frameLocal(value(instr)));
        R0 = valueStack.elements[currentFrame + FRAMELOCAL0 + (instr & DATA)];
        switch (R0 >> 24) {
        case CONS:
        case NIL:
          valueStack.elements[valueStack.index++] = (R0 == nilValue ? trueValue : falseValue);
          break;
        case SET:
          valueStack.elements[valueStack.index++] = (setElements(R0) == nilValue ? trueValue : falseValue);
          break;
        case HASHTABLE:
          valueStack.push(hashTableIsEmpty(R0) ? trueValue : falseValue);
          break;
        case OBJ:
        default:
          valueStack.push(R0);
          send0(theSymbolIsEmpty);
          break;
        }
        break;
      case DOTSELF:
        // Reference the slot via self...
        // dot(frameConstant(instr & DATA), frameSelf());
        dot(frameConstant(instr & DATA), valueStack.elements[currentFrame + FRAMESELF]);
        break;
      case DOTLOCAL:
        // Reference a slot of a local...
        dot(frameConstant(byte2(instr) << 8 | byte1(instr)), frameLocal(byte3(instr)));
        break;
      case SETLOCALSLOT:
        // Update the value of a slot in a local...
        setSlot(frameLocal(byte3(instr)), frameConstant(byte2(instr) << 8 | byte1(instr)), valueStack.pop());
        break;
      case SETSELFSLOT:
        // Update the value of a slot in self...
        setSlot(frameSelf(), frameConstant(value(instr)), valueStack.pop());
        break;
      case LOCALREFPOS:
        // References the local and records the line in the
        // original source code...
        valueStack.elements[valueStack.index++] = valueStack.elements[currentFrame + FRAMELOCAL0 + byte3(instr)];
        valueStack.elements[currentFrame + FRAMELINECOUNT] = INT_MASK | ((byte2(instr) << 8) | byte1(instr));
        valueStack.elements[currentFrame + FRAMECHARCOUNT] = INT_MASK;
        break;
      case DYNREFPOS:
        // References a dynamic and sets the line count...
        dynamic(frameConstant(value(byte3(instr))));
        valueStack.elements[currentFrame + FRAMELINECOUNT] = INT_MASK | ((byte2(instr) << 8) | byte1(instr));
        valueStack.elements[currentFrame + FRAMECHARCOUNT] = INT_MASK;
        break;
      case ASSOC:
        // Looks up a key in a sequence of pairs and returns the
        // sequence
        // from the position where the pair occurs...
        assoc();
        break;
      case RETDOTSELF:
        // Return the value of a slot via self...
        dot(frameConstant(instr & DATA), valueStack.elements[currentFrame + FRAMESELF]);
        popFrame();
        break;
      case TOSTRING:
        // Turn the value into a string...
        if (needsGC()) gc();
        toStringInstr();
        break;
      case ARITY:
        // Get the number of expected arguments...
        arity();
        break;
      case STRINGEQL:
        // A string equal to the first argument...
        stringEqual();
        break;
      case GET:
        // Tables or objects...
        get();
        break;
      case PUT:
        // Tables...
        put();
        break;
      case HASKEY:
        // Tables...
        hasKey();
        break;
      case LOCALNAME:
        // Set the name...
        localName(frameConstant(0), byte2(instr) << 8 | byte1(instr), byte3(instr));
        break;
      case UNSETLOCAL:
        // Name goes out of scope ...
        unsetLocal(value(instr));
        break;
      case LINE:
        // Set the line position in the current stack frame...
        line(value(instr));
        break;
      case HASSLOT:
        // Check whether an element has a slot...
        hasSlot();
        break;
      default:
        throw new MachineError(INSTR, "Machine.perform: unknown instruction " + tag(instr));
      }
    }

  }

  public void hasSlot() {

    // Implements the HASSLOT instruction...

    int name = popStack();
    int element = popStack();
    if (isString(name)) name = mkSymbol(name);
    switch (tag(element)) {
    case OBJ:
      objHasSlot(element, name);
      break;
    case FOREIGNOBJ:
      if (foreignObjHasSlot(element, name))
        pushStack(trueValue);
      else pushStack(falseValue);
      break;
    default:
      openFrame();
      valueStack.push(name);
      valueStack.push(element);
      send(1, mkSymbol("hasSlot"));
    }
  }

  public void objHasSlot(int obj, int slot) {

    // Check that the object implements the standard MOP
    // and return true or false. Otherwise send the type
    // of the object a hasInstanceSlot message...

    int type = type(obj);
    int meta = type(type);
    if (meta == theClassClass) {
      if (this.objAttribute(obj, slot) == -1)
        pushStack(falseValue);
      else pushStack(trueValue);
    } else {
      openFrame();
      valueStack.push(obj);
      valueStack.push(slot);
      valueStack.push(type);
      send(2, mkSymbol("hasInstanceSlot"));
    }
  }

  public void line(int line) {

    // Implements the LINE instruction...

    valueStack.elements[currentFrame + FRAMELINECOUNT] = INT_MASK | line;
    debugger.line(this, line);
  }

  public void cons() {

    // Implements the CONS instruction...

    if (needsGC()) gc();
    int tail = valueStack.pop();
    int head = valueStack.pop();
    valueStack.push(mkCons(head, tail));
  }

  public void mkSeqFrom(int length) {

    // Create a sequence an populate it from the values on
    // the stack...

    if (needsGC()) gc();
    int seq = nilValue;
    for (int i = 0; i < length; i++) {
      int element = valueStack.pop();
      seq = mkCons(element, seq);
    }
    valueStack.push(seq);
  }

  public void mkSetFrom(int length) {

    // Create a set an dpopulate it from the values on the stack...

    if (needsGC()) gc();
    int seq = nilValue;
    for (int i = 0; i < length; i++) {
      int element = valueStack.pop();
      seq = mkCons(element, seq);
    }
    valueStack.push(mkSet(seq));
  }

  public void add() {

    // Implements the ADD instruction...

    // Add can allocate fairly large strings. Check for garbage collection
    // before the values are popped from the stack...

    if (needsGC()) gc();

    int v2 = valueStack.pop();
    int v1 = valueStack.pop();

    // The instruction is overloaded for a variety of different
    // data structures. Ultimately it will bounce out into the
    // upper levels and call an add() operation...

    if (isInt(v1) && isInt(v2)) {
      // Add integers.
      valueStack.push(addInts(intValue(v1), intValue(v2)));
      return;
    }
    if (isFloat(v1) && isFloat(v2)) {
      valueStack.push(floatAdd(v1, v2));
      return;
    }
    if (isString(v1) && isString(v2)) {
      valueStack.push(stringAppend(v1, v2));
      return;
    }
    if (isSymbol(v1)) {
      valueStack.push(symbolName(v1));
      valueStack.push(v2);
      add();
      return;
    }
    if (isSymbol(v2)) {
      valueStack.push(v1);
      valueStack.push(symbolName(v2));
      add();
      return;
    }
    if (isBuffer(v1)) {
      arraySetValue(v1, size(v1), v2);
      return;
    }
    if (isString(v1) || isString(v2)) {
      // If either argument is a string then coerce both arguments to
      // strings and produce the concatenation of the strings.
      valueStack.push(mkString(valueToString(v1) + valueToString(v2)));
      return;
    }
    if (isSeq(v1) && isSeq(v2)) {
      valueStack.push(consAppend(v1, v2));
      return;
    }
    if (isSet(v1) && isSet(v2)) {
      valueStack.push(union(v1, v2));
      return;
    }
    if (isArray(v1) && isArray(v2)) {
      valueStack.push(addArrays(v1, v2));
      return;
    }
    if (isNum(v1) && isNum(v2)) {
      valueStack.push(asBigInt(ensureBigInt(v1).add(ensureBigInt(v2))));
      return;
    }
    if (isSymbol(v1)) {
      pushStack(symbolName(v1));
      pushStack(v2);
      add();
      return;
    }
    if (isSymbol(v2)) {
      pushStack(v1);
      pushStack(symbolName(v2));
      add();
      return;
    }
    overloadedBinOp(v1, v2, "add");
  }

  public int addInts(int i, int j) {
    // return mkInt(i + j);
    long l = i + j;
    if (l > MAXINT)
      return mkBigInt(l);
    else return mkInt(i + j);
  }

  public void overloadedBinOp(int v1, int v2, String op) {
    openFrame();
    valueStack.push(v2);
    valueStack.push(v1);
    send(1, mkSymbol(op));
  }

  public int consAppend(int l1, int l2) {
    int TOS = valueStack.getTOS();
    while (l1 != nilValue)
      if (isCons(l1)) {
        valueStack.push(consHead(l1));
        l1 = consTail(l1);
      } else throw new MachineError(TYPE, "consAppend expecting a proper sequence.", l1, theTypeSeqOfElement);
    while (valueStack.getTOS() != TOS)
      l2 = mkCons(valueStack.pop(), l2);
    return l2;
  }

  private int addArrays(int v1, int v2) {

    // Add two arrays and produce a new array...

    int l1 = arrayLength(v1);
    int l2 = arrayLength(v2);
    int newArray = mkArray(l1 + l2);
    for (int i = 0; i < l1; i++)
      arraySet(newArray, i, arrayRef(v1, i));
    for (int i = 0; i < l2; i++)
      arraySet(newArray, i + l1, arrayRef(v2, i));
    return newArray;
  }

  public void sub() {

    // Implements the SUB instruction...

    if (needsGC()) gc();
    int v1 = valueStack.pop();
    int v2 = valueStack.pop();
    if (isInt(v1) && isInt(v2))
      valueStack.push(mkInt(intValue(v2) - intValue(v1)));
    else if (isFloat(v1) && isFloat(v2))
      valueStack.push(floatSub(v2, v1));
    else if (isSet(v1) && isSet(v2))
      valueStack.push(setDifference(v2, v1));
    else if ((isSeq(v1) || isNil(v1)) && (isSeq(v2) || isNil(v2)))
      valueStack.push(seqDifference(v2, v1));
    else if (isNum(v1) && isNum(v2))
      valueStack.push(bigIntSub(v2, v1));
    else overloadedBinOp(v2, v1, "sub");
  }

  public void mul() {

    // Implements the MUL instruction...

    int v1 = valueStack.elements[--valueStack.index];
    int v2 = valueStack.elements[--valueStack.index];

    boolean v1IsInt = (v1 >> 24) == INT;
    boolean v1IsNegInt = (v1 >> 24) == NEGINT;
    boolean v2IsInt = (v2 >> 24) == INT;
    boolean v2IsNegInt = (v2 >> 24) == NEGINT;

    if ((v1IsInt || v1IsNegInt) && (v2IsInt || v2IsNegInt))
      valueStack.push(mulInts(v1IsNegInt ^ v2IsNegInt, value(v1), value(v2)));
    else if (isFloat(v1) && isFloat(v2))
      valueStack.push(floatMul(v2, v1));
    else if (isBigInt(v1) && isBigInt(v2))
      valueStack.push(asBigInt(asBigInteger(v1).multiply(asBigInteger(v2))));
    else if (isBigInt(v1) && (v2IsInt || v2IsNegInt))
      valueStack.push(asBigInt(asBigInteger(v1).multiply(asBigInteger(mkBigInt(intValue(v2))))));
    else if ((v1IsInt || v1IsNegInt) && isBigInt(v2))
      valueStack.push(asBigInt(asBigInteger(mkBigInt(intValue(v1))).multiply(asBigInteger(v2))));
    else overloadedBinOp(v2, v1, "mul");
  }

  public int mulInts(boolean isNegative, int i, int j) {
    long l = i * j;
    if (l > MAXINT)
      if (isNegative)
        return mkBigInt(-l);
      else return mkBigInt(l);
    else if (isNegative)
      return mkInt(-(int) l);
    else return mkInt((int) l);
    /*
     * if(isNegative) return mkInt(-(i*j)); else return mkInt(i*j);
     */
  }

  public void div() {

    // Implements the DIV instruction...

    int v1 = valueStack.pop();
    int v2 = valueStack.pop();
    if (isInt(v1) && isInt(v2))
      valueStack.push(intSlash(v2, v1));
    else if (isFloat(v1) && isFloat(v2))
      valueStack.push(floatDiv(v2, v1));
    else if (isBigInt(v1) || isBigInt(v2))
      valueStack.push(bigIntDiv(v2, v1));
    else overloadedBinOp(v2, v1, "slash");
  }

  public int intSlash(int i1, int i2) {
    float f1 = (float) intValue(i1);
    float f2 = (float) intValue(i2);
    float f3 = f1 / f2;
    return mkFloat("" + f3);
  }

  public int bigIntDiv(int v1, int v2) {
    float f1 = ensureBigInt(v1).floatValue();
    float f2 = ensureBigInt(v2).floatValue();
    return mkFloat(f1 / f2);
  }

  public void gre() {

    // Implements the GRE instruction...

    int v1 = valueStack.pop();
    int v2 = valueStack.pop();
    if (isInt(v1) && isInt(v2))
      if (intValue(v2) > intValue(v1))
        valueStack.push(trueValue);
      else valueStack.push(falseValue);
    else if (isFloat(v1) && isFloat(v2))
      if (floatGreater(v2, v1))
        valueStack.push(trueValue);
      else valueStack.push(falseValue);
    else if (isString(v1) && isString(v2))
      if (stringGreater(v2, v1))
        valueStack.push(trueValue);
      else valueStack.push(falseValue);
    else if (isBigInt(v1) || isBigInt(v2))
      if (ensureBigInt(v1).compareTo(ensureBigInt(v2)) > 0)
        valueStack.push(trueValue);
      else valueStack.push(falseValue);
    else overloadedBinOp(v2, v1, "greater");
  }

  public void less() {

    // Implements the LESS instruction...

    int v1 = valueStack.elements[--valueStack.index];
    int v2 = valueStack.elements[--valueStack.index];

    boolean v1IsInt = (v1 >> 24) == INT;
    boolean v1IsNegInt = (v1 >> 24) == NEGINT;
    boolean v2IsInt = (v2 >> 24) == INT;
    boolean v2IsNegInt = (v2 >> 24) == NEGINT;

    if ((v1IsInt || v1IsNegInt) && (v2IsInt || v2IsNegInt)) {
      int i1 = v1IsNegInt ? -(v1 & DATA) : (v1 & DATA);
      int i2 = v2IsNegInt ? -(v2 & DATA) : (v2 & DATA);
      valueStack.elements[valueStack.index++] = (i2 < i1) ? trueValue : falseValue;
    } else if (isFloat(v1) && isFloat(v2))
      if (floatLess(v2, v1))
        valueStack.push(trueValue);
      else valueStack.push(falseValue);
    else if (isString(v1) && isString(v2))
      if (stringLess(v2, v1))
        valueStack.push(trueValue);
      else valueStack.push(falseValue);
    else if (isBigInt(v1) || isBigInt(v2))
      if (ensureBigInt(v1).compareTo(ensureBigInt(v2)) < 0)
        valueStack.push(trueValue);
      else valueStack.push(falseValue);
    else overloadedBinOp(v2, v1, "less");
  }

  public void eql() {

    // Implements the EQ instruction...

    int v1 = valueStack.pop();
    int v2 = valueStack.pop();
    if (equalValues(v1, v2))
      valueStack.push(trueValue);
    else valueStack.push(falseValue);
  }

  public void and() {

    // Implements the AND instruction...

    int v1 = valueStack.pop();
    int v2 = valueStack.pop();
    if (isBool(v1) && isBool(v2))
      if ((v1 == trueValue) && (v2 == trueValue))
        valueStack.push(trueValue);
      else valueStack.push(falseValue);
    else if (isInt(v1) && isInt(v2))
      valueStack.push(mkInt(value(v1) & value(v2)));
    else overloadedBinOp(v1, v2, "booland");
  }

  public void implies() {

    // Implements the IMPLIES instruction...

    int v1 = valueStack.pop();
    int v2 = valueStack.pop();
    if (isBool(v1) && isBool(v2))
      valueStack.push(implies(v2, v1));
    else overloadedBinOp(v2, v1, "implies");
  }

  public int implies(int v1, int v2) {
    boolean b1 = v1 == trueValue;
    boolean b2 = v2 == trueValue;
    boolean implies = (!b1) || b2;
    return implies ? trueValue : falseValue;
  }

  public void or() {

    // Implements the OR instruction...

    int v1 = valueStack.pop();
    int v2 = valueStack.pop();
    if (isBool(v1) && isBool(v2))
      if ((v1 == trueValue) || (v2 == trueValue))
        valueStack.push(trueValue);
      else valueStack.push(falseValue);
    else if (isInt(v1) && isInt(v2))
      valueStack.push(mkInt(value(v1) | value(v2)));
    else overloadedBinOp(v1, v2, "boolor");
  }

  public void not() {

    // Implements the NOT instruction...

    int v = valueStack.pop();
    if (isBool(v))
      if (v == falseValue)
        valueStack.push(trueValue);
      else valueStack.push(falseValue);
    else throw new MachineError(TYPE, "Machine.not: operand types", v, theTypeBoolean);
  }

  public void dot(int name) {

    // Implements the DOT instruction...

    // The '.' operator accesses object fields. The object is at the head
    // of the stack. Call dot/2 since this is used elsewhere as a general
    // routine.

    int obj = valueStack.pop();
    dot(name, obj);
  }

  public void dot(int name, int obj) {

    // Field reference via '.' can occur to objects, collections and
    // non-objects. Field reference in objects is fairly straightforward.
    // Field reference in non-objects simulates fields where appropriate.
    // Field reference in collections causes the reference to occur to
    // all the elements of the collection and for the results to be
    // flattened (where appropriate) into a single collection a la standard
    // OCL...

    switch (obj >> 24) {
    case FOREIGNOBJ:
      dotForeignObj(name, obj);
      break;
    case OBJ:
      dotObj(name, obj);
      break;
    case SET:
    case CONS:
    case NIL:
      dotCollection(name, obj);
      break;
    case FUN:
      dotFun(name, obj);
      break;
    case FOREIGNFUN:
      dotForeignFun(name, obj);
      break;
    case SYMBOL:
      dotSymbol(name, obj);
      break;
    case HASHTABLE:
      dotCollection(name, hashTableContents(obj));
      break;
    case DAEMON:
      dotDaemon(name, obj);
      break;
    default:
      throw new MachineError(ERROR, "Dot: unknown type of value " + valueToString(obj) + "." + valueToString(name));
    }
  }

  public void dotCollection(int name, int collection) {

    // Don't handle '.' on collections in the machine so
    // just send the collection a dot message and let it handle
    // the iteration at the XOCL level...

    openFrame();
    valueStack.push(symbolName(name));
    valueStack.push(collection);
    send(1, theSymbolDot);
  }

  public void dotDaemon(int name, int daemon) {

    // Allow daemons to behave as objects...

    openFrame();
    valueStack.push(name);
    valueStack.push(daemon);
    send(1, mkSymbol("get"));
  }

  public void dotForeignFun(int name, int fun) {

    // Allow foreign funs to behave as objects...

    if (name == theSymbolName)
      valueStack.push(foreignFunName(fun));
    else if (name == mkSymbol("arity"))
      valueStack.push(mkInt(foreignFunArity(fun)));
    else {
      openFrame();
      valueStack.push(name);
      valueStack.push(fun);
      send(1, mkSymbol("get"));
    }
  }

  public void dotForeignObj(int name, int obj) {

    // Allow foreign objects to behave as objects.
    // A foreign object has an associated MOP that defines
    // how to access its slots...

    int index = foreignObjIndex(obj);
    ForeignObject f = foreignObjects.elementAt(index);
    f.getMop().dot(this, obj, name);
  }

  public void dotFun(int name, int fun) {

    // Allow a function to behave as an object...

    if (name == theSymbolName)
      valueStack.push(funName(fun));
    else if (name == theSymbolArity)
      valueStack.push(mkInt(funArity(fun)));
    else if (name == theSymbolOwner)
      valueStack.push(funOwner(fun));
    else if (name == theSymbolDocumentation)
      valueStack.push(funDocumentation(fun));
    else {
      openFrame();
      valueStack.push(name);
      valueStack.push(fun);
      send(1, mkSymbol("get"));
    }
  }

  public void dotObj(int name, int obj) {

    // Access a machine object field. If the object does not have a standard
    // slot access protocol then the class of the object is sent a message
    // that invokes an operation that implements the slot access protocol.
    // If the object has a standard slot access protocol then the machine
    // can handle the access directly...

    if (standardSlotAccessProtocol(obj) || isDefaultGetMOP(type(obj))) {
      int att = objAttribute(obj, name);
      if (att == -1)
        sendSlotMissing(obj, name);
      else valueStack.push(attributeValue(att));
    } else {
      sendSlotAccess(obj, name);
    }
  }

  public void sendSlotAccess(int obj, int name) {
    openFrame();
    valueStack.push(obj);
    valueStack.push(name);
    valueStack.push(type(obj));
    send(2, mkSymbol("getInstanceSlot"));
  }

  public void sendSlotMissing(int obj, int name) {
    openFrame();
    valueStack.push(name);
    valueStack.push(obj);
    send(1, mkSymbol("slotMissing"));
  }

  public void sendSlotMissing(int obj, int name, int value) {
    openFrame();
    valueStack.push(name);
    valueStack.push(value);
    valueStack.push(obj);
    send(2, mkSymbol("slotMissing"));
  }

  public boolean standardSlotAccessProtocol(int object) {
    int metaClass = type(type(object));
    return theClassClass == undefinedValue || metaClass == theClassClass || metaClass == theClassPackage;
  }

  public void dotSymbol(int name, int symbol) {

    // Allow symbols to behave as objects...

    if (name == theSymbolName)
      valueStack.push(symbolName(symbol));
    else throw new MachineError(MISSINGSLOT, "Dot.", symbolName(symbol), name);
  }

  public void dynamic(int name) {

    // The DYNAMIC instruction handles two cases:
    // o Unqualified slot reference in 'self'
    // o Reference to dynamic variables.

    int value = dynamicSlotReference(name);
    if (value == -1)
      dynamicReference(name);
    else valueStack.push(value);
  }

  public int dynamicSlotReference(int name) {
    int object = frameSelf();
    switch (tag(object)) {
    case OBJ:
      int attributes = objAttributes(object);
      while (attributes != nilValue) {
        int attribute = consHead(attributes);
        int attName = attributeName(attribute);
        if (name == attName)
          return attributeValue(attribute);
        else attributes = consTail(attributes);
      }
      return -1;
    case FUN:
      if (name == theSymbolName) return funName(object);
      if (name == theSymbolOwner) return funOwner(object);
      return -1;
    case FOREIGNFUN:
      if (name == theSymbolName) return foreignFunName(object);
      return -1;
    case FOREIGNOBJ:
      ForeignObject fobj = getForeignObject(object);
      if (fobj.getMop().hasSlot(this, object, name)) {
        fobj.getMop().dot(this, object, name);
        return valueStack.pop();
      } else return -1;
    default:
      return -1;
    }
  }

  public String elapsedTime() {
    return elapsedTime(time);
  }

  public String elapsedTime(long time) {

    // Calculate the time duration, as a string, since the last point
    // at which the time was reset.

    long elapsedTimeMillis = System.currentTimeMillis() - time;
    int mins = (int) (elapsedTimeMillis / (60 * 1000F));
    int secs = (int) ((elapsedTimeMillis - (mins * 60 * 1000F)) / 1000F);
    int millis = (int) (elapsedTimeMillis - ((mins * 60 * 1000F) + (secs * 1000F)));
    return mins + " minutes " + secs + " seconds " + millis + " milliseconds";
  }

  public void dynamicReference(int name) {
    int value = dynamicValue(name);
    if (value == -1)
      error(UNBOUNDVAR, "Unbound variable " + valueToString(name), name, mkInt(frameLineCount()), mkInt(0));
    else valueStack.push(value);
  }

  public void mkfun(int operands) {

    // Implements the MKFUn instruction.
    // The supplied operands from the
    // machine instruction encode the offsets to the
    // code box and the globals in the constants area.
    // The arity is also encoded in the operands. If
    // the constant offsets do not fit into the operands
    // then the assembler will have emitted a MKFUNE
    // instruction that uses the following words in
    // the code stream to place the offsets...

    int byte3 = byte3(operands);
    int arity = stripVarArgs(byte3);
    int codeBox = frameConstant(byte1(operands));
    int globals = linkGlobals(byte2(operands));
    int fun = mkFun();
    int doc = valueStack.pop();
    int data = valueStack.pop();
    int sig = take(data, arity + 1);
    int properties = drop(data, arity + 1);

    funSetArity(fun, mkInt(arity));
    funSetGlobals(fun, globals);
    funSetCode(fun, codeBox);
    funSetSelf(fun, frameSelf());
    funSetSupers(fun, mkCons(fun, consTail(frameSuper())));
    funSetDynamics(fun, frameDynamics());
    funSetOwner(fun, frameNameSpace());
    funSetProperties(fun, properties);
    funSetDocumentation(fun, doc);
    funSetSig(fun, sig);
    funSetIsVarArgs(fun, isVarArgs(byte3) ? trueValue : falseValue);
    funSetInstLevel(fun, mkInt(-1));
    funSetIsIntrinsic(fun, falseValue);
    valueStack.push(fun);
  }

  public static final int stripVarArgs(int arity) {

    // The arity operand in a MKFUN or MKFUNE instruction contains
    // a top bit that is set when the function takes var args.
    // The bit is stripped when the arity in a function is set.

    return arity & 0x7F;
  }

  public int linkGlobals(int globalsNum) {
    int globals = mkGlobals();
    int globalsArray = mkArray(globalsNum);
    for (int i = globalsNum - 1; i >= 0; i--)
      arraySet(globalsArray, i, valueStack.pop());
    globalsSetArray(globals, globalsArray);
    globalsSetPrev(globals, frameGlobals());
    return globals;
  }

  public int take(int seq, int n) {
    if (n <= 0)
      return nilValue;
    else return mkCons(consHead(seq), take(consTail(seq), n - 1));
  }

  public void mkfune(int byte3, int free) {

    // Extended version of MKFUN.

    int arity = stripVarArgs(byte3);
    int codeBox = frameConstant(value(popStack()));
    int globals = linkGlobals(free);
    int fun = mkFun();
    int doc = valueStack.pop();
    int data = valueStack.pop();
    int sig = take(data, arity + 1);
    int properties = drop(data, arity + 1);

    funSetArity(fun, mkInt(arity));
    funSetGlobals(fun, globals);
    funSetCode(fun, codeBox);
    funSetSelf(fun, frameSelf());
    funSetSupers(fun, mkCons(fun, frameSuper()));
    funSetDynamics(fun, frameDynamics());
    funSetOwner(fun, frameNameSpace());
    funSetProperties(fun, properties);
    funSetDocumentation(fun, doc);
    funSetSig(fun, sig);
    funSetIsVarArgs(fun, isVarArgs(byte3) ? trueValue : falseValue);
    valueStack.push(fun);
  }

  public static final boolean isVarArgs(int arity) {

    // The top bit of a functions arity byte is set if the
    // function can take multiple arguments.

    return (arity & 0x80) != 0;
  }

  public void openFrame() {

    // Implements the STARTCALL instruction...

    // Create a new open frame. An open frame is one that is under
    // construction either due to a function call (args must be
    // evaluated before closing the frame) or due to a field reference.

    int savedOpenFrame = openFrame;
    openFrame = valueStack.getTOS();
    valueStack.pushn(FRAMELOCAL0, undefinedValue);
    setOpenFramePrevFrame(currentFrame);
    setOpenFramePrevOpenFrame(savedOpenFrame);
    setOpenFrameCodeIndex(mkInt(0));
    setOpenFrameDynamics(frameDynamics());
  }

  public void enter(int arity) {

    // Implements the ENTER instruction...

    calls++;
    enterOperator(valueStack.pop(), arity);
  }

  public void enterClass(int c, int arity) {

    // When we enter a class we are instantiating it.
    // Create a new object, add slots for each of the
    // attributes initialised to the default
    // value of the attribute and then call 'init'.

    int obj = mkObj(c);
    boolean hasDynamicInits = addSlots(obj, c);
    int constructorArgs = classConstructorArgs(c, arity);
    if (hasDynamicInits || (constructorArgs == -1)) {
      int args = popArgs(arity);
      valueStack.push(args);
      // The argument to 'init'.
      valueStack.push(obj);
      // The target.
      send(1, hasDynamicInits ? theSymbolMachineInit : theSymbolInit);
    } else enterConstructor(obj, constructorArgs);
  }

  public int popArgs(int arity) {
    int args = nilValue;
    for (int i = 0; i < arity; i++)
      args = mkCons(valueStack.pop(), args);
    return args;
  }

  public boolean addSlots(int obj, int c) {

    // Calculate all the attributes defined and inherited by the
    // class and add slots to the object. The initial value for
    // each slot is the default value for the type of the attribute.
    // Returns true when any attribute specifies an initial value.
    // Initial values must be set by the kernel XMF code.

    int TOS = valueStack.getTOS();
    boolean hasDynamicInits = false;
    valueStack.push(c);
    while (valueStack.getTOS() != TOS) {
      int nextClass = valueStack.pop();
      int parents = asSeq(objAttValue(nextClass, theSymbolParents));
      while (parents != nilValue) {
        int parent = consHead(parents);
        valueStack.push(parent);
        parents = consTail(parents);
      }
      int attributes = asSeq(objAttValue(nextClass, theSymbolAttributes));
      while (attributes != nilValue) {
        int attribute = consHead(attributes);
        attributes = consTail(attributes);
        int name = objAttValue(attribute, theSymbolName);
        int type = objAttValue(attribute, theSymbolType);
        int init = objAttValue(attribute, theSymbolInit);
        int value = dynamicInitValue(init);
        int defaultValue = objAttValue(type, theSymbolDefault);
        boolean dynamicInit = isFun(init) && (value == -1);
        objAddAttribute(obj, name, (value == -1) ? defaultValue : value);
        hasDynamicInits = hasDynamicInits || dynamicInit;
      }
    }
    return hasDynamicInits;
  }

  public int dynamicInitValue(int init) {
    if (isFun(init))
      return funGetStringProperty(init, "value");
    else return -1;
  }

  public int classConstructorArgs(int c, int arity) {

    // Returns the constructor arg list for the given arity
    // or -1 if none defined...

    if (this.hashTableHasKey(constructorTable, c)) {
      if (arity == 0)
        return nilValue;
      else {
        int argLists = hashTableGet(constructorTable, c);
        while (argLists != nilValue)
          if (size(consHead(argLists)) == arity)
            return consHead(argLists);
          else argLists = consTail(argLists);
        return -1;
      }
    } else return -1;
  }

  public void enterConstructor(int obj, int slotNames) {
    closeFrame(0);
    int i = 0;
    while (slotNames != nilValue) {
      int arg = frameLocal(i++);
      int slot = consHead(slotNames);
      objSetAttValue(obj, slot, arg);
      slotNames = consTail(slotNames);
    }
    valueStack.push(obj);
    popFrame();
  }

  public void enterCont(int cont, int arity) {

    // Calling a continuation causes the continuation to call the
    // underlying operation and reinstate the saved stack therefore
    // returning to the creator of the continuation...

    if (arity == 1) {
      int value = valueStack.pop();
      copyContStack(cont);
      valueStack.push(value);
      currentFrame = value(contCurrentFrame(cont));
      openFrame = value(contOpenFrame(cont));
      popFrame();
    } else error(ARGCOUNT, "Continuations require exactly 1 argument.", cont, stackArgs(openFrame, arity));

  }

  public int stackArgs(int frame, int arity) {
    int args = nilValue;
    for (int i = arity - 1; i >= 0; i--)
      args = mkCons(valueStack.ref(frame + FRAMELOCAL0 + i), args);
    return args;
  }

  public int stackLocalValues(int frame, int values) {
    int locals = nilValue;
    for (int i = values - 1; i >= 0; i--)
      locals = mkCons(valueStack.ref(frame + FRAMELOCAL0 + i), locals);
    return locals;
  }

  public void enterForeignFun(int foreignFun, int arity) {

    // A foreign fun is essentially a Method. Prepare the
    // current frame for the method and then call it supplying
    // the VM to the method...

    ForeignFun fun = foreignFun(foreignFunIndex(foreignFun));
    if ((fun.arity() == -1) || (fun.arity() == arity))
      try {
        setOpenFrameLocals(arity); // Used for varArgs.
        setOpenFrameSelf(foreignFun);
        closeFrame(0);
        fun.invoke(this);
      } catch (Throwable x) {
        throw new MachineError(FOREIGNFUNERR, x.toString());
      }
    else throw new MachineError(ARGCOUNT, "Machine.enterForeignFun: arity mismatch ", foreignFun, nilValue);
  }

  // The value saver uses a tag in the first word of a sharable value
  // to be the index in the save engine when the value is first encountered.

  public void enterFun(int fun, int arity) {

    // Called to enter a function. The function may be
    // traced and may have var args. If it is traced then
    // the appropriate trace function is entered instead.
    // If the function has var args then the args at the
    // top of the stack must be adjusted....

    int funArity = funArity(fun);
    int traced = funTraced(fun);

    if (funIsVarArgs(fun) == trueValue) arity = adjustVarArgs(fun, arity);

    if (traced != undefinedValue)
      enterTracedFun(fun, funArity, funSelf(fun), nilValue);
    else if (arity == funArity)
      enterFun(fun, arity, funSelf(fun), funSupers(fun));
    else error(ARGCOUNT, "Operator arg mismatch.", fun, stackArgs(openFrame, arity));
  }

  public int adjustVarArgs(int fun, int arity) {

    // The function has var args. The last argument after the
    // (arity - 1)th argument is passed as the sequence of all
    // extra arguments. This method is called when fun has var
    // args. The extra args are popped from the stack, consed
    // into a sequence which is then pushed back on the stack.
    // Returns the new arity or -1 if there is an error.

    int funArity = funArity(fun);
    if (arity >= (funArity - 1)) {
      int args = nilValue;
      while (arity > (funArity - 1)) {
        args = mkCons(popStack(), args);
        arity = arity - 1;
      }
      pushStack(args);
      return funArity;
    } else return -1;
  }

  public void enterFun(int fun, int arity, int self, int supers) {

    // Once we get to this point everything must be set up to
    // correctly invoke the function. All arity checks must have
    // ocurred and any var args modifications taken place.

    int globals = funGlobals(fun);
    int codeBox = funCode(fun);
    int locals = codeBoxLocals(codeBox);
    int dynamics = funDynamics(fun);
    setOpenFrameGlobals(globals);
    setOpenFrameCodeBox(codeBox);
    setOpenFrameLocals((locals * 2) + arity);
    setOpenFrameSelf(self);

    // When operations are saved and loaded, the supers are not made
    // persistent. This is mainly because the overhead of saving and
    // loading the data is not worth the benefit gained. If a loaded
    // operation is invoked, it must have at least itself as the head
    // super operation.

    setOpenFrameSuper(supers == undefinedValue ? mkCons(fun, nilValue) : supers);
    setOpenFrameDynamics(dynamics);
    closeFrame(locals);
  }

  public void closeFrame(int locals) {

    // Close the current open frame by making it current...

    valueStack.pushn(locals * 2, undefinedValue);
    currentFrame = openFrame;
    openFrame = -1;
  }

  public void enterObj(int obj, int arity) {

    // If we try to apply an object o(args) then this is shorthand for
    // o.invoke(o,args). The following cases improve efficiency.

    // If we enter the class Table then create a table. We expect that the
    // initialisation arguments contain the table size.

    if (obj == theClassTable) {
      closeFrame(0);
      valueStack.push(mkHashtable(value(valueStack.pop())));
      popFrame();
      return;
    }

    // If we enter the class Vector then create an array of the given size.

    if (obj == theClassVector) {
      closeFrame(0);
      valueStack.push(mkArray(value(valueStack.pop())));
      popFrame();
      return;
    }

    // If we enter the class Symbol then create a symbol. The
    // initialisation
    // arguments must be the name of the symbol.

    if (obj == theClassSymbol) {
      closeFrame(0);
      valueStack.push(mkSymbol(valueStack.pop()));
      popFrame();
      return;
    }

    // If we enter the type Float then we expect two integers as the
    // initialisation arguments. The first is the float part before the
    // decimal point and the second is that after the decimal point.

    if (obj == theTypeFloat) {
      closeFrame(0);
      int args = popArgs(2);
      int prePoint = at(args, 0);
      int postPoint = at(args, 1);
      valueStack.push(mkFloat(valueToString(prePoint) + "." + valueToString(postPoint)));
      popFrame();
      return;
    }

    // If we enter a class then we create an instance of the class
    // and then initialise it.

    int type = objType(obj);

    if (type == theClassClass || type == theClassPackage) {
      enterClass(obj, arity);
      return;
    }

    // If we reach here then we don't have a specialised handler for the
    // the application of the object so just call its 'invoke' operation
    // and let the object handle the rest.

    invokeObj(obj, obj, arity);
  }

  public void invokeObj(int obj, int target, int arity) {

    // We are not sure that we are doing. Hopefully the object implements
    // an appropriate invoke operation that will handle the application...

    int args = popArgs(arity);
    valueStack.push(target);
    // The first argument to 'invoke'.
    valueStack.push(args);
    // The second argument to 'invoke'.
    valueStack.push(obj);
    // The target.
    send(2, theSymbolInvoke);
  }

  public void enterOperator(int fun, int arity) {

    // The arguments have just been pushed and the frame is
    // yet to be closed and started. Decide what type of
    // element we are applying to the arguments and dispatch
    // to an appropriate handler...

    switch (tag(fun)) {
    case FUN:
      enterFun(fun, arity);
      break;
    case FOREIGNFUN:
      enterForeignFun(fun, arity);
      break;
    case OBJ:
      enterObj(fun, arity);
      break;
    case CONT:
      enterCont(fun, arity);
      break;
    case FOREIGNOBJ:
      invokeObj(fun, fun, arity);
      break;
    default:
      error(ERROR, "Trying to apply a non-applicable value: " + valueToString(fun));
    }
  }

  public void enterTracedFun(int fun, int arity, int target, int supers) {

    // On calling enter traced fun we have an open frame and have
    // discovered that the function we are about to call is traced.
    // We need to extract the arguments from the currently open
    // frame and discard it. Then call 'traceFun' to construct a
    // new frame that will call the appropriate tracing function.

    closeFrame(0);
    int args = nilValue;
    for (int i = arity - 1; i >= 0; i--)
      args = mkCons(frameLocal(i), args);
    valueStack.push(undefinedValue);
    popFrame();
    valueStack.pop();
    traceFun(fun, target, args, supers);
  }

  public void tailEnter(int arity) {

    // Implements the TAILENTER instruction...

    openFrame = currentFrame;
    setOpenFrameCodeIndex(0);
    int fun = valueStack.pop();
    int argIndex = valueStack.getTOS() - arity;
    valueStack.setTOS(openFrame + FRAMELOCAL0);
    for (int i = 0; i < arity; i++) {
      int value = valueStack.ref(argIndex++);
      pushStack(value);
    }
    valueStack.push(fun);
    enter(arity);
  }

  public void popFrame() {

    // The current frame has completed. The top value on the stack
    // is the return value. The machine registers are restored from
    // the saved previous values in the current frame which is then
    // discarded...

    int returnValue = valueStack.pop();
    valueStack.setTOS(currentFrame);
    openFrame = prevOpenFrame();
    currentFrame = prevFrame();
    valueStack.push(returnValue);
  }

  public void setFrameLocal(int index, int value) {

    // Implements the SETFRAMELOCAL instruction...

    valueStack.set(currentFrame + FRAMELOCAL0 + index, value);
  }

  public void send(int target, int arity, int message) {

    // Implements the SEND instruction...

    if (isForeignObj(target))
      sendForeignObj(target, arity, message);
    else {

      // Expects a target on the top of the stack above the arguments.
      // If the target does not have a standard message sending protocol
      // then the classifier of the target is sent a message
      // 'sendInstance/3';
      // otherwise the machine can send the message directly.
      // Calculate the list of operations headed by the most specific
      // operation with the given name and arity. If no message is
      // found then send the target a noOperationFound message. Otherwise
      // invoke the operation.

      // Determine whether or not we have a standard message protocol...

      int classifier = type(target);
      int metaType = type(classifier);

      boolean standardMessageProtocol = theClassClass != undefinedValue && (metaType == theClassClass || metaType == theClassPackage || metaType == theClassDataType || metaType == theTypeSeq || metaType == theTypeSet);

      // If not standard then send a message to the class to deliver
      // the message...

      if (!standardMessageProtocol && !isDefaultSendMOP(type(target)))
        sendInstance(target, message, arity);
      else {

        // Otherwise perform the standard protocol:
        // find an operation with the right name and
        // arity and call it...

        int ops = newFindOperation(classifier, message, arity);

        if (ops == nilValue)
          noOperationFound(target, arity, message);
        else {
          int op = consHead(ops);
          if (isFun(op)) {
            if (funIsVarArgs(op) == trueValue) adjustVarArgs(op, arity);
            if (funTraced(op) != undefinedValue)
              enterTracedFun(op, funArity(op), target, ops);
            else enterFun(op, arity, target, ops);
          } else invokeObj(op, target, arity);
        }
      }
    }
  }

  public void noOperationFound(int target, int arity, int message) {
    int args = nilValue;
    for (int i = 0; i < arity; i++)
      args = mkCons(valueStack.pop(), args);
    valueStack.push(message);
    valueStack.push(args);
    valueStack.push(target);
    send(2, mkSymbol("noOperationFound"));
  }

  public void sendInstance(int target, int message, int arity) {

    // The target is an instance of a classifier that is itself
    // NOT an instance of one of the key machine meta-classes.
    // This means that the class of the target *MAY* have redefined
    // the operation that delivers messages. Reconstruct the
    // stack frame to send the message to the class of the target.

    closeFrame(0);
    int args = nilValue;
    for (int i = arity - 1; i >= 0; i--)
      args = mkCons(frameLocal(i), args);

    valueStack.push(undefinedValue);
    popFrame();
    valueStack.pop();
    openFrame();
    valueStack.push(target);
    valueStack.push(message);
    valueStack.push(args);
    valueStack.push(type(target));
    send(3, mkSymbol("sendInstance"));
  }

  public void send(int arity, int message) {
    send(popStack(), arity, message);
  }

  public void sendLocal(int arity, int message, int local) {
    send(frameLocal(local), arity, message);
  }

  public void sendSelf(int arity, int message) {
    send(frameSelf(), arity, message);
  }

  public int newFindOperation(int classifier, int message, int arity) {
    int opsList = newGetOpsList(classifier, message, arity);
    if (opsList == -1)
      return newCacheOpsList(classifier, message, arity);
    else return opsList;
  }

  public int newGetOpsList(int classifier, int message, int arity) {
    int opsTable = newGetOpsTable(classifier);
    int opsList = hashTableGet(opsTable, message);
    if (opsList == -1)
      return -1;
    else {
      int op = consHead(opsList);
      return value(funArity(op)) == arity ? opsList : -1;
    }
  }

  public int newCacheOpsList(int classifier, int message, int arity) {
    int opsTable = newGetOpsTable(classifier);
    int allOps = hashTableGet(opsTable, classifier);
    if (allOps == -1) {
      int TOS = valueStack.getTOS();
      int classifierTOS = pushClassifiers(classifier);
      allOps = constructOperators(classifierTOS, TOS);
      while (valueStack.getTOS() != TOS)
        valueStack.pop();
      hashTablePut(opsTable, classifier, allOps);
    }
    int opsList = findOperation(allOps, message, arity);
    hashTablePut(opsTable, message, opsList);
    return opsList;
  }

  public int newGetOpsTable(int classifier) {
    int opsTable = hashTableGet(operatorTable, classifier);
    if (opsTable == -1) {
      opsTable = mkHashtable(20);
      hashTablePut(operatorTable, classifier, opsTable);
    }
    return opsTable;
  }

  public void sendForeignObj(int foreignObj, int arity, int message) {

    // The foreign object is to be sent a message. How this occurs
    // will depend on the MOP of the foreign object's class. The
    // MOPs are associated with the objects in the foreign object
    // table...

    int index = foreignObjIndex(foreignObj);
    ForeignObject f = foreignObjects.elementAt(index);
    // Get the args and tidy up the currently open stack frame...
    int args = nilValue;
    closeFrame(0);
    for (int i = arity - 1; i >= 0; i--)
      args = mkCons(frameLocal(i), args);
    valueStack.push(undefinedValue);
    popFrame();
    valueStack.pop();
    f.getMop().send(this, foreignObj, message, args);

  }

  public void tailSend(int arity, int message) {

    // Implements the TAILSEND instruction...

    int target = popStack();
    openFrame = currentFrame;
    setOpenFrameCodeIndex(0);
    int argIndex = valueStack.getTOS() - arity;
    valueStack.setTOS(openFrame + FRAMELOCAL0);
    for (int i = 0; i < arity; i++) {
      int value = valueStack.ref(argIndex++);
      pushStack(value);
    }
    valueStack.push(target);
    send(arity, message);
  }

  public void send0(int message) {

    // Implements the SEND0 instruction...

    // Expects a target at the top of the stack. There are no arguments
    // and no stack frame is currently open. opens stack frame and
    // calls operation.

    int target = popStack();
    if (isForeignObj(target)) {
      openFrame();
      sendForeignObj(target, 0, message);
    } else {
      openFrame();

      int ops = getOperations(target, message, 0);
      if (ops == nilValue)
        noOperationFound(target, 0, message);
      else {
        int op = consHead(ops);
        if (isFun(op)) {
          if (funIsVarArgs(op) == trueValue) adjustVarArgs(op, 0);
          if (funTraced(op) != undefinedValue)
            enterTracedFun(op, 0, target, ops);
          else enterFun(op, 0, target, ops);
        } else invokeObj(op, target, 0);
      }
    }
  }

  public int getOperations(int target, int message, int arity) {

    // Get the operator precedence list for the target. Discard the
    // operations at the head of the list that do not have the correct
    // name and arity. Return the resulting list...

    int ops = operatorPrecedenceList(type(target));
    return findOperation(ops, message, arity);
  }

  public int findOperation(int ops, int message, int arity) {
    boolean found = false;
    while (!found && ops != nilValue) {
      int op = consHead(ops);
      int funArity = funArity(op);
      boolean isVarArgs = funIsVarArgs(op) == trueValue;
      boolean arityMatch = (arity == funArity) || (isVarArgs && (arity >= (funArity - 1)));

      switch (tag(op)) {
      case FUN:
        if (funName(op) == message && arityMatch)
          found = true;
        else ops = consTail(ops);
        break;
      case OBJ:
        if (objGetName(op) == message)
          found = true;
        else ops = consTail(ops);
        break;
      default:
        ops = consTail(ops);
      }
    }
    return ops;
  }

  public int operatorPrecedenceList(int classifier) {

    // Calculate the sequence of operators defined for the given classifier.
    // To do this we traverse the inheritance lattice depth first, left to
    // right up to a join. This means that we do not add operators twice
    // into the precedence list; such operators occur in the list at the
    // last possible position.
    // In addition we try to cons a little as possible when constructing
    // this list and cache it once it has been constructed. When new
    // operators are added, the cache is reset...

    int opsTable = newGetOpsTable(classifier);
    int operators = hashTableGet(opsTable, classifier);
    if (operators == -1) {
      operators = calculateOperatorPrecedenceList(classifier);
      hashTablePut(opsTable, classifier, operators);
    }
    return operators;
  }

  public int calculateOperatorPrecedenceList(int classifier) {
    int TOS = valueStack.getTOS();
    int classifierTOS = pushClassifiers(classifier);
    int operators = constructOperators(classifierTOS, TOS);
    while (valueStack.getTOS() != TOS)
      valueStack.pop();
    return operators;
  }

  public int constructOperators(int leastSpecificClassifier, int mostSpecificClassifier) {
    for (int i = mostSpecificClassifier; i < leastSpecificClassifier; i++) {
      int classifier = valueStack.ref(i);
      if (!classifierInRange(classifier, i + 1, leastSpecificClassifier)) pushOperators(classifier);
    }
    return popOperators(leastSpecificClassifier);
  }

  public int popOperators(int lastOperator) {
    int operations = nilValue;
    while (valueStack.getTOS() != lastOperator)
      operations = mkCons(valueStack.pop(), operations);
    return operations;
  }

  public boolean classifierInRange(int classifier, int start, int end) {
    for (int i = start; i < end; i++)
      if (valueStack.ref(i) == classifier) return true;
    return false;
  }

  public int pushClassifiers(int classifier) {
    valueStack.push(classifier);
    int parents = setElements(objAttValue(classifier, theSymbolParents));
    while (parents != nilValue) {
      int parent = consHead(parents);
      pushClassifiers(parent);
      parents = consTail(parents);
    }
    return valueStack.getTOS();
  }

  public void tailSend0(int message) {

    // Implements the TAILSEND0 instuction...

    tailSend(0, message);
  }

  public void global(int operands) {

    // Implement the GLOBAL instruction. Two indices define how far
    // up the current globals chain to index and then the index of the
    // variable location in the resulting array...

    int frame1 = byte2(operands);
    int frame2 = byte3(operands);
    int index = byte1(operands);
    valueStack.push(frameGlobal((frame2 << 8) | frame1, index));
  }

  public void setSlot(int name) {

    // Implements the SETSLOT instruction...
    // Various things can have their state set using ':='.
    // Dispatch to an appropriate handler...

    int obj = valueStack.pop();
    int value = valueStack.pop();
    setSlot(obj, name, value);
  }

  public void setSlot(int obj, int name, int value) {

    // Decide what to do depending on the type of obj...

    switch (tag(obj)) {

    case OBJ:

      // If the object has a standard slot access protocol then just
      // update the slot and fire any daemons. Otherwise there may be
      // a specialized slot update protocol defined via 'setInstanceSlot'
      // in the class of the object.

      if (standardSlotAccessProtocol(obj) || isDefaultSetMOP(type(obj)))
        setObjSlot(obj, name, value);
      else sendSlotUpdate(obj, name, value);
      break;

    case FUN:
      setFunSlot(obj, name, value);
      break;

    case DAEMON:
      setDaemonSlot(obj, name, value);
      break;

    case FOREIGNOBJ:
      setForeignObjectSlot(obj, name, value);
      break;

    default:
      throw new MachineError(TYPE, "Machine.setSlot: Don't know how to set the " + valueToString(name) + " slot of " + valueToString(obj) + " to " + valueToString(value), obj);
    }
  }

  public void setForeignObjectSlot(int obj, int name, int value) {

    // Allow foreign objects to behave as objects.
    // A foreign object has an associated MOP that defines
    // how to access its slots...

    int index = foreignObjIndex(obj);
    ForeignObject f = foreignObjects.elementAt(index);
    f.getMop().set(this, obj, name, value);
  }

  public void sendSlotUpdate(int obj, int name, int value) {

    // The object is an instance of a class whose meta-class is not
    // one of the standard kernel meta-classes. Call the appropriate
    // MOP operation to update the slot...

    openFrame();
    valueStack.push(obj);
    valueStack.push(name);
    valueStack.push(value);
    valueStack.push(type(obj));
    send(3, mkSymbol("setInstanceSlot"));
  }

  public void setFunSlot(int fun, int name, int value) {
    if (name == theSymbolOwner) {
      funSetOwner(fun, value);
      valueStack.push(fun);
      return;
    }
    if (name == theSymbolDocumentation) {
      funSetDocumentation(fun, value);
      valueStack.push(fun);
      return;
    }
    openFrame();
    valueStack.push(name);
    valueStack.push(value);
    valueStack.push(fun);
    send(2, mkSymbol("set"));
  }

  public void setObjSlot(int obj, int name, int value) {

    // Setting the slot of an object should update the object and
    // then fire all the daemons registered with the object. To fire
    // the daemons on an object, sendthe object a message
    // 'fire(name,oldValue,newValue)'.

    if ((objDaemonsActive(obj) == trueValue) && (objDaemons(obj) != nilValue)) {
      int oldValue = objAttValue(obj, name);
      if (objSetAttValue(obj, name, value) == -1) sendSlotMissing(obj, name, value);
      openFrame();
      valueStack.push(name);
      valueStack.push(value);
      valueStack.push(oldValue);
      valueStack.push(obj);
      send(3, theSymbolFire);
    } else {
      if (objSetAttValue(obj, name, value) == -1) sendSlotMissing(obj, name, value);
      valueStack.push(obj);
    }
  }

  public void setDaemonSlot(int daemon, int name, int value) {
    openFrame();
    valueStack.push(name);
    valueStack.push(value);
    valueStack.push(daemon);
    send(2, mkSymbol("set"));
  }

  public void setGlob(int frame, int index) {

    // Implements the SETGLOB instruction...
    // Set the value of a closure variable in the current frame...

    int value = valueStack.top();
    setFrameGlobal(frame, index, value);
  }

  public void setFrameGlobal(int frame, int index, int value) {

    // Set a global variable...

    int globals = frameGlobals();

    // Chain back up to the appropriate globals vector...

    for (int i = 0; i < frame; i++)
      globals = globalsPrev(globals);

    // Update the variable in the vector...

    arraySet(globalsArray(globals), index, value);
  }

  public void mkFixedArray(int length) {

    // Makes an array and populates it from the values at the
    // head of the stack...

    int array = mkArray(length);
    for (int i = (length - 1); i >= 0; i--)
      arraySet(array, i, valueStack.pop());
    valueStack.push(array);
  }

  public void sendSuper(int arity) {

    // Implements the SENDSUPER instruction...
    // Continue the search for an operation from the supers list
    // in the current frame...

    int target = frameSelf();
    int ops = frameSuper();
    int op = consHead(ops);
    int message = funName(op);
    ops = findOperation(consTail(ops), message, arity);
    if (ops == nilValue)
      noOperationFound(target, arity, message);
    else {
      op = consHead(ops);
      if (funIsVarArgs(op) == trueValue) adjustVarArgs(op, arity);
      if (funTraced(op) != undefinedValue)
        enterTracedFun(op, arity, target, ops);
      else enterFun(op, arity, target, ops);
    }
  }

  public void tailSuper(int arity) {

    // Implements the TAILSUPER instruction...

    openFrame = currentFrame;
    setOpenFrameCodeIndex(0);
    int argIndex = valueStack.getTOS() - arity;
    valueStack.setTOS(openFrame + FRAMELOCAL0);
    for (int i = 0; i < arity; i++) {
      int value = valueStack.ref(argIndex++);
      pushStack(value);
    }
    sendSuper(arity);
  }

  public void nameSpaceRef(int contour, int name) {

    // Implements the NAMESPACEREF instruction...

    nameSpaceRef(frameNameSpace(), contour, name);
  }

  public void nameSpaceRef(int nameSpace, int contour, int name) {

    // Chain back up the owners slot until the required contour is
    // found and then find the named element in the resulting
    // name-space...

    if (contour == 0)
      refNameSpace(nameSpace, name);
    else if (topLevelNameSpace(nameSpace) && contour == 1)
      refTopLevelNameSpace(nameSpace, name);
    else nameSpaceRef(objAttValue(nameSpace, theSymbolOwner), contour - 1, name);
  }

  public void refNameSpace(int nameSpace, int name) {
    int contents = objAttValue(nameSpace, theSymbolContents);
    if (!hashTableHasKey(contents, name)) {
      String nameSpaceName = valueToString(objAttValue(nameSpace, theSymbolName));
      throw new MachineError(NAMESPACEERR, "Machine.nameSpaceRef: Cannot find element " + valueToString(name) + " in namespace " + nameSpaceName);
    } else valueStack.push(hashTableGet(contents, name));
  }

  public int refNameSpace(int nameSpace, StringBuffer name) {
    int contents = objAttValue(nameSpace, theSymbolContents);
    for (int i = 0; i < arrayLength(contents); i++) {
      int bucket = arrayRef(contents, i);
      while (bucket != nilValue) {
        int cell = consHead(bucket);
        int symbol = consHead(cell);
        if (isSymbol(symbol)) {
          int string = symbolName(symbol);
          if (stringEqual(string, name)) return consTail(cell);
        }
        bucket = consTail(bucket);
      }
    }
    return -1;
  }

  public void refTopLevelNameSpace(int nameSpace, int name) {
    int nameSpaceName = objAttValue(nameSpace, theSymbolName);
    if (equalValues(nameSpaceName, name))
      valueStack.push(nameSpace);
    else throw new MachineError(NAMESPACEERR, "Machine.nameSpaceRef: Top level name space " + valueToString(nameSpaceName) + " not called " + valueToString(name));
  }

  public void head() {

    // Implements the HEAD instruction...

    pushHead(valueStack.pop());
  }

  public void pushHead(int cons) {

    // Push the head of the cons. Raise an exception of the sequence
    // is empty otherwise send a head message and hope for the best...

    if (cons == nilValue) throw new MachineError(TYPE, "Cannot take the head of Seq{}", nilValue, theTypeSeqOfElement);
    if (isCons(cons))
      valueStack.push(consHead(cons));
    else {
      valueStack.push(cons);
      send0(theSymbolHead);
    }
  }

  public void tail() {

    // Implements the TAIL instruction...

    pushTail(valueStack.pop());
  }

  public void pushTail(int cons) {

    // Push the tail of a cons. Raise an exception for the
    // empty sequence otherwise send a tail message and hope for
    // the best...

    if (cons == nilValue) throw new MachineError(TYPE, "Cannot take the tail of Seq{}", nilValue, theTypeSeqOfElement);

    if (isCons(cons))
      valueStack.push(consTail(cons));
    else {
      valueStack.push(cons);
      send0(theSymbolTail);
    }
  }

  public void size() {

    // Implements the SIZE instruction...

    int collection = valueStack.pop();
    valueStack.push(mkInt(size(collection)));
  }

  public int size(int collection) {
    switch (tag(collection)) {
    case BUFFER:
      return value(bufferSize(collection));
    case CONS:
      return consLength(collection);
    case NIL:
      return 0;
    case SET:
      return consLength(setElements(collection));
    case STRING:
      return stringLength(collection);
    case SYMBOL:
      return stringLength(symbolName(collection));
    case ARRAY:
      return arrayLength(collection);
    case HASHTABLE:
      return arrayLength(collection);
    default:
      throw new MachineError(TYPE, "Size: expecting a collection.", collection);
    }
  }

  public void drop() {

    // Implements the DROP instruction...

    if (needsGC()) gc();
    int collection = valueStack.pop();
    int length = valueStack.pop();
    valueStack.push(drop(collection, value(length)));
  }

  public int drop(int seq, int n) {
    while (n > 0 && seq != nilValue) {
      seq = consTail(seq);
      n--;
    }
    return seq;
  }

  public void isEmpty() {

    // Implements the ISEMPTY instruction...

    pushIsEmpty(valueStack.pop());
  }

  public void pushIsEmpty(int collection) {
    switch (tag(collection)) {
    case CONS:
    case NIL:
      valueStack.push(collection == nilValue ? trueValue : falseValue);
      break;
    case SET:
      valueStack.push(setElements(collection) == nilValue ? trueValue : falseValue);
      break;
    case OBJ:
      valueStack.push(collection);
      send0(theSymbolIsEmpty);
      break;
    case HASHTABLE:
      valueStack.push(hashTableIsEmpty(collection) ? trueValue : falseValue);
      break;
    default:
      openFrame();
      valueStack.push(collection);
      send(0, mkSymbol("isEmpty"));
    }
  }

  public void includes() {

    // Implements the INCLUDES instruction...

    int collection = valueStack.pop();
    int element = valueStack.pop();
    valueStack.push(includes(collection, element) ? trueValue : falseValue);
  }

  public boolean includes(int collection, int element) {
    switch (tag(collection)) {
    case SET:
      return seqIncludes(setElements(collection), element);
    case CONS:
      return seqIncludes(collection, element);
    case NIL:
      return false;
    case STRING:
      return stringIncludes(collection, value(element));
    default:
      throw new MachineError(TYPE, "includes: expecting a collection", collection);
    }
  }

  public void excluding() {

    // Implements the EXCLUDING instruction...

    if (needsGC()) gc();
    int collection = valueStack.pop();
    int element = valueStack.pop();
    switch (tag(collection)) {
    case SET:
      valueStack.push(setExcluding(collection, element));
      break;
    case CONS:
    case NIL:
      valueStack.push(seqExcluding(collection, element));
      break;
    default:
      openFrame();
      valueStack.push(element);
      valueStack.push(collection);
      send(1, mkSymbol("excluding"));
    }
  }

  public int excluding(int collection, int value) {
    switch (tag(collection)) {
    case SET:
      return setExcluding(collection, value);
    case CONS:
    case NIL:
      return seqExcluding(collection, value);
    default:
      throw new MachineError(ERROR, "Excluding: " + valueToString(collection));
    }
  }

  public void including() {

    // Implements the INCLUDING instruction...

    if (needsGC()) gc();
    int collection = valueStack.pop();
    int element = valueStack.pop();
    valueStack.push(including(collection, element));
  }

  public int including(int collection, int element) {
    switch (tag(collection)) {
    case SET:
      return setIncluding(collection, element);
    case CONS:
    case NIL:
      return consAppend(collection, mkCons(element, nilValue));
    default:
      throw new MachineError(TYPE, "including expecting a collection: ", collection);
    }
  }

  public int setIncluding(int set, int element) {
    if (setMember(element, set))
      return set;
    else {
      int seq = setElements(set);
      return mkSet(mkCons(element, seq));
    }
  }

  public void sel() {

    // Implements the SEL instruction...

    int collection = valueStack.pop();
    sel(collection);
  }

  public void sel(int collection) {
    if (isSet(collection) || isSeq(collection)) {
      int seq = asSeq(collection);
      if (seq == nilValue)
        throw new MachineError(TYPE, "sel: empty collection.", nilValue);
      else valueStack.push(consHead(seq));
    } else {
      openFrame();
      valueStack.push(collection);
      send(0, mkSymbol("sel"));
    }
  }

  public void union() {

    // Implements the UNION instruction...

    if (needsGC()) gc();
    int set1 = valueStack.pop();
    int set2 = valueStack.pop();
    valueStack.push(union(set1, set2));
  }

  public int union(int set1, int set2) {
    int seq1 = setElements(set1);
    int seq2 = setElements(set2);
    if (seq2 == nilValue) return set1;
    int union = seq2;
    while (seq1 != nilValue) {
      int value = consHead(seq1);
      boolean member = false;
      int seq3 = seq2;
      while (seq3 != nilValue && !member) {
        if (equalValues(consHead(seq3), value))
          member = true;
        else seq3 = consTail(seq3);
      }
      if (!member) union = mkCons(value, union);
      seq1 = consTail(seq1);
    }
    return mkSet(union);
  }

  public void asSeq() {

    // Implements the ASSEQ instruction...

    if (needsGC()) gc();
    int collection = valueStack.pop();
    pushAsSeq(collection);
  }

  public int asSeq(int value) {

    // Translates the value to a sequence...

    switch (tag(value)) {
    case BUFFER:
      return bufferAsSeq(value);
    case SET:
      return setElements(value);
    case CONS:
    case NIL:
      return value;
    case STRING:
      return stringAsSeq(value);
    case SYMBOL:
      return stringAsSeq(symbolName(value));
    case ARRAY:
      return arrayAsSeq(value);
    default:
      throw new MachineError(TYPE, "asSeq ", value, theTypeSeqOfElement);
    }
  }

  public void pushAsSeq(int value) {

    // Pushes a sequence on the stack...

    switch (tag(value)) {
    case FOREIGNOBJ:
      openFrame();
      valueStack.push(value);
      send(0, mkSymbol("asSeq"));
      break;
    default:
      valueStack.push(asSeq(value));
    }
  }

  public void at() {

    // Implements the AT instruction...

    int collection = valueStack.pop();
    int index = valueStack.pop();
    switch (tag(collection)) {
    case BUFFER:
    case NIL:
    case CONS:
    case STRING:
    case SYMBOL:
    case ARRAY:
      valueStack.push(at(collection, value(index)));
      break;
    default:
      openFrame();
      valueStack.push(index);
      valueStack.push(collection);
      send(1, mkSymbol("at"));
    }
  }

  public int at(int seq, int index) {
    switch (tag(seq)) {
    case BUFFER:
      return bufferRef(seq, index);
    case NIL:
      throw new MachineError(ERROR, "Machine.at: empty sequence.");
    case CONS:
      while (index > 0) {
        seq = consTail(seq);
        index--;
        if (isNil(seq)) throw new MachineError(ERROR, "Seq(Element)::at: encountered Seq{} - index too big?");
        if (!isCons(seq)) throw new MachineError(TYPE, "at.", seq, theTypeSeqOfElement);
      }
      return consHead(seq);
    case STRING:
      if (index < stringLength(seq))
        return mkInt(stringRef(seq, index));
      else throw new MachineError(ERROR, "String::at index " + index + " out of bounds in " + valueToString(seq));
    case SYMBOL:
      return at(symbolName(seq), index);
    case ARRAY:
      if (index < arrayLength(seq))
        return arrayRef(seq, index);
      else throw new MachineError(ERROR, "Machine.at: array index out of range: " + valueToString(seq) + " index = " + index);
    default:
      throw new MachineError(ERROR, "Machine.at: expecting a sequence " + valueToString(seq));
    }
  }

  public int type(int value) {

    // Implements the OF instruction...

    switch (value >> 24) {
    case ARRAY:
      return theClassVector;
    case BOOL:
      return theTypeBoolean;
    case BUFFER:
      return theClassBuffer;
    case CODEBOX:
      return theClassCodeBox;
    case FUN:
      return theClassCompiledOperation;
    case FOREIGNFUN:
      return theClassForeignOperation;
    case FORWARDREF:
      return theClassForwardRef;
    case FOREIGNOBJ:
      return foreignObjects.elementAt(this.foreignObjIndex(value)).getType();
    case INT:
    case NEGINT:
    case BIGINT:
      return theTypeInteger;
    case OBJ:
      return objType(value);
    case STRING:
      return theTypeString;
    case CONS:
    case NIL:
      return theTypeSeqOfElement;
    case SET:
      return theTypeSetOfElement;
    case HASHTABLE:
      return theClassTable;
    case SYMBOL:
      return theClassSymbol;
    case UNDEFINED:
      return theTypeNull;
    case FLOAT:
      return theTypeFloat;
    case THREAD:
      return theClassThread;
    case DAEMON:
      return theClassDaemon;
    default:
      return theClassElement;
    }
  }

  public void throwIt() {

    // Implements the THROW instruction...
    // Find the most recently established handler for a catch and
    // invoke the handler. Make sure that we don't invoke it again
    // by setting it to 'null'. The handler must be a function that
    // expects 1 argument which is currently on the stack.

    while (frameHandler() == undefinedValue && currentFrame != -1)
      popFrame();
    int value = valueStack.top();
    if (frameHandler() == undefinedValue && currentFrame == -1) {
      System.out.println("Value thrown, no handler found.");
      System.out.println("Value = " + valueToString(value));
      System.out.println(valueToString(objAttValue(value, mkSymbol("message"))));
      System.exit(-1);
    } else if (currentFrame == -1)
      throwItRestart(value);
    else throwItCreateFrame(value);
  }

  public void throwItCreateFrame(int value) {

    // Create a new frame from the point that the frame handler
    // was defined. Pop the frame to lose the definition of the
    // try expression.

    int handler = frameHandler();
    popFrame();
    setFrameHandler(undefinedValue);
    openFrame();
    setOpenFrameGlobals(nilValue);
    setOpenFrameCodeBox(funCode(handler));
    setOpenFrameLocals(0);
    setOpenFrameSelf(undefinedValue);
    setOpenFrameSuper(nilValue);
    setOpenFrameDynamics(frameDynamics());
    setOpenFrameHandler(undefinedValue);
    valueStack.push(value);
    valueStack.push(handler);
    enter(1);
  }

  public void throwItRestart(int value) {

    // We have reached the end of the road on the stack.
    // Recreate a stack frame and try to start from there.

    int handler = frameHandler();
    if (isFun(handler)) {
      valueStack.reset();
      initStack(valueStack, funCode(handler), 0);
      for (int i = 0; i < value(codeBoxLocals(funCode(handler))); i++)
        valueStack.pop();
      valueStack.push(value);
      valueStack.push(handler);
    } else {
      System.err.println("Tried to restart, no handler!");
      saveBacktrace();
    }
  }

  public void tryIt(int freeVars, int codeBox) {

    // Implements the TRY instruction...
    //
    // The top of the stack is a handler (a function expecting 1 arg).
    // Below that is a collection of values that are freely referenced
    // in the body of the code box. Call the code box as though it
    // were a 0-arity function. The handler is added to the stack
    // frame for the call of the code box so that if it, or any subsequent
    // stack frame, throws a value then the handler is used (or a
    // sequently established handler). Note that when the stack frame
    // exits normally the handler is popped and therefore no longer
    // avalable...

    int handler = valueStack.pop();
    int globals = linkGlobals(freeVars);
    int locals = codeBoxLocals(codeBox);
    int arity = frameArity();
    openFrame();
    setOpenFrameGlobals(globals);
    setOpenFrameCodeBox(codeBox);
    setOpenFrameLocals(locals * 2);
    setOpenFrameSelf(frameSelf());
    setOpenFrameSuper(frameSuper());
    setOpenFrameDynamics(frameDynamics());
    setOpenFrameHandler(handler);
    closeFrame(locals + arity);
  }

  public void isKindOf() {

    // Implements the ISKINDOF instruction...

    int value = valueStack.pop();
    int type = valueStack.pop();
    if (value == undefinedValue)
      valueStack.push(trueValue);
    else {
      int valueType = type(value);
      int typeType = type(type);
      if (type == theClassElement)
        valueStack.push(trueValue);
      else if (typeType == theTypeSeq && value == nilValue)
        valueStack.push(trueValue);
      else if (typeType == theTypeSet && value == emptySet)
        valueStack.push(trueValue);
      else if (isCons(value) && !(typeType == theTypeSeq || type == theClassElement))
        valueStack.push(falseValue);
      else if (value == nilValue && !(typeType == theTypeSeq || type == theClassElement))
        valueStack.push(falseValue);
      else if (isSet(value) && !(typeType == theTypeSet || type == theClassElement))
        valueStack.push(falseValue);
      else if (valueType == theTypeSeqOfElement || valueType == theTypeSetOfElement)
        sendIsKindOf(value, type);
      else if (isFun(value) && type(type) != theClassClass)
        sendIsKindOf(value, type);
      else basicIsKindOf(value, type, valueType);
    }
  }

  public void sendIsKindOf(int value, int type) {

    // Use the upper levels to handle special types...

    openFrame();
    valueStack.push(type);
    valueStack.push(value);
    send(1, mkSymbol("isKindOf"));
  }

  public void basicIsKindOf(int value, int type, int valueType) {

    // Implemented in the VM for efficiency...

    int TOS = valueStack.getTOS();
    boolean isKindOf = false;
    valueStack.push(valueType);
    while (valueStack.getTOS() != TOS && !isKindOf) {
      valueType = valueStack.pop();
      if (valueType == type)
        isKindOf = true;
      else {
        int parents = asSeq(objAttValue(valueType, theSymbolParents));
        while (parents != nilValue) {
          valueStack.push(consHead(parents));
          parents = consTail(parents);
        }
      }
    }
    valueStack.setTOS(TOS);
    valueStack.push(isKindOf ? trueValue : falseValue);
  }

  public void getElement(int name) {

    // Implements the GETELEMENT instruction...

    int nameSpace = valueStack.pop();
    int table = objAttValue(nameSpace, theSymbolContents);
    if (table == -1) throw new MachineError(TYPE, "Expecting name-space.", nameSpace, theClassPackage);
    if (hashTableHasKey(table, name))
      valueStack.push(hashTableGet(table, name));
    else throw new MachineError(NAMESPACEERR, "Name space " + valueToString(symbolName(getName(nameSpace))) + " does not define a name " + valueToString(name));
  }

  public int getName(int value) {

    // Many values have names. This returns the name of the value
    // or -1 if the value has no name.

    switch (tag(value)) {
    case OBJ:
      return objGetName(value);
    case FOREIGNFUN:
      return foreignFunName(value);
    case FUN:
      return funName(value);
    default:
      return -1;
    }
  }

  public void setHead() {

    // Implements the SETHEAD instruction...

    int seq = valueStack.pop();
    int value = valueStack.top();
    if (isCons(seq))
      consSetHead(seq, value);
    else throw new MachineError(TYPE, "setHead expects a sequence: ", seq, theTypeSeqOfElement);
  }

  public void setTail() {

    // Implements the SETTAIL instruction...

    int seq = valueStack.pop();
    int value = valueStack.top();
    if (isCons(seq))
      consSetTail(seq, value);
    else throw new MachineError(TYPE, "setTail expects a sequence: ", seq, theTypeSeqOfElement);
  }

  public void read() {

    // Implements the READ instruction...

    int channel = valueStack.pop();
    if (isInputChannel(channel))
      read(channel);
    else if (isString(channel))
      readClient(valueToString(channel));
    else error(ERROR, "Machine.read() expects an input channel: " + valueToString(channel));
  }

  public void read(int in) {

    // If the input channel is ready for input then we can go ahead
    // and read from it. Otherwise, XOS will mark the current thread
    // as blocking on input from the supplied channel and the current
    // thread yields. XOS will re-schedule the thread when input
    // becomes available on the thread.

    if (XOS.ready(value(in)))
      readReadyChannel(in);
    else {
      XOS.blockOnRead(value(in));
      yield();
    }
  }

  public void readClient(String name) {

    // Called with the name of a message client.

    if (XOS.isMessageClient(name))
      if (XOS.ready(name))
        readMessageClient(name);
      else {
        XOS.blockOnRead(name);
        yield();
      }
    else error(ERROR, "Machine.read() expects a client name: " + name);
  }

  public void accept() {

    // Implements the ACCEPT instruction...
    // The head of the stack should be a string that names a client.
    // If the client has connected to XOS then return true otherwise
    // block until the the client connects.

    int value = valueStack.pop();
    if (isString(value)) {
      String name = valueToString(value);
      if (XOS.isConnected(name))
        valueStack.push(trueValue);
      else {
        XOS.blockOnAccept(name);
        yield();
      }
    } else error(TYPE, "Machine.accept expects a name.", value, theTypeString);
  }

  public void arrayRef() {

    // Implements the ARRAYREF instruction...

    int array = valueStack.pop();
    int index = valueStack.pop();
    if ((isArray(array) || isTable(array)) && value(index) < arrayLength(array))
      valueStack.push(arrayRef(array, value(index)));
    else if (isBuffer(array))
      valueStack.push(bufferRef(array, value(index)));
    else {
      openFrame();
      valueStack.push(index);
      valueStack.push(array);
      send(1, mkSymbol("ref"));
    }
  }

  public void arraySet() {

    // Implements the ARRAYSET instruction...

    int array = valueStack.pop();
    int index = valueStack.pop();
    int newValue = valueStack.pop();
    arraySetValue(array, index, newValue);
  }

  public void arraySetValue(int array, int index, int newValue) {
    if (isArray(array) || isTable(array)) {
      int oldValue = arrayRef(array, value(index));
      arraySet(array, value(index), newValue);
      arraySetDaemons(array, index, newValue, oldValue);
    } else if (isBuffer(array)) {
      if (value(index) < value(bufferSize(array))) {
        int oldValue = bufferRef(array, value(index));
        bufferSet(array, value(index), newValue);
        bufferSetDaemons(array, index, newValue, oldValue);
      } else {
        bufferSet(array, value(index), newValue);
        valueStack.push(array);
      }
    } else error(TYPE, "Machine.arraySet() expects an array, table or buffer.", array, theClassVector);
  }

  public void bufferSetDaemons(int buffer, int index, int newValue, int oldValue) {
    if ((bufferDaemonsActive(buffer) == trueValue) && (bufferDaemons(buffer) != nilValue)) {
      openFrame();
      valueStack.push(mkInt(index));
      valueStack.push(newValue);
      valueStack.push(oldValue);
      valueStack.push(buffer);
      send(3, theSymbolFire);
    } else valueStack.push(buffer);
  }

  public void arraySetDaemons(int array, int index, int newValue, int oldValue) {
    if ((arrayDaemonsActive(array) == trueValue) && (arrayDaemons(array) != nilValue)) {
      openFrame();
      valueStack.push(mkInt(index));
      valueStack.push(newValue);
      valueStack.push(oldValue);
      valueStack.push(array);
      send(3, theSymbolFire);
    } else valueStack.push(array);
  }

  public void tableGet() {

    // Implements the TABLEGET instruction...

    int table = valueStack.pop();
    int key = valueStack.pop();
    tableGet(table, key);
  }

  public void tableGet(int table, int key) {
    if (isTable(table)) {
      int value = hashTableGet(table, key);
      if (value == -1)
        error(NOKEY, "No key in table.", table, key);
      else valueStack.push(value);
    } else error(TYPE, "Machine::get expects a table.", table, theClassTable);
  }

  public void tablePut() {

    // Implements the TABLEPUT instruction...

    int table = valueStack.pop();
    int key = valueStack.pop();
    int value = valueStack.pop();
    tablePut(table, key, value);
  }

  public void tablePut(int table, int key, int value) {
    if (isTable(table)) {
      if (hashTableGet(table, key) != value) {
        int index = hashTableIndex(table, key);
        boolean active = arrayDaemonsActive(table) == trueValue;
        boolean hasDaemons = arrayDaemons(table) != nilValue;
        if (active && hasDaemons) {
          int oldBucket = copyBucket(hashTableBucket(table, key));
          hashTablePut(table, key, value);
          int newBucket = hashTableBucket(table, key);
          openFrame();
          valueStack.push(mkInt(index));
          valueStack.push(newBucket);
          valueStack.push(oldBucket);
          valueStack.push(table);
          send(3, theSymbolFire);
        } else {
          hashTablePut(table, key, value);
          valueStack.push(table);
        }
      } else valueStack.push(table);
    } else error(TYPE, "Table::put expects a table.", table, theClassTable);
  }

  public void sleep() {

    // Set the current thread to be sleeping and yield.
    // Implements the SLEEP instruction...

    threads.sleep();
    yield();
  }

  public void dispatch(int jumpTable, int value) {

    // Implements the DISPATCH instruction...

    if (isArray(jumpTable) && isInt(value)) {
      int index = value(value);
      int length = arrayLength(jumpTable);
      if (index < length)
        incFrameCodeIndex(value(arrayRef(jumpTable, index)));
      else throw new MachineError(ERROR, "Machine.dispatch: index out of range.");
    } else throw new MachineError(ERROR, "Machine.dispatch: expecting a jump table and an integer index: " + valueToString(value) + " " + valueToString(jumpTable));
  }

  public void incFrameCodeIndex(int offset) {
    // Used to jump about the current frame instructions.
    valueStack.set(currentFrame + FRAMECODEINDEX, frameCodeIndex() + offset);
  }

  public void incSelfSlot(int slot) {

    // Implements the INCSELFSLOT instruction...

    int self = frameSelf();
    int value = objAttValue(self, slot);
    if (value == -1)
      throw new MachineError(MISSINGSLOT, "Machine.incSelfSlot no slot named " + valueToString(slot));
    else objSetAttValue(self, slot, mkInt(intValue(value) + 1));
    pushStack(self);
  }

  public void decSelfSlot(int slot) {

    // Implements the DECSELFSLOT instruction...

    int self = frameSelf();
    int value = objAttValue(self, slot);
    if (value == -1)
      throw new MachineError(MISSINGSLOT, "No slot.", self, slot);
    else objSetAttValue(self, slot, mkInt(intValue(value) - 1));
    pushStack(self);
  }

  public void prepend() {

    // Implements the PREPEND instruction...

    if (needsGC()) gc();
    int tail = valueStack.pop();
    int head = valueStack.pop();
    if (isCons(tail) || tail == nilValue)
      valueStack.push(mkCons(head, tail));
    else throw new MachineError(TYPE, "prepend expects a sequence: ", tail);
  }

  public void enterDynamic(int name, int arity) {

    // Implements the ENTERDYNAMIC instruction...

    int operator = lookupDynamic(name);
    if (operator == -1)
      error(UNBOUNDVAR, "Unbound dynamic " + valueToString(name), name, mkInt(frameLineCount()), mkInt(0));
    else enterOperator(operator, arity);
  }

  public int lookupDynamic(int name) {

    // Does not push the value on the stack - just returns it.
    // Returns -1 if not bound...

    int value = dynamicSlotReference(name);
    if (value == -1)
      return dynamicValue(name);
    else return value;
  }

  public int dynamicValue(int name) {

    // Returns the dynamic value in the stack frame or
    // -1 if not present...

    int dynamics = frameDynamics();
    boolean found = false;
    while (!found && (dynamics != nilValue)) {
      dynamicLookupSteps++;
      int cell = consHead(dynamics);
      dynamics = consTail(dynamics);
      switch (dynamicCellType(cell)) {
      case DYNAMIC_VALUE:
        int binding = dynamicCellValue(cell);
        if (dynamicBindingName(binding) == name) return dynamicBindingValue(binding);
        break;
      case DYNAMIC_TABLE:
        int table = dynamicCellValue(cell);
        int value = hashTableGet(table, name);
        if (value != -1) return value;
        break;
      default:
        throw new MachineError(ERROR, "Machine.dynamicValue: Unknown type of dynamic cell: " + valueToString(cell));
      }
    }
    return -1;
  }

  public void tailEnterDynamic(int name, int arity) {

    // Implements the TAILENTERDYNAMIC instruction...

    openFrame = currentFrame;
    setOpenFrameCodeIndex(0);
    int argIndex = valueStack.getTOS() - arity;
    valueStack.setTOS(openFrame + FRAMELOCAL0);
    for (int i = 0; i < arity; i++) {
      int value = valueStack.ref(argIndex++);
      pushStack(value);
    }
    enterDynamic(name, arity);
  }

  public void assoc() {

    // Implements the ASSOC instruction...
    // An a-list is at the head of the stack over a key.
    // return the list from the pair headed by the key
    // or return null.

    int aList = popStack();
    int key = popStack();
    boolean found = false;
    while (aList != nilValue && isCons(aList) && !found) {
      int pair = consHead(aList);
      if (isCons(pair))
        if (equalValues(consHead(pair), key))
          found = true;
        else aList = consTail(aList);
      else aList = nilValue;
    }
    if (found)
      pushStack(aList);
    else pushStack(undefinedValue);
  }

  private void toStringInstr() {

    // Implements TOSTRING instruction...

    int value = valueStack.pop();
    switch (tag(value)) {
    case INT:
      valueStack.push(intToXMFString(value));
      break;
    case BOOL:
      valueStack.push(mkString(value == trueValue ? "true" : "false"));
      break;
    case STRING:
      valueStack.push(value);
      break;
    case SYMBOL:
      valueStack.push(symbolName(value));
      break;
    case BUFFER:
      if (bufferAsString(value) == trueValue)
        valueStack.push(stringBufferToXMFString(value));
      else {
        openFrame();
        valueStack.push(value);
        send(0, mkSymbol("toString"));
      }
      break;
    default:
      openFrame();
      valueStack.push(value);
      send(0, mkSymbol("toString"));
    }
  }

  private final int intToXMFString(int value) {
    buffer.setLength(0);
    int i = value(value);

    if (i == 0) return mkString("0");

    while (i > 0) {
      buffer.insert(0, i % 10);
      i = i / 10;
    }
    return mkString(buffer);
  }

  private void arity() {

    // Implements ARITY instruction...

    int op = valueStack.pop();
    switch (tag(op)) {
    case FUN:
      valueStack.push(mkInt(funArity(op)));
      break;
    default:
      openFrame();
      valueStack.push(op);
      send(0, mkSymbol("arity"));
    }
  }

  private void stringEqual() {

    // Implements STRINGEQL instruction...

    int v2 = popStack();
    int v1 = popStack();
    switch (tag(v1)) {
    case BUFFER:
      if (bufferStringEqual(v1, v2))
        valueStack.push(trueValue);
      else valueStack.push(falseValue);
      break;
    case STRING:
      if (isBuffer(v2))
        if (bufferStringEqual(v1, v2))
          valueStack.push(trueValue);
        else valueStack.push(falseValue);
      else if (this.equalValues(v1, v2))
        valueStack.push(trueValue);
      else valueStack.push(falseValue);
      break;
    default:
      openFrame();
      pushStack(v2);
      pushStack(v1);
      send(1, mkSymbol("stringEqual"));
    }

  }

  private void get() {

    // Implements GET instruction...

    int table = valueStack.pop();
    int key = valueStack.pop();
    switch (tag(table)) {
    case HASHTABLE:
      tableGet(table, key);
      break;
    case OBJ:
      if (isString(key))
        dot(mkSymbol(key), table);
      else dot(key, table);
      break;
    default:
      openFrame();
      valueStack.push(key);
      valueStack.push(table);
      send(1, mkSymbol("get"));
    }
  }

  private void put() {

    // Implements PUT instruction...

    int table = valueStack.pop();
    int value = valueStack.pop();
    int key = valueStack.pop();
    switch (tag(table)) {
    case HASHTABLE:
      tablePut(table, key, value);
      break;
    case ARRAY:
    case BUFFER:
      arraySetValue(table, key, value);
      break;
    default:
      openFrame();
      valueStack.push(key);
      valueStack.push(value);
      valueStack.push(table);
      send(2, mkSymbol("put"));
    }

  }

  private void hasKey() {

    // Implements HASKEY instruction...

    int table = valueStack.pop();
    int key = valueStack.pop();
    switch (tag(table)) {
    case HASHTABLE:
      if (hashTableHasKey(table, key))
        valueStack.push(trueValue);
      else valueStack.push(falseValue);
      break;
    default:
      openFrame();
      valueStack.push(table);
      valueStack.push(key);
      send(1, mkSymbol("hasKey"));
    }
  }

  private void localName(int names, int nameIndex, int offset) {

    // Implements LOCALNAME instruction...

    int localName = arrayRef(names, nameIndex);
    int arity = frameArity();
    int locals = (frameLocals() - arity) / 2;
    int index = arity + locals + offset;
    setFrameLocal(index, localName);
  }

  private void unsetLocal(int offset) {

    // Implements UNSETLOCAL instruction...

    int arity = frameArity();
    int locals = (frameLocals() - arity) / 2;
    int index = arity + locals + offset;
    setFrameLocal(index, undefinedValue);
  }

  // The stack frame has the structure defined in StackFrame.java.
  // The accessors and updaters for the stack frame are defined
  // as Java methods below...

  public int frameCharCount() {

    // Deprecated...

    return valueStack.ref(currentFrame + FRAMECHARCOUNT);
  }

  public int frameCodeBox() {

    // The currently executing code box...

    return valueStack.ref(currentFrame + FRAMECODEBOX);
  }

  public int frameResourceName() {

    // The path to the current source file or "" if
    // this is not defined...

    int codeBox = frameCodeBox();
    return codeBoxResourceName(codeBox);
  }

  public int frameArity() {

    // The arity of the currently executing fun ...

    return funArity(consHead(frameSuper()));
  }

  public int frameCodeIndex() {

    // The index into the current frame instructions...

    return value(valueStack.ref(currentFrame + FRAMECODEINDEX));
  }

  public int frameConstant(int index) {

    // Index a particular constant...

    return arrayRef(frameConstants(), index);
  }

  public int frameConstants() {

    // The constants for the instructions in the current frame...

    return codeBoxConstants(frameCodeBox());
  }

  public int frameDynamics() {

    // The dynamic variables imported into the current fun...

    return valueStack.ref(currentFrame + FRAMEDYNAMICS);
  }

  public int frameGlobal(int frame, int index) {

    // Index a global variable. Note that globals require binding
    // contour information (frame) and an index into the globals
    // structure for that contour. When global structures are created
    // they are chained to the global structure for the surrounding
    // contour...

    int globals = frameGlobals();
    for (int i = 0; i < frame; i++)
      globals = globalsPrev(globals);
    return arrayRef(globalsArray(globals), index);
  }

  public int frameGlobals() {

    // The global variable values in the current frame...

    return valueStack.ref(currentFrame + FRAMEGLOBALS);
  }

  public int frameHandler() {

    // Either undefined or a single argument function that is used
    // to handle values that are thrown from younger stack frames...

    return valueStack.ref(currentFrame + FRAMEHANDLER);
  }

  public int frameInstrs() {

    // The instruction array being performed...

    return codeBoxInstrs(frameCodeBox());
  }

  public int frameLineCount() {

    // The current line number in the source code...

    return valueStack.ref(currentFrame + FRAMELINECOUNT);
  }

  public int frameLocal(int index) {

    // The locals live above the fixed size part of the current
    // stack frame (at FRAMELOCAL0). This method is used to index
    // a particular local. Note that arguments of functions are
    // locals (occurring on the stack before let-bound locals)...

    return valueStack.ref(currentFrame + FRAMELOCAL0 + index);
  }

  public int frameLocals() {

    // The total number of locals required by the frame. In the
    // case of functions this includes the arguments and space for
    // the name of non-arg locals...

    return valueStack.ref(currentFrame + FRAMELOCALS);
  }

  public int frameNameSpace() {

    // There is no explicit representation of the name-space for
    // the currently executing frame. However the current function
    // should have an owner which *might* be a name-space...

    int operations = frameSuper();
    int operation = consHead(operations);
    return funOwner(operation);
  }

  public int frameNameSpace(int frame) {

    // Get the name-space from the supplied frame...

    int supers = valueStack.ref(frame + FRAMESUPER);
    if (isCons(supers)) {
      int active = consHead(supers);
      int owner = funOwner(active);
      return owner;
    } else return undefinedValue;
  }

  public String frameNameSpaceName(int frame) {
    int nameSpace = frameNameSpace(frame);
    if (nameSpace == undefinedValue)
      return "?";
    else return valueToString(symbolName(objAttValue(nameSpace, theSymbolName)));
  }

  public int frameSelf() {

    // The value of 'self' with respect to the current frame...

    return valueStack.ref(currentFrame + FRAMESELF);
  }

  public int frameSuper() {

    // A sequence of operations headed by the currently executing
    // operation...

    return valueStack.ref(currentFrame + FRAMESUPER);
  }

  public int prevFrame() {

    // The previously active stack frame. This is used to restore the value
    // of 'currentFrame' when the current frame is popped.

    return valueStack.ref(currentFrame + PREVFRAME);
  }

  public int prevOpenFrame() {

    // The previously open frame. This is restored when the current frame
    // is popped...

    return valueStack.ref(currentFrame + PREVOPENFRAME);
  }

  public int prevPrevFrame() {
    return valueStack.ref(prevFrame() + PREVFRAME);
  }

  public int prevPrevOpenFrame() {
    return valueStack.ref(prevOpenFrame() + PREVOPENFRAME);
  }

  public void setFrameSelf(int self) {

    // Set the target of the frame...

    valueStack.set(currentFrame + FRAMESELF, self);
  }

  public void setFrameSuper(int supers) {

    // Set the list of operations used for super...

    valueStack.set(currentFrame + FRAMESUPER, supers);
  }

  public void setFrameCharCount(int charCount) {
    valueStack.set(currentFrame + FRAMECHARCOUNT, mkInt(charCount));
  }

  public void setFrameDynamics(int dynamics) {
    // Required for let-dynamic.
    valueStack.set(currentFrame + FRAMEDYNAMICS, dynamics);
  }

  public void setFrameGlobals(int globals) {
    valueStack.set(currentFrame + FRAMEGLOBALS, globals);
  }

  public void setFrameHandler(int handler) {
    valueStack.set(currentFrame + FRAMEHANDLER, handler);
  }

  public void setFrameLineCount(int lineCount) {
    valueStack.set(currentFrame + FRAMELINECOUNT, mkInt(lineCount));
  }

  public void setOpenFrameCodeBox(int codeBox) {
    valueStack.set(openFrame + FRAMECODEBOX, codeBox);
  }

  public void setOpenFrameCodeIndex(int index) {
    valueStack.set(openFrame + FRAMECODEINDEX, index);
  }

  public void setOpenFrameDynamics(int dynamics) {
    valueStack.set(openFrame + FRAMEDYNAMICS, dynamics);
  }

  public void setOpenFrameGlobals(int globals) {
    valueStack.set(openFrame + FRAMEGLOBALS, globals);
  }

  public void setOpenFrameHandler(int handler) {
    valueStack.set(openFrame + FRAMEHANDLER, handler);
  }

  public void setOpenFrameLocals(int locals) {
    valueStack.set(openFrame + FRAMELOCALS, locals);
  }

  public void setOpenFramePrevFrame(int index) {
    valueStack.set(openFrame + PREVFRAME, index);
  }

  public void setOpenFramePrevOpenFrame(int index) {
    valueStack.set(openFrame + PREVOPENFRAME, index);
  }

  public void setOpenFrameSelf(int self) {
    valueStack.set(openFrame + FRAMESELF, self);
  }

  public void setOpenFrameSuper(int supers) {
    valueStack.set(openFrame + FRAMESUPER, supers);
  }

  public int openFrameLocal(int index) {
    return valueStack.ref(openFrame + FRAMELOCAL0 + index);
  }

  // The garbage collector is a stop-and-copy system that starts
  // from a collection of known roots and follows pointers copying
  // the values from an old heap into a new heap. When the stack
  // of pointers is exhausted then the new heap becomes current
  // and the old heap is thrown away. This way any garbage is not
  // copied to the new heap and is discarded.

  public void gc() {

    // Perform garbage collection. Can be called anywhere providing all
    // collectable values are accessible from the top-level machine
    // structures.

    long startTime = System.currentTimeMillis();

    try {
      clearGCHeap();
      gcResetStats();
      gcSymbols();
      gcSpecials();
      gcForeign();
      gcStack();
      gcConstants();
      gcUndo();
      gc.gcComplete();
      swapHeap();
      gc.gcPopStack();
      if (!gc.isSilent()) gcDiagnostics(startTime);
    } catch (Throwable t) {
      error(GCERROR, t.getMessage());
    }
  }

  public GC getGC() {
    return gc;
  }

  public void setGC(GC gc) {
    this.gc = gc;
  }

  public void clearGCHeap() {

    // The garbage collector copies into the
    // gcWords heap. This must be
    // cleared prior to copy.

    for (int i = 0; i < gcWords.length; i++)
      gcWords[i] = undefinedValue;
    gcFreePtr = 0;
    gcCopiedPtr = 0;
  }

  public void swapHeap() {

    // Swap between the two heaps. All allocation and memory reference
    // occurs with respect to the 'words' array and 'freePtr'...

    int[] tempWords = words;
    int tempFreePtr = freePtr;
    words = gcWords;
    freePtr = gcFreePtr;
    gcWords = tempWords;
    gcFreePtr = tempFreePtr;
  }

  public boolean collected(int word) {

    // A data structure has been collected when its header word is a
    // forward pointer into the gcHeap...

    return tag(ref(ptr(word))) == FORWARD;
  }

  public void setForward(int source, int destination) {

    // The source structure has been copied into the destination. Set the
    // header word to be a forward pointer. Assume that the heaps are
    // correct.

    set(ptr(source), mkPtr(FORWARD, ptr(destination)));
  }

  public int forward(int tag, int word) {

    // Tag the pointer part of the forward pointer in the header word...

    return mkPtr(tag, ptr(ref(ptr(word))));
  }

  public void gcConstants() {
    gcSymbolConstants();
    gcTypeConstants();
    operatorTable = gcCopy(operatorTable);
    constructorTable = gcCopy(constructorTable);
    newListenersTable = gcCopy(newListenersTable);
  }

  public int gcCopy(int word) {

    // Copy a data structure into the new heap returning a machine word
    // with respect to the new heap...

    switch (tag(word)) {
    case ARRAY:
      return gc.gcArray(word);
    case BUFFER:
      return gc.gcBuffer(word);
    case BOOL:
      return word;
    case CODEBOX:
      return gc.gcCodeBox(word);
    case CODE:
      return gc.gcCode(word);
    case CONT:
      return gc.gcCont(word);
    case INT:
    case NEGINT:
      return word;
    case FOREIGNFUN:
      return word;
    case FOREIGNOBJ:
      return word;
    case FUN:
      return gc.gcFun(word);
    case OBJ:
      return gc.gcObj(word);
    case STRING:
      return gc.gcString(word);
    case UNDEFINED:
      return word;
    case CONS:
      return gc.gcCons(word);
    case SET:
      return gc.gcSet(word);
    case NIL:
      return word;
    case SYMBOL:
      return gc.gcSymbol(word);
    case INPUT_CHANNEL:
    case OUTPUT_CHANNEL:
    case CLIENT:
    case THREAD:
      return word;
    case HASHTABLE:
      return gc.gcHashTable(word);
    case FLOAT:
      return gc.gcFloat(word);
    case DAEMON:
      return gc.gcDaemon(word);
    case FORWARDREF:
      return gc.gcForwardRef(word);
    default:
      throw new MachineError(GCERROR, "Machine.gcCopy: unknown type tag " + tag(word));
    }
  }

  public void gcDiagnostics(long startTime) {
    // Print out some information stating how much memory was freed up.
    int freedup = gcFreePtr - freePtr;
    long endTime = System.currentTimeMillis();
    long time = endTime - startTime;
    int percentFreed = (int) (((float) freedup / (float) gcFreePtr) * 100);
    int availableMB = (heapSize - freePtr) * 4 / (1024 * 1024);
    int usedMB = freePtr * 4 / (1024 * 1024);
    System.out.print("[GC");
    // System.out.println(" before = " + gcFreePtr + " words,");
    // System.out.print(" after = " + freePtr + " words,");
    System.out.print(" " + percentFreed + "% collected in " + time + " ms,");
    System.out.print(" " + usedMB + "MB used,");
    System.out.println(" " + availableMB + "MB available.]");
  }

  public void gcForeign() {

    // Garbage collect the foreign objects tables....

    for (ForeignObject f : foreignObjects)
      f.setType(gcCopy(f.getType()));
  }

  public int getGCTOS() {
    return gcTOS;
  }

  public void rehash(int table) {

    // Elements hash codes may have changed (due to gc ?)
    // so reposition all the elements in the table...

    int TOS = valueStack.getTOS();
    hashTablePushCells(table);
    hashTableClear(table);
    hashTablePopCells(table, TOS);
  }

  public void hashTablePushCells(int table) {
    for (int i = 0; i < arrayLength(table); i++) {
      int bucket = arrayRef(table, i);
      while (bucket != nilValue) {
        int cell = consHead(bucket);
        valueStack.vpush(cell);
        bucket = consTail(bucket);
      }
    }
  }

  public void hashTablePopCells(int table, int TOS) {
    while (valueStack.getTOS() != TOS) {
      int cell = valueStack.pop();
      int key = consHead(cell);
      int value = consTail(cell);
      hashTablePut(table, key, value);
    }
  }

  public void rehash(int newTable, int oldTable) {
    int length = arrayLength(oldTable);
    for (int i = 0; i < length; i++) {
      int bucket = arrayRef(oldTable, i);
      while (bucket != nilValue) {
        int cell = consHead(bucket);
        int key = consHead(cell);
        int value = consTail(cell);
        hashTablePut(newTable, key, value);
      }
    }
  }

  public void gcResetStats() {

    // Reset the data structure counts at the beginning of a garbage
    // collect...

    memory.setTotalUsed(freePtr);
    memory = memory.newRecord();
    gcTOS = valueStack.getTOS();
  }

  public int gcSet(int set) {
    if (collected(set))
      return forward(SET, set);
    else {
      int elements = setElements(set);
      swapHeap();
      int newSet = mkSet(elements);
      swapHeap();
      setForward(set, newSet);
      return newSet;
    }
  }

  public void gcSpecials() {

    // Garbage collect various values that are known by the
    // machine...

    int array0 = emptyArray;
    int set0 = emptySet;
    emptyArray = undefinedValue;
    emptySet = undefinedValue;
    emptyArray = gcCopy(array0);
    emptySet = gcCopy(set0);
    globalDynamics = gcCopy(globalDynamics);
    clientInterface = gcCopy(clientInterface);
    foreignTypeMapping = gcCopy(foreignTypeMapping);
    foreignMOPMapping = gcCopy(foreignMOPMapping);
  }

  public void gcStack() {

    // Most accessible structures are found by following values on the
    // stack. To save time the garbage collector does not bother with
    // stack frames it just zooms up the stack and collects all values...

    Thread t = threads.next();
    while (t != threads) {
      ValueStack stack = t.stack();
      gcStack(stack, stack.getTOS());
      t = t.next();
    }
    gcStack(valueStack, gcTOS);
  }

  public void gcStack(ValueStack stack, int TOS) {
    for (int i = 0; i < TOS; i++) {
      int value = stack.ref(i);

      // Really all values on the stack should be tagged.
      // -1 is used for the initial values of the frame registers...

      if (value != -1) stack.set(i, gcCopy(value));
    }
  }

  public void gcSymbolConstants() {
    theSymbolAttributes = gc.gcSymbol(theSymbolAttributes);
    theSymbolInit = gc.gcSymbol(theSymbolInit);
    theSymbolMachineInit = gc.gcSymbol(theSymbolMachineInit);
    theSymbolType = gc.gcSymbol(theSymbolType);
    theSymbolDefault = gc.gcSymbol(theSymbolDefault);
    theSymbolOperations = gc.gcSymbol(theSymbolOperations);
    theSymbolParents = gc.gcSymbol(theSymbolParents);
    theSymbolName = gc.gcSymbol(theSymbolName);
    theSymbolArity = gc.gcSymbol(theSymbolArity);
    theSymbolOwner = gc.gcSymbol(theSymbolOwner);
    theSymbolContents = gc.gcSymbol(theSymbolContents);
    theSymbolInvoke = gc.gcSymbol(theSymbolInvoke);
    theSymbolDot = gc.gcSymbol(theSymbolDot);
    theSymbolFire = gc.gcSymbol(theSymbolFire);
    theSymbolValue = gc.gcSymbol(theSymbolValue);
    theSymbolDocumentation = gc.gcSymbol(theSymbolDocumentation);
    theSymbolHead = gc.gcSymbol(theSymbolHead);
    theSymbolTail = gc.gcSymbol(theSymbolTail);
    theSymbolIsEmpty = gc.gcSymbol(theSymbolIsEmpty);
    theSymbolNewListener = gc.gcSymbol(theSymbolNewListener);
  }

  public void gcSymbols() {
    forwardRefs = gcCopy(forwardRefs);
    symbolTable = gcCopy(symbolTable);
  }

  public int getSymbolTable() {
    return symbolTable;
  }

  public int getForwardRefs() {
    return forwardRefs;
  }

  public void gcTypeConstants() {
    theTypeBoolean = gcCopy(theTypeBoolean);
    theClassCompiledOperation = gcCopy(theClassCompiledOperation);
    theClassElement = gcCopy(theClassElement);
    theClassForeignObject = gcCopy(theClassForeignObject);
    theClassForeignOperation = gcCopy(theClassForeignOperation);
    theClassForwardRef = gcCopy(theClassForwardRef);
    theClassException = gcCopy(theClassException);
    theClassClass = gcCopy(theClassClass);
    theClassPackage = gcCopy(theClassPackage);
    theClassBind = gcCopy(theClassBind);
    theClassCodeBox = gcCopy(theClassCodeBox);
    theTypeInteger = gcCopy(theTypeInteger);
    theTypeFloat = gcCopy(theTypeFloat);
    theTypeString = gcCopy(theTypeString);
    theClassTable = gcCopy(theClassTable);
    theClassThread = gcCopy(theClassThread);
    theTypeSeq = gcCopy(theTypeSeq);
    theTypeSet = gcCopy(theTypeSet);
    theClassDataType = gcCopy(theClassDataType);
    theClassDaemon = gcCopy(theClassDaemon);
    theClassBuffer = gcCopy(theClassBuffer);
    theClassVector = gcCopy(theClassVector);
    theClassSymbol = gcCopy(theClassSymbol);
    theTypeNull = gcCopy(theTypeNull);
    theTypeSeqOfElement = gcCopy(theTypeSeqOfElement);
    theTypeSetOfElement = gcCopy(theTypeSetOfElement);
  }

  public void gcUndo() {
    undo.gc(this);
  }

  public boolean gcIsComplete() {
    return gcFreePtr != gcCopiedPtr;
  }

  public void gcUpdateCopied(int word) {
    gcWords[gcCopiedPtr++] = word;
  }

  public int[] getGCHeap() {
    return gcWords;
  }

  public int getGCCopiedPtr() {
    return gcCopiedPtr;
  }

  public int getGCFreePtr() {
    return gcFreePtr;
  }

  public void setGCCopiedPtr(int gcCopiedPtr) {
    this.gcCopiedPtr = gcCopiedPtr;
  }

  public void advanceCopiedPtr() {
    gcCopiedPtr++;
  }

  public void advanceCopiedPtrBy(int inc) {
    gcCopiedPtr = gcCopiedPtr + inc;
  }

  public int gcValue() {
    return gcWords[gcCopiedPtr];
  }

  public Header defaultHeader() {

    // A saved image has a header that contains properties. These
    // can be set before saving the image or can be set independently
    // by an application that modifies images.

    Header header = new Header();
    header.setProperty("Tool", "XMF-Mosaic");
    header.setProperty("Version", "1.0");
    return header;
  }

  // Various printing methods that can be used to debug the VM
  // Backtrace prining prints out information about all the stack
  // frames on the current stack...

  public void printBacktrace() {
    printBacktrace(System.out, currentFrame);
  }

  public void printBacktrace(PrintStream out, int frame) {

    // The code boxes in stack frames contain the names of the methods and
    // functions that have been invoked. Assuming that frame is a pointer to
    // a legal
    // frame, traverse back up the stack and print the names in the frames
    // and the locals.

    out.println();
    out.println("Call frame backtrace:");
    out.println();
    printInstrBacktrace(out, frame);
    while (frame != -1) {
      int codeBox = valueStack.ref(frame + FRAMECODEBOX);
      int name = symbolName(codeBoxName(codeBox));
      String nameSpace = frameNameSpaceName(frame);
      printLine(out);
      out.println("Called(" + frame + "): " + nameSpace + "::" + valueToString(name) + " at line " + value(valueStack.ref(frame + FRAMELINECOUNT)) + " char " + value(valueStack.ref(frame + FRAMECHARCOUNT)));
      int locals = valueStack.ref(frame + FRAMELOCALS);
      out.println("Self: " + valueToString(valueStack.ref(frame + FRAMESELF)));
      int supers = valueStack.ref(frame + FRAMESUPER);
      out.print("Supers: ");
      while (isCons(supers) && supers != nilValue) {
        int op = consHead(supers);
        if (isFun(op)) out.print(valueToString(symbolName(funName(op))));
        supers = consTail(supers);
        if (supers != nilValue) out.print(",");
      }
      out.println();
      for (int i = 0; i < locals; i++)
        out.println("Local(" + i + "): " + valueToString(valueStack.ref(frame + FRAMELOCAL0 + i)));
      out.println("Source Code:\n    " + valueToString(codeBoxSource(codeBox)));
      int constants = codeBoxConstants(valueStack.ref(frame + FRAMECODEBOX));
      for (int i = 0; i < arrayLength(constants); i++)
        out.println("constant(" + i + ") = " + valueToString(arrayRef(constants, i)));
      System.out.print("Handler: " + valueToString(valueStack.ref(frame + FRAMEHANDLER)));
      frame = valueStack.ref(frame + PREVFRAME);
      printLine(out);
      out.println();
      out.println();
    }
    out.println();
  }

  public void printCalls(PrintStream out) {

    // Just print a backtrace of the current calls..

    printCalls(out, currentFrame);
  }

  public void printCalls(PrintStream out, int frame) {
    while (frame != -1) {
      int codeBox = valueStack.ref(frame + FRAMECODEBOX);
      int name = codeBoxName(codeBox);
      String nameSpace = frameNameSpaceName(frame);
      out.println("Called(" + frame + "): " + nameSpace + "::" + valueToString(name));
      frame = valueStack.ref(frame + PREVFRAME);
    }
  }

  public void printCurrentFrameToTOS() {

  }

  public void printDynamics() {

    // Print all the dynamics currently in scope...

    int dynamics = frameDynamics();
    while (!(dynamics == nilValue)) {
      int cell = consHead(dynamics);
      int type = dynamicCellType(cell);
      dynamics = consTail(dynamics);
      switch (type) {
      case DYNAMIC_VALUE:
        int binding = dynamicCellValue(cell);
        int name = dynamicBindingName(binding);
        int value = dynamicBindingValue(binding);
        System.out.println(valueToString(name) + " = " + valueToString(value));
        break;
      case DYNAMIC_TABLE:
        int table = dynamicCellValue(cell);
        printDynamicTable(table);
        break;
      default:
        throw new MachineError(TYPE, "Machine.printDynamics: Unknown type of dynamic value " + valueToString(cell));
      }
    }
  }

  public void printDynamicTable(int table) {
    int keys = hashTableKeys(table);
    while (keys != nilValue) {
      int key = consHead(keys);
      keys = consTail(keys);
      int namedElement = hashTableGet(table, key);
      System.out.println(valueToString(key) + " = " + valueToString(namedElement));
    }
  }

  public void printFrame() {
    printFrame(currentFrame);
  }

  public void printFrame(int frame) {

    // Print the contents of the current stack frame...

    System.out.println("Frame at index " + frame + "---------------------------------------");
    System.out.println("PREVFRAME = " + valueStack.ref(frame + PREVFRAME));
    System.out.println("PREVOPENFRAME = " + valueStack.ref(frame + PREVOPENFRAME));
    System.out.println("INSTRS = " + valueToString(codeBoxInstrs(valueStack.ref(frame + FRAMECODEBOX))));
    System.out.println("CODEINDEX = " + valueStack.ref(frame + FRAMECODEINDEX));
    System.out.println("GLOBALS = " + valueToString(valueStack.ref(frame + FRAMEGLOBALS)));
    System.out.println("SELF = " + valueToString(valueStack.ref(frame + FRAMESELF)));
    System.out.println("SUPER = " + valueToString(valueStack.ref(frame + FRAMESUPER)));
    System.out.println("HANDLER = " + valueToString(valueStack.ref(frame + FRAMEHANDLER)));
    System.out.println("CONSTANTS = " + valueToString(codeBoxConstants(valueStack.ref(frame + FRAMECODEBOX))));
    for (int i = 0; i < valueStack.ref(frame + FRAMELOCALS); i++) {
      String local = valueToString(valueStack.ref(frame + FRAMELOCAL0 + i));
      System.out.println("[" + (frame + FRAMELOCAL0 + i) + "] LOCAL" + i + " = " + local);
    }
    System.out.println("--------------------------------------------------------------------");
    System.out.println("");
  }

  public void printGCHeap() {
    for (int i = 0; i < gcFreePtr; i++)
      System.out.println("[" + i + "] " + tag(gcWords[i]) + " " + value(gcWords[i]));
    System.out.println("gcFreePtr = " + gcFreePtr);
    System.out.println("gcCopiedPtr = " + gcCopiedPtr);
  }

  public void printHeap() {
    for (int i = 0; i < words.length; i++)
      System.out.println("[" + i + "] " + tag(words[i]) + ":" + value(words[i]));
  }

  public void printInstrBacktrace(PrintStream out, int frame) {
    out.println("Current stack frame instructions:");
    int codeBox = valueStack.ref(frame + FRAMECODEBOX);
    int instrs = codeBoxInstrs(codeBox);
    int constants = codeBoxConstants(codeBox);
    int index = valueStack.ref(frame + FRAMECODEINDEX);
    for (int i = 0; i < index; i++) {
      out.print("  " + instrToString(codeRef(instrs, i), constants));
      if (i + 1 == index)
        out.println(" <--- ");
      else out.println();
    }
  }

  public void printLine(PrintStream out) {
    for (int i = 0; i < 100; i++)
      out.print("-");
    out.println();
  }

  public void printStackElement(int i, int value) {
    if (value == -1)
      System.out.println("[" + i + "] -1");
    else {
      String string = valueToString(valueStack.ref(i));
      string = string.substring(0, Math.min(20, string.length()));
      System.out.println("[" + i + "] " + string);
    }
  }

  public void printStackToTop(int stackPtr) {
    int TOS = valueStack.getTOS();
    System.out.println("---------------- Stack from " + stackPtr + " to " + TOS + " ----------");
    if (stackPtr > TOS)
      for (int i = stackPtr; i >= TOS; i--)
        printStackElement(i, valueStack.ref(i));
    else for (int i = stackPtr; i <= TOS; i++)
      printStackElement(i, valueStack.ref(i));
  }

  public void printStats(PrintStream out) {

    // Print out statistics on the current state of the machine...

    long now = System.currentTimeMillis();
    float elapsedTimeMillis = (now - time0) / 1000F;
    out.println();
    out.println("Machine Statistics");
    out.println("------------------");
    out.println(calls + " function calls");
    out.println(dynamicLookupSteps + " dynamic lookup steps.");
    out.println(foreignFuns.size() + " foreign functions allocated.");
    out.println(foreignObjects.size() + " foreign objects allocated.");
    out.println(threadsAllocated() + " threads allocated.");
    out.println("operator table = " + valueToString(operatorTable));
    out.println("constructor table = " + valueToString(constructorTable));
    out.println("symbol table = " + valueToString(symbolTable));
    out.println("undo = [" + undo.undoStackSize() + "," + undo.undoCommandSize() + "]");
    out.println("redo = [" + undo.redoStackSize() + "," + undo.redoCommandSize() + "]");
    out.println(instrsPerformed + " instructions performed.");
    out.println("time since boot = " + elapsedTimeMillis + " sec");
    debugger.printStats(out);
    memStat(out);
  }

  public void memStat(PrintStream out) {

    // If you are having problems with memory management then you can use
    // this routine to print out the current state of the VM heap in
    // XMF words.

    int memSize = calc(words.length);
    int freeSize = calc(words.length - freePtr);
    out.println("Total memory:" + memSize);
    out.println("Free memory: " + freeSize);
    out.println("Stack: " + calc(valueStack.size()));
    out.println("TOS: " + valueStack.getTOS());
  }

  public static int calc(long value) {
    return (int) (value * 4) / (K * 1000);
  }

  public void printValueStack(PrintStream output) {
    for (int i = 0; i < valueStack.index; i++) {
      output.print(i + " : ");
      int value = valueStack.elements[i];
      if (!(value == -1))
        output.println(valueToString(value));
      else output.println("-1");
    }
  }

  public void write(int channel, int value) {

    // This method is supplied with machine words for an
    // output channel and a value. The value is written to the
    // output channel.

    if (isOutputChannel(channel))
      if (XOS.isDataOutputChannel(value(channel)))
        writeData(channel, value);
      else writeByte(channel, value);
    else if (isString(channel))
      writeMessage(channel, value, false);
    else throw new MachineError(TYPE, "Machine.writeChar: expecting an output channel " + valueToString(channel));
  }

  public void writeByte(int channel, int value) {
    switch (tag(value)) {
    case INT:
      XOS.write(value(channel), value(value));
      break;
    case STRING:
      for (int i = 0; i < stringLength(value); i++)
        XOS.write(value(channel), value(stringRef(value, i)));
      break;
    default:
      throw new MachineError(TYPE, "Unknown type to write to an output channel: " + valueToString(value));
    }
  }

  public int writeCommand(int client, int mname, int arity, boolean isCall) {

    // Called from the var arg foreign function writeCommand.
    // Works hard not to cons any unecessary data structures.

    clientName.setLength(0);
    for (int i = 0; i < stringLength(client); i++)
      clientName.append((char) stringRef(client, i));
    int result = client;
    xos.MessageClient messageClient = XOS.messageClient(clientName);
    if (messageClient != null) {
      Message message = XOS.allocMessage();
      message.setArity(value(arity));
      for (int i = 0; i < stringLength(mname); i++)
        message.appendNameChar((char) stringRef(mname, i));
      for (int i = 0; i < value(arity); i++) {
        int arg = frameLocal(3 + i);
        message.args[i] = messageValue(arg);
      }
      if (isCall)
        result = messageValue(messageClient.call(message));
      else messageClient.sendMessage(message);
      XOS.freeMessage(message);
    } else error(ERROR, "Unknown client: " + clientName);
    return result;
  }

  public xos.Value messageValue(int value) {
    switch (tag(value)) {
    case INT:
    case NEGINT:
      return XOS.allocValue(intValue(value));
    case BOOL:
      return XOS.allocValue(value == trueValue);
    case STRING:
      xos.Value v1 = XOS.allocValue();
      v1.type = XData.STRING;
      for (int i = 0; i < stringLength(value); i++)
        v1.appendChar((char) stringRef(value, i));
      return v1;
    case SYMBOL:
      xos.Value v2 = XOS.allocValue();
      v2.type = XData.STRING;
      for (int i = 0; i < stringLength(symbolName(value)); i++)
        v2.appendChar((char) stringRef(symbolName(value), i));
      return v2;
    case FLOAT:
      return XOS.allocValue(asFloat(value));
    case CONS:
      if (properList(value)) {
        xos.Value[] values = new xos.Value[consLength(value)];
        int index = 0;
        while (value != nilValue) {
          values[index++] = messageValue(consHead(value));
          value = consTail(value);
        }
        return new xos.Value(values);
      } else return XOS.allocValue(valueToString(value));
    case ARRAY:
      xos.Value[] values = new xos.Value[arrayLength(value)];
      for (int j = 0; j < arrayLength(value); j++)
        values[j] = messageValue(arrayRef(value, j));
      return XOS.allocValue(values);
    case NIL:
      return new xos.Value(new xos.Value[0]);
    default:
      return XOS.allocValue(valueToString(value));
    }
  }

  public float asFloat(int f) {
    return Float.parseFloat(valueToString(f));
  }

  public void writeData(int channel, int value) {
    switch (tag(value)) {
    case INT:
      XOS.writeInt(value(channel), value(value));
      break;
    case BOOL:
      XOS.writeBool(value(channel), value == trueValue);
      break;
    case STRING:
      XOS.startString(value(channel), stringLength(value));
      for (int i = 0; i < stringLength(value); i++)
        XOS.write(value(channel), stringRef(value, i));
      break;
    default:
      XOS.writeString(value(channel), valueToString(value));
    }
  }

  public int writeMessage(int client, int value, boolean isCall) {
    String name = valueToString(client);
    int result = client;
    if (XOS.isMessageClient(name)) {
      if (isArray(value)) {
        String messageName = valueToString(arrayRef(value, 0));
        int arity = arrayLength(value) - 1;
        Message message = XOS.allocMessage(messageName, arity);
        for (int i = 0; i < arity; i++) {
          int arg = arrayRef(value, i + 1);
          message.args[i] = messageValue(arg);
        }
        if (isCall)
          result = messageValue(XOS.call(name, message));
        else XOS.writeMessage(name, message);
        XOS.freeMessage(message);
      } else error(TYPE, "Message args must be arrays.", value, theClassVector);
    } else error(ERROR, "Unknown client: " + name);
    return result;
  }

  public int writePacket(int client, int packet, int size) {

    // Called from the var arg foreign function writeCommand.
    // Works hard not to cons any unecessary data structures.

    clientName.setLength(0);
    for (int i = 0; i < stringLength(client); i++)
      clientName.append((char) stringRef(client, i));
    int result = client;
    xos.MessageClient messageClient = XOS.messageClient(clientName);
    if (messageClient != null) {
      if (isBuffer(packet)) {
        MessagePacket pack = XOS.allocPacket(value(size));
        for (int i = 0; i < value(size); i++) {
          int message = bufferRef(packet, i);
          xos.Value mess = messageValue(message);
          if (mess.type == XData.VECTOR) {
            String messageName = mess.values[0].strValue();
            int arity = mess.values.length - 1;
            Message m = XOS.allocMessage(messageName, arity);
            for (int a = 0; a < arity; a++) {
              m.args[a] = mess.values[a + 1];
            }
            pack.addMessage(i, m);
          } else error(TYPE, "A message packet must contain messages of type vector.", message, theClassVector);
        }
        messageClient.sendPacket(pack);
        XOS.freePacket(pack);
      } else error(TYPE, "Packet must be a vector.", packet, theClassVector);

    } else error(ERROR, "Unknown client: " + clientName);
    return result;
  }

  public boolean terminated() {

    // There are no further active threads in the machine and therefore
    // no further activity can be performed by the machine.

    return threads == null;
  }

  public boolean terminatedThread() {

    // The machine is complete when there is no current frame.
    // Note that there may be other threads that are active.
    // The current threads should be removed.

    return currentFrame == -1;
  }

  // Saving information occurs in two ways: serializing data and
  // saving a heap image. The serializer encodes information using
  // a stack language that is interpreted by a loader. Saving an
  // image just writes out the heap and the required associated
  // data structures...

  public int save(int value, OutputStream out) {
    throw new Error("Machine.save/2 is deprecated");
  }

  public int save(int value, OutputStream out, int nameSpaces) {

    // Save the supplied value to the output stream using the
    // element serializer. The name spaces are those that are
    // expected to be present when the value is loaded and
    // therefore elements contained in these name-spaces can be
    // saved as named referenced for lookup by the loader...

    try {
      BufferedOutputStream buf = new BufferedOutputStream(out);
      serializer.reset();
      serializer.setNameSpaces(nameSpaces);
      serializer.save(buf, value);
      buf.flush();
      return value;
    } catch (Throwable t) {
      System.out.println(t);
      t.printStackTrace();
      return value;
    }
  }

  public int save(int value, String fileName) {
    throw new Error("Machine.save/2 is deprecated");
  }

  public int save(int value, String fileName, int nameSpaces) {
    try {
      FileOutputStream fout = new FileOutputStream(getFile(fileName));
      save(value, fout, nameSpaces);
      return value;
    } catch (Throwable t) {
      System.out.println(t);
      t.printStackTrace();
      return value;
    }
  }

  public void save(String fileName) {

    // Save a VM image in a file...

    new ImageSerializer(this).serialize(fileName);
  }

  public void legacySave(String fileName) {

    // Saves a VM image and associated structures in the file.
    // Old version that is much slower...

    try {
      System.out.print("[ Save " + fileName);
      FileOutputStream fout = new FileOutputStream(getFile(fileName));
      BufferedOutputStream bout = new BufferedOutputStream(fout);
      GZIPOutputStream zout = new GZIPOutputStream(bout);
      ObjectOutputStream out = new ObjectOutputStream(zout);
      saveHeader(out);
      saveHeap(out);
      saveStack(out);
      saveTables(out);
      saveConstants(out);
      out.flush();
      out.close();
    } catch (IOException ioe) {
      throw new MachineError(SAVEERR, ioe.getMessage());
    } finally {
      System.out.println(" ]");
    }
  }

  public void saveBacktrace() {
    saveBacktrace(currentFrame);
  }

  public void saveBacktrace(int frame) {
    saveBacktrace(frame, backtraceDumpFile());
  }

  public String backtraceDumpFile() {
    return backtraceDumpFile + new Date().getTime() + ".txt";
  }

  public void saveBacktrace(int frame, String file) {
    try {
      FileOutputStream fout = new FileOutputStream(file);
      BufferedOutputStream bout = new BufferedOutputStream(fout);
      PrintStream pout = new PrintStream(bout);
      printBacktrace(pout, frame);
      new Exception().printStackTrace(pout);
      pout.close();
    } catch (IOException ioe) {
      System.err.println(ioe.toString());
    }
  }

  void saveConstants(ObjectOutputStream out) {
    try {
      out.writeInt(clientInterface);
      out.writeInt(foreignTypeMapping);
      out.writeInt(foreignMOPMapping);
      out.writeInt(symbolTable);
      out.writeInt(emptyArray);
      out.writeInt(emptySet);
      out.writeInt(theTypeBoolean);
      out.writeInt(theClassCompiledOperation);
      out.writeInt(theTypeInteger);
      out.writeInt(theTypeFloat);
      out.writeInt(theTypeString);
      out.writeInt(theTypeNull);
      out.writeInt(theTypeSeqOfElement);
      out.writeInt(theTypeSetOfElement);
      out.writeInt(theClassElement);
      out.writeInt(theClassForeignObject);
      out.writeInt(theClassForeignOperation);
      out.writeInt(theClassForwardRef);
      out.writeInt(theClassException);
      out.writeInt(theClassClass);
      out.writeInt(theClassPackage);
      out.writeInt(theClassBind);
      out.writeInt(theClassCodeBox);
      out.writeInt(theClassTable);
      out.writeInt(theClassThread);
      out.writeInt(theTypeSeq);
      out.writeInt(theTypeSet);
      out.writeInt(theClassDataType);
      out.writeInt(theClassDaemon);
      out.writeInt(theClassBuffer);
      out.writeInt(theClassVector);
      out.writeInt(theClassSymbol);
      out.writeInt(theSymbolAttributes);
      out.writeInt(theSymbolInit);
      out.writeInt(theSymbolMachineInit);
      out.writeInt(theSymbolType);
      out.writeInt(theSymbolDefault);
      out.writeInt(theSymbolOperations);
      out.writeInt(theSymbolParents);
      out.writeInt(theSymbolName);
      out.writeInt(theSymbolArity);
      out.writeInt(theSymbolOwner);
      out.writeInt(theSymbolContents);
      out.writeInt(theSymbolInvoke);
      out.writeInt(theSymbolDot);
      out.writeInt(theSymbolFire);
      out.writeInt(theSymbolValue);
      out.writeInt(theSymbolDocumentation);
      out.writeInt(theSymbolHead);
      out.writeInt(theSymbolTail);
      out.writeInt(theSymbolIsEmpty);
      out.writeInt(theSymbolNewListener);
      out.writeInt(operatorTable);
      out.writeInt(constructorTable);
      out.writeInt(newListenersTable);
      out.writeInt(globalDynamics);
    } catch (IOException ioe) {
      throw new MachineError(SAVEERR, ioe.getMessage());
    }
  }

  public void saveCurrentThread() {
    if (threads != null) {
      threads.setCurrentFrame(currentFrame);
      threads.setOpenFrame(openFrame);
      threads.setStack(valueStack);
    }
  }

  void saveHeader(ObjectOutputStream out) {
    try {
      out.writeObject(header);
    } catch (IOException ioe) {
      throw new MachineError(SAVEERR, ioe.getMessage());
    }
  }

  void saveHeap(ObjectOutputStream out) {
    try {
      out.writeObject(words);
      out.writeInt(freePtr);
    } catch (IOException ioe) {
      throw new MachineError(SAVEERR, ioe.getMessage());
    }

  }

  public int saveIndex(int word) {
    return value(firstWord(word));
  }

  void saveStack(ObjectOutputStream out) {
    try {
      out.writeObject(valueStack);
      out.writeInt(currentFrame);
      out.writeInt(openFrame);
      threads.setCurrentFrame(currentFrame);
      threads.setOpenFrame(openFrame);
      out.writeObject(threads);
    } catch (IOException ioe) {
      throw new MachineError(SAVEERR, ioe.getMessage());
    }
  }

  void saveTables(ObjectOutputStream out) {
    try {
      out.writeObject(foreignFuns);
    } catch (IOException ioe) {
      throw new MachineError(SAVEERR, ioe.getMessage());
    }
  }

  public void load(String fileName) {

    // Load the image in the supplied filename and replace
    // the current image and associated structures...

    if (!getFile(fileName).exists()) {
      System.out.println("Image file does not exist " + fileName);
      exitAbnormal();
    }
    new ImageSerializer(this).inflate(fileName);
  }

  public void legacyLoad(String fileName) {

    // This is a much slower image loader that used to be
    // used. Left in until the new image serializer beds down...

    try {
      if (!getFile(fileName).exists()) {
        System.out.println("Image file does not exist " + fileName);
        exitAbnormal();
      }
      System.out.print("[ Load " + fileName);
      java.io.FileInputStream fin = new java.io.FileInputStream(getFile(fileName));
      GZIPInputStream zin = new GZIPInputStream(fin);
      ObjectInputStream in = new ObjectInputStream(zin);
      loadHeader(in);
      loadHeap(in);
      loadStack(in);
      loadTables(in);
      loadConstants(in);
      in.close();
    } catch (IOException ioe) {
      throw new MachineError(LOADERR, ioe.getMessage());
    } finally {
      System.out.println(" ]");
    }
  }

  public int loadBin(InputStream in) {

    // Loads a binary file and turns it into a function...

    try {
      int codeBox = consHead(serializer.load(in));
      in.close();
      return codeBoxToFun(codeBox, 0, globalDynamics);
    } catch (IOException e) {
      e.printStackTrace();
      return undefinedValue;
    }
  }

  public int loadBin(String fileName) {

    // Loads a binary file and turns it into a function.

    int codeBox = consHead(serializer.load(getFile(fileName)));
    return codeBoxToFun(codeBox, 0, globalDynamics);
  }

  void loadConstants(ObjectInputStream in) {
    try {
      clientInterface = in.readInt();
      foreignTypeMapping = in.readInt();
      foreignMOPMapping = in.readInt();
      symbolTable = in.readInt();
      emptyArray = in.readInt();
      emptySet = in.readInt();
      theTypeBoolean = in.readInt();
      theClassCompiledOperation = in.readInt();
      theTypeInteger = in.readInt();
      theTypeFloat = in.readInt();
      theTypeString = in.readInt();
      theTypeNull = in.readInt();
      theTypeSeqOfElement = in.readInt();
      theTypeSetOfElement = in.readInt();
      theClassElement = in.readInt();
      theClassForeignObject = in.readInt();
      theClassForeignOperation = in.readInt();
      theClassForwardRef = in.readInt();
      theClassException = in.readInt();
      theClassClass = in.readInt();
      theClassPackage = in.readInt();
      theClassBind = in.readInt();
      theClassCodeBox = in.readInt();
      theClassTable = in.readInt();
      theClassThread = in.readInt();
      theTypeSeq = in.readInt();
      theTypeSet = in.readInt();
      theClassDataType = in.readInt();
      theClassDaemon = in.readInt();
      theClassBuffer = in.readInt();
      theClassVector = in.readInt();
      theClassSymbol = in.readInt();
      theSymbolAttributes = in.readInt();
      theSymbolInit = in.readInt();
      theSymbolMachineInit = in.readInt();
      theSymbolType = in.readInt();
      theSymbolDefault = in.readInt();
      theSymbolOperations = in.readInt();
      theSymbolParents = in.readInt();
      theSymbolName = in.readInt();
      theSymbolArity = in.readInt();
      theSymbolOwner = in.readInt();
      theSymbolContents = in.readInt();
      theSymbolInvoke = in.readInt();
      theSymbolDot = in.readInt();
      theSymbolFire = in.readInt();
      theSymbolValue = in.readInt();
      theSymbolDocumentation = in.readInt();
      theSymbolHead = in.readInt();
      theSymbolTail = in.readInt();
      theSymbolIsEmpty = in.readInt();
      theSymbolNewListener = in.readInt();
      operatorTable = in.readInt();
      constructorTable = in.readInt();
      newListenersTable = in.readInt();
      globalDynamics = in.readInt();
    } catch (IOException ioe) {
      throw new MachineError(LOADERR, ioe.getMessage());
    }
  }

  void loadHeader(ObjectInputStream in) {
    try {
      header = (Header) in.readObject();
    } catch (IOException ioe) {
      throw new MachineError(LOADERR, ioe.getMessage());
    } catch (ClassNotFoundException ioe) {
      throw new MachineError(LOADERR, ioe.getMessage());
    }
  }

  void loadHeap(ObjectInputStream in) {
    try {
      words = (int[]) in.readObject();
      freePtr = in.readInt();
      if (heapSize != words.length) if (heapSize < freePtr + K) {
        heapSize = words.length;
        gcWords = new int[heapSize];
      } else {
        int[] newWords = new int[heapSize];
        for (int i = 0; i < freePtr; i++)
          newWords[i] = words[i];
        words = newWords;
      }
    } catch (IOException ioe) {
      throw new MachineError(LOADERR, ioe.getMessage());
    } catch (ClassNotFoundException cnf) {
      throw new MachineError(LOADERR, cnf.getMessage());
    }
  }

  public void loadImage() {

    // If an image file has been specified then load the image.
    // We may have specified an arg to return as the result of
    // the original save image call.

    if (imageFile != null) {
      load(imageFile);
      setDynamicValue(mkSymbol("Kernel_stdout"), mkImmediate(INPUT_CHANNEL, 0));
      setDynamicValue(mkSymbol("Kernel_stdin"), mkImmediate(OUTPUT_CHANNEL, 0));
      XOS.imageLoaded();
      valueStack.push(imageArgs());
    }
  }

  public int setDynamicValue(int name, int value) {
    int dynamics = frameDynamics();
    while (dynamics != nilValue) {
      dynamicLookupSteps++;
      int cell = consHead(dynamics);
      dynamics = consTail(dynamics);
      switch (dynamicCellType(cell)) {
      case DYNAMIC_VALUE:
        int binding = dynamicCellValue(cell);
        if (dynamicBindingName(binding) == name) {
          dynamicBindingSetValue(binding, value);
          return value;
        }
        break;
      case DYNAMIC_TABLE:
        int table = dynamicCellValue(cell);
        if (hashTableHasKey(table, name)) {
          hashTablePut(table, name, value);
          return value;
        }
        break;
      default:
        throw new MachineError(TYPE, "Machine.detDynamicValue: Unknown type of dynamic cell: " + valueToString(cell));
      }
    }
    return -1;
  }

  public void loadInit() {
    // Initialise the machine from the given binary file.
    if (initFile != null) {
      int codeBox = consHead(serializer.load(getFile(initFile)));
      init(codeBox);
    }
  }

  // Foreign functions are maintained in a table. New foreign
  // functions are added to the table and maintained as an index
  // from the heap into the table.

  void loadStack(ObjectInputStream in) {
    try {
      valueStack = (ValueStack) in.readObject();
      currentFrame = in.readInt();
      openFrame = in.readInt();
      threads = (Thread) in.readObject();
      if (stackSize != valueStack.size()) if (stackSize < valueStack.size()) {
        System.out.println("Cannot load stack, increase requested size " + stackSize + " to at least " + valueStack.size());
        System.exit(-1);
      } else {
        ValueStack newStack = new ValueStack(stackSize);
        valueStack.copyInto(newStack);
        valueStack = newStack;
      }
    } catch (IOException ioe) {
      throw new MachineError(LOADERR, ioe.getMessage());
    } catch (ClassNotFoundException cnf) {
      throw new MachineError(LOADERR, cnf.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  void loadTables(ObjectInputStream in) {
    try {
      foreignFuns = (Stack<ForeignFun>) in.readObject();
    } catch (IOException ioe) {
      throw new MachineError(LOADERR, ioe.getMessage());
    } catch (ClassNotFoundException cnf) {
      throw new MachineError(LOADERR, cnf.getMessage());
    }
  }

  public int loadValue(InputStream in) throws MachineError {
    if (needsGC()) gc();
    return serializer.load(in);
  }

  public int loadValue(String fileName) throws MachineError {
    if (needsGC()) gc();
    return serializer.load(getFile(fileName));
  }

  int loadWord(InputStream in) {
    try {
      int i1 = in.read();
      int i2 = in.read();
      int i3 = in.read();
      int i4 = in.read();
      return (i4 << 24) | (i3 << 16) | (i2 << 8) | i1;
    } catch (IOException ioe) {
      throw new MachineError(LOADERR, ioe.getMessage());
    }
  }

  // Input methods that read input channels. In general input
  // is performed by the underlying operating system...

  public int readArray(int in, int array) {

    // Read input into an array and return the number of
    // bytes that were read...

    int length = arrayLength(array);
    int index = 0;
    while (XOS.ready(value(in)) && !XOS.eof(value(in)) && index < length) {
      int c = XOS.read(value(in));
      arraySet(array, index++, mkInt(c));
    }
    return mkInt(index);
  }

  public int readBuffer(int in, int buffer) {

    // Read bytes into a buffer and return the number
    // of bytes that were read..

    int chars = 0;
    while (XOS.ready(value(in)) && !XOS.eof(value(in))) {
      int c = XOS.read(value(in));
      chars++;
      bufferSet(buffer, bufferSize(buffer), mkInt(c));
    }
    return mkInt(chars);
  }

  public void readChannel(int in) {
    int c = XOS.read(value(in));
    valueStack.push(mkInt(c));
  }

  public void readDataInputChannel(int in) {
    int type = XOS.read(value(in));
    DChannel din = XOS.dataInputStream(value(in));
    switch (type) {
    case DataInputStream.BOOL:
      valueStack.push(din.boolValue ? trueValue : falseValue);
      break;
    case DataInputStream.INT:
      valueStack.push(mkInt(din.intValue));
      break;
    case DataInputStream.STRING:
      StringBuffer s = din.stringValue;
      int length = s.length();
      int string = mkString(length);
      for (int i = 0; i < length; i++)
        stringSet(string, i, s.charAt(i));
      valueStack.push(string);
      break;
    default:
      System.out.println("Unknown type of data from input channel: " + type);
      exitAbnormal();
    }
  }

  public void readMessageClient(String name) {
    xos.Message message = XOS.readMessage(name);
    int array = mkArray(message.arity + 1);
    arraySet(array, 0, mkString(message.name()));
    for (int i = 0; i < message.arity; i++) {
      xos.Value value = message.args[i];
      arraySet(array, i + 1, messageValue(value));
    }
    valueStack.push(array);
  }

  public int readNextToken(int index) {
    XOS.nextToken(index);
    if (XOS.tokenError(index))
      throw new MachineError(ERROR, XOS.tokenErrorMessage(index));
    else {
      int type = mkInt(XOS.tokenType(index));
      int rawChars = mkString(XOS.rawChars(index));
      int posValue = mkInt(XOS.posValue(index));
      int lineCount = mkInt(XOS.lineCount(index));
      int charCount = mkInt(XOS.charCount(index));
      switch (XOS.tokenType(index)) {
      case TChannel.EOF:
        return undefinedValue;
      case TChannel.INT:
        return token(type, mkInt(XOS.tokenValue(index)), posValue, lineCount, charCount, rawChars);
      default:
        return token(type, mkString(XOS.token(index)), posValue, lineCount, charCount, rawChars);
      }
    }
  }

  public void readReadyChannel(int in) {
    if (XOS.isTokenChannel(value(in)))
      readTokenChannel(in);
    else if (XOS.isDataInputChannel(value(in)))
      readDataInputChannel(in);
    else readChannel(in);
  }

  public int readString(int in) {
    StringBuffer s = new StringBuffer();
    int c;
    while ((c = XOS.read(value(in))) != -1)
      s.append((char) c);
    return mkString(s.toString());
  }

  public void readTokenChannel(int in) {
    int t = nextToken(in);
    valueStack.push(t);
  }

  public int readVector(int in, int array) {
    switch (tag(array)) {
    case ARRAY:
      return readArray(in, array);
    case BUFFER:
      return readBuffer(in, array);
    default:
      throw new MachineError(TYPE, "readVector: unknown target ", array, theClassVector);
    }
  }

  public String valueToString(int word) {

    String string = valueToString(word, 0);
    return string;

  }

  public String valueToString(int word, int depth) {

    // Translate a value into a string.

    if (depth >= maxPrintDepth)
      return "...";
    else switch (tag(word)) {
    case UNDEFINED:
      return "null";
    case ARRAY:
      return arrayToString(word, depth);
    case BUFFER:
      return bufferToString(word, depth);
    case CODE:
      return codeToString(word);
    case INT:
    case NEGINT:
      return intToString(word);
    case BIGINT:
      return asBigInteger(word).toString();
    case STRING:
      return stringToString(word);
    case CODEBOX:
      return codeBoxToString(word, depth);
    case BOOL:
      return boolToString(word);
    case OBJ:
      return objToString(word, depth);
    case FUN:
      return funToString(word);
    case FOREIGNFUN:
      return foreignFunToString(word);
    case FOREIGNOBJ:
      return foreignObjToString(word);
    case CONT:
      return contToString(word);
    case CONS:
      return consToString(word, depth);
    case NIL:
      return nilToString();
    case SYMBOL:
      return symbolToString(word);
    case STRINGLENGTH:
      return "StringLength(" + value(word) + ")";
    case CODELENGTH:
      return "CodeLength(" + value(word) + ")";
    case SET:
      return setToString(word, depth);
    case INPUT_CHANNEL:
      return inputChannelToString(word);
    case OUTPUT_CHANNEL:
      return outputChannelToString(word);
    case HASHTABLE:
      return hashTableToString(word);
    case FLOAT:
      return floatToString(word);
    case CLIENT:
      return clientToString(word);
    case THREAD:
      return threadToString(word);
    case DAEMON:
      return daemonToString(word);
    default:
      return "valueToString: unknown tag " + tag(word);
    }
  }

  public String instrToString(int instr, int constants) {

    // Translate an instruction to a string.

    switch (tag(instr)) {
    case MKSEQ:
      return "MKSEQ " + value(instr);
    case MKSET:
      return "MKSET " + value(instr);
    case MKCONS:
      return "MKCONS";
    case PUSHINT:
      return "PUSHINT " + value(instr);
    case PUSHTRUE:
      return "PUSHTRUE";
    case PUSHFALSE:
      return "PUSHFALSE";
    case PUSHSTR:
      return "PUSHSTR " + valueToString(arrayRef(constants, value(instr)));
    case RETURN:
      return "RETURN";
    case ADD:
      return "ADD";
    case SUB:
      return "SUB";
    case MUL:
      return "MUL";
    case DIV:
      return "DIV";
    case GRE:
      return "GRE";
    case LESS:
      return "LESS";
    case EQL:
      return "EQL";
    case AND:
      return "AND";
    case OR:
      return "OR";
    case NOT:
      return "NOT";
    case DOT:
      return "DOT " + valueToString(arrayRef(constants, value(instr)));
    case DOTSELF:
      return "DOTSELF " + valueToString(arrayRef(constants, value(instr)));
    case DOTLOCAL:
      return "DOTLOCAL " + valueToString(arrayRef(constants, (byte3(instr) << 8) | byte2(instr))) + " " + byte1(instr);
    case SELF:
      return "SELF " + value(instr);
    case SKPF:
      return "SKPF " + value(instr);
    case SKP:
      return "SKP " + value(instr);
    case DYNAMIC:
      return "DYNAMIC(" + value(instr) + ") " + valueToString(arrayRef(constants, value(instr)));
    case SETDYN:
      return "SETDYN " + valueToString(arrayRef(constants, value(instr)));
    case BINDDYN:
      return "BINDDYN " + valueToString(arrayRef(constants, value(instr)));
    case UNBINDDYN:
      return "UNBINDDYN " + valueToString(arrayRef(constants, value(instr)));
    case MKFUN:
      return "MKFUN " + byte3(instr) + " " + byte2(instr) + " " + byte1(instr);
    case MKFUNE:
      return "MKFUNE " + byte3(instr) + " " + byte2(instr);
    case LOCAL:
      return "LOCAL " + value(instr);
    case STARTCALL:
      return "STARTCALL";
    case ENTER:
      return "ENTER " + value(instr);
    case TAILENTER:
      return "TAILENTER " + value(instr);
    case SEND:
      return "SEND " + byte2(instr) + " " + valueToString(arrayRef(constants, byte1(instr)));
    case SENDSELF:
      return "SENDSELF " + byte2(instr) + " " + valueToString(arrayRef(constants, byte1(instr)));
    case SENDLOCAL:
      return "SENDLOCAL " + byte3(instr) + " " + valueToString(arrayRef(constants, byte2(instr))) + byte1(instr);
    case TAILSEND:
      return "TAILSEND " + byte2(instr) + " " + valueToString(arrayRef(constants, byte1(instr)));
    case SEND0:
      return "SEND0 " + valueToString(arrayRef(constants, value(instr)));
    case TAILSEND0:
      return "TAILSEND0 " + valueToString(arrayRef(constants, value(instr)));
    case SUPER:
      return "SUPER " + value(instr);
    case TAILSUPER:
      return "TAILSUPER " + value(instr);
    case SETLOC:
      return "SETLOC " + value(instr);
    case POP:
      return "POP";
    case GLOBAL:
      return "GLOBAL " + byte3(instr) + " " + byte2(instr);
    case SETSLOT:
      return "SETSLOT " + valueToString(arrayRef(constants, value(instr)));
    case SETSELFSLOT:
      return "SETSELFSLOT " + valueToString(arrayRef(constants, value(instr)));
    case SETLOCALSLOT:
      return "SETLOCALSLOT " + valueToString(arrayRef(constants, (byte2(instr) << 8) | byte1(instr))) + " " + byte3(instr);
    case SETGLOB:
      return "SETGLOB " + byte3(instr) + " " + byte2(instr);
    case MKARRAY:
      return "MKARRAY " + value(instr);
    case NAMESPACEREF:
      return "NAMESPACEREF " + byte3(instr) + " " + valueToString(arrayRef(constants, (byte2(instr))));
    case HEAD:
      return "HEAD";
    case TAIL:
      return "TAIL";
    case SIZE:
      return "SIZE";
    case DROP:
      return "DROP";
    case ISEMPTY:
      return "ISEMPTY";
    case INCLUDES:
      return "INCLUDES";
    case EXCLUDING:
      return "EXCLUDING";
    case INCLUDING:
      return "INCLUDING";
    case SEL:
      return "SEL";
    case UNION:
      return "UNION";
    case ASSEQ:
      return "ASSEQ";
    case AT:
      return "AT";
    case SKPBACK:
      return "SKPBACK " + value(instr);
    case NULL:
      return "NULL";
    case OF:
      return "OF";
    case THROW:
      return "THROW";
    case TRY:
      return "TRY" + byte2(instr) + " " + byte1(instr);
    case ISKINDOF:
      return "ISKINDOF";
    case SOURCEPOS:
      return "SOURCEPOS line:" + ((byte3(instr) << 8) | byte2(instr)) + " char:" + byte1(instr);
    case GETELEMENT:
      return "GETELEMENT " + valueToString(arrayRef(constants, byte1(instr)));
    case SETHEAD:
      return "SETHEAD";
    case SETTAIL:
      return "SETTAIL";
    case READ:
      return "READ";
    case ACCEPT:
      return "ACCEPT";
    case ARRAYREF:
      return "ARRAYREF";
    case ARRAYSET:
      return "ARRAYSET";
    case TABLEGET:
      return "TABLEGET";
    case TABLEPUT:
      return "TABLEPUT";
    case NOOP:
      return "NOOP";
    case SLEEP:
      return "SLEEP";
    case CONST:
      return "CONST " + valueToString(arrayRef(constants, value(instr)));
    case SETLOCPOP:
      return "SETLOCPOP " + value(instr);
    case DISPATCH:
      return "DISPATCH " + valueToString(arrayRef(constants, value(instr)));
    case INCSELFSLOT:
      return "INCSELFSLOT " + valueToString(arrayRef(constants, value(instr)));
    case DECSELFSLOT:
      return "DECSELFSLOT " + valueToString(arrayRef(constants, value(instr)));
    case INCLOCAL:
      return "INCLOCAL " + value(instr);
    case DECLOCAL:
      return "DECLOCAL " + value(instr);
    case ADDLOCAL:
      return "ADDLOCAL " + value(instr);
    case SUBLOCAL:
      return "SUBLOCAL " + value(instr);
    case PREPEND:
      return "PREPEND";
    case ENTERDYN:
      return "ENTERDYN " + byte2(instr) + " " + valueToString(arrayRef(constants, byte1(instr)));
    case TAILENTERDYN:
      return "TAILENTERDYN " + byte2(instr) + " " + valueToString(arrayRef(constants, byte1(instr)));
    case LOCALHEAD:
      return "LOCALHEAD " + value(instr);
    case LOCALTAIL:
      return "LOCALTAIL " + value(instr);
    case LOCALASSEQ:
      return "LOCALASSEQ " + value(instr);
    case LOCALISEMPTY:
      return "LOCALISEMPTY " + value(instr);
    case LOCALREFPOS:
      return "LOCALREFPOS " + byte3(instr) + " " + ((byte2(instr) << 8) + byte1(instr));
    case DYNREFPOS:
      return "DYNREFPOS " + valueToString(arrayRef(constants, byte3(instr))) + " " + ((byte2(instr) << 8) + byte1(instr));
    case ASSOC:
      return "ASSOC";
    case RETDOTSELF:
      return "RETDOTSELF " + valueToString(arrayRef(constants, value(instr)));
    default:
      return "<Unknown instruction " + tag(instr) + ">";
    }
  }

  public int fork(int name, int fun) {
    return fork(name, fun, nilValue, new ThreadInitiator(), null);
  }

  public int fork(int name, int fun, int args, ThreadInitiator init, ClassLoader loader) {

    // Creates a new thread but does not make it current.
    // The function is expecting the supplied arguments.

    // Save the current values...

    ValueStack cs = valueStack;
    int cf = currentFrame;
    int of = openFrame;

    // Create a new thread...

    ValueStack newStack = new ValueStack(STACKSIZE);
    String s = valueToString(name);
    Thread newThread = threads.add(new Thread(s, newStack, 0, -1, init, loader));
    int arity = consLength(args);

    // Initialise the new thread (using operations
    // that expect the current stack frame to be updated)...

    valueStack = newStack;
    currentFrame = -1;
    openFrame = -1;
    initStack(newStack, funCode(fun), arity);
    currentFrame = 0;
    for (int i = 0; args != nilValue; i++) {
      setFrameLocal(i, consHead(args));
      args = consTail(args);
    }
    setFrameGlobals(funGlobals(fun));
    setFrameDynamics(funDynamics(fun));
    setFrameSelf(funSelf(fun));
    setFrameSuper(funSupers(fun));

    // Restore the current values...

    valueStack = cs;
    currentFrame = cf;
    openFrame = of;
    XOS.schedule(newThread);

    // Return the thread as a data value...

    return mkThread(newThread.id());

  }

  public int getClientInterface() {

    // Implements Kernel_clientInterface...

    return clientInterface;
  }

  public int asString(int value) {

    // Implements Kernel_asString...

    switch (tag(value)) {
    case STRING:
      return value;
    case CONS:
      return consAsString(value);
    case ARRAY:
      return arrayAsString(value);
    case BUFFER:
      if (bufferAsString(value) == trueValue)
        return bufAsString(value);
      else return mkString(valueToString(value));
    default:
      return mkString(valueToString(value));
    }
  }

  public int available(int inputChannel) {

    // Implements Kernel_available...

    if (isInputChannel(inputChannel))
      return XOS.available(value(inputChannel));
    else throw new MachineError(ERROR, "Machine.available expects an input channel " + valueToString(inputChannel));

  }

  public void bindDynamic(int name, int value) {
    setFrameDynamics(mkCons(mkDynamicBinding(name, value), frameDynamics()));
  }

  public int call(int channel, int value) {

    // Only used with message channels.

    return writeMessage(channel, value, true);

  }

  public void callcc(int fun) {

    // Implements Kernel_callcc...

    if (!isFun(fun)) throw new Error("Machine.callcc: expecting a function.");
    if (funArity(fun) != 1) throw new Error("Machine.callcc: function arity must be 1.");
    // Get rid of the callcc frame...
    pushStack(undefinedValue);
    popFrame();
    popStack();
    // Create a new continuation.
    int cont = newCont();
    // Start a new frame for the function supplied with the new
    // continuation.
    openFrame();
    // Set up the call.
    pushStack(cont);
    pushStack(fun);
    // Enter the body of fun.
    enter(1);
  }

  public int newCont() {

    // A new continuation is created in the context of a callcc
    // stack frame. For example:
    // f = fun(...) let g = callcc(fun(value) ... end) in ...
    // The continuation captured must return from f when invoked...

    int cont = mkCont(valueStack.getTOS());
    contSetCurrentFrame(cont, mkInt(currentFrame));
    contSetOpenFrame(cont, mkInt(openFrame));
    for (int i = 0; i < valueStack.getTOS(); i++)
      contSet(cont, i, valueStack.ref(i));
    return cont;
  }

  public boolean checkoutLicense() {
    return true;
    // return LicenseManager.checkoutLicense(header,imageArgs);
  }

  public synchronized void clientSend(String name, Object[] javaArgs, ThreadInitiator init, final ClientResult handler, ClassLoader classLoader) {

    // Call the named client function and return the result to the handler.
    // The call is asynchronous. It up to the caller to wait for the result
    // if necessary...

    int str = mkString(name);
    int xmfArgs = nilValue;
    ClassLoader currentLoader = XJ.getLoader();
    XJ.setLoader(classLoader);
    for (int i = javaArgs.length - 1; i >= 0; i--)
      xmfArgs = mkCons(xjava.JavaTranslator.mapJavaValue(this, javaArgs[i]), xmfArgs);
    XJ.setLoader(currentLoader);
    final Machine self = this;
    if (hashTableHasKey(clientInterface, str)) {
      int fun = hashTableGet(clientInterface, str);
      int t = fork(str, fun, xmfArgs, init, classLoader);
      Thread thread = getThread(t);
      thread.monitor(new ThreadMonitor() {
        public void threadDies(Thread thread, int result) {
          try {
            Object value = xjava.JavaTranslator.mapXMFValue(self, java.lang.Object.class, result);
            handler.result(value);
          } catch (Throwable t) {
            handler.error(t.getMessage());
          }
        }
      });
    } else handler.error("No client handler for " + name);

  }

  public Thread getThread(int thread) {

    // Find and return the thread with the supplied id...

    Thread t = threads;
    do {
      if (t.id() == threadId(thread))
        return t;
      else t = t.next();
    } while (t != threads);
    return null;
  }

  public boolean hasClient(String name) {

    // External query that requests the existence of a client
    // handler by a *real* client of the VM...

    return hashTableHasKey(clientInterface, mkString(name));
  }

  public int clientInputChannel(int value) {

    // Implements Kernel_clientInputChannel..

    if (isString(value)) {
      String name = valueToString(value);
      int index = XOS.inputChannel(name);
      if (index != -1)
        return mkInputChannel(index);
      else return -1;
    } else return -1;
  }

  public int clientOutputChannel(int value) {

    // Implements Kernel_clientOutputChannel..

    if (isString(value)) {
      String name = valueToString(value);
      int index = XOS.outputChannel(name);
      if (index != -1)
        return mkOutputChannel(index);
      else return error(ERROR, "No client named " + name);
    } else return error(TYPE, "Client name should be a string.", value, theTypeString);
  }

  public String clientToString(int client) {
    return "<Client>";
  }

  public void close(int channel) {

    // Implements kernel_close...

    switch (tag(channel)) {
    case INPUT_CHANNEL:
      XOS.closeInputChannel(value(channel));
      break;
    case OUTPUT_CHANNEL:
      XOS.flush(value(channel));
      XOS.closeOutputChannel(value(channel));
      break;
    }
  }

  public void closeAll() {

    // Implements kernel_closeAll...
    // Close all connections prior to shutting down.

    XOS.closeAll();
  }

  public void closeZipInputChannel(int fileName) {

    // Implements Kernel_closeZipInputChannel...

    String name = valueToString(fileName);
    ZipFile zipFile = zipFile(fileName);
    if (zipFile != null) {
      zipFiles.remove(name);
      try {
        zipFile.close();
      } catch (IOException e) {
        throw new MachineError(ERROR, e.getMessage());
      }
    }
  }

  public int currentFrame() {
    return currentFrame;
  }

  public Thread currentThread() {
    return threads;
  }

  public boolean eof(int inputChannel) {

    // Implements Kernel_eof...

    if (isInputChannel(inputChannel))
      return XOS.eof(value(inputChannel));
    else throw new MachineError(ERROR, "Machine.eof expects an input channel " + valueToString(inputChannel));
  }

  public int error(int id, String message, int... dataElements) {

    // Causes the machine to throw an exception. The exception
    // is created as an instance of the MachineException class
    // and then the stack frames are discarded until a handle is
    // found. The handler is supplied with the exception...

    int exception = mkException(message);
    int data = nilValue;
    for (int i = dataElements.length - 1; i >= 0; i--)
      data = mkCons(dataElements[i], data);
    objSetAttValue(exception, mkSymbol("id"), mkInt(id));
    objSetAttValue(exception, mkSymbol("data"), data);
    valueStack.push(exception);
    throwIt();
    return exception;
  }

  public void exit(int value) {

    // Implements Kernel_exit...

    // LicenseManager.checkinLicense();
    XOS.shutDown(value(value));
  }

  public void exitAbnormal() {
    // LicenseManager.checkinLicense();
    System.exit(0);
  }

  // A foreign object is an object that responds to '.'.
  // The machine uses the java.lang.reflect interface to
  // provide access to the internals of an object.

  public int firstWord(int word) {
    return ref(ptr(word));
  }

  public void flush(int channel) {
    switch (tag(channel)) {
    case OUTPUT_CHANNEL:
      XOS.flush(value(channel));
      break;
    default:
      System.out.println("Machine.flush: Unknown channel type " + tag(channel));
    }
  }

  public int getElement(CharSequence name) {

    // Used by the serializer...

    int nameSpace = valueStack.pop();
    int table = objAttValue(nameSpace, theSymbolContents);
    if (table == -1) return -1;
    int length = arrayLength(table);
    int element = -1;
    for (int i = 0; i < length && element == -1; i++) {
      int bucket = arrayRef(table, i);
      while (bucket != nilValue && element == -1) {
        int cell = consHead(bucket);
        bucket = consTail(bucket);
        int symbol = consHead(cell);
        int string = symbolName(symbol);
        if (stringEqual(string, name)) element = consTail(cell);
      }
    }
    if (element != -1) {
      valueStack.push(element);
      return 0;
    } else return -1;
  }

  public int getForwardRef(int path) {
    int forwardRef = -1;
    int refs = forwardRefs;
    while (forwardRef == -1 && refs != nilValue) {
      int ref = consHead(refs);
      if (forwardRefFor(ref, path))
        forwardRef = ref;
      else refs = consTail(refs);
    }
    if (refs == nilValue) {
      forwardRef = mkForwardRef(path);
      forwardRefs = mkCons(forwardRef, forwardRefs);
    }
    return forwardRef;
  }

  public boolean forwardRefFor(int forwardRef, int path) {
    return equalRefPaths(forwardRefPath(forwardRef), path);
  }

  public int globalDynamics() {
    return globalDynamics;
  }

  public void handle(Signal sig) {
    setInterruptFlag();
  }

  public int hashCode(int value) {

    // Implements Kernel_hashCode...

    switch (tag(value)) {
    case STRING:
      return stringHashCode(value);
    case SET:
      return setHashCode(value);
    default:
      return ptr(value);
    }
  }

  public boolean hasSaveIndex(int value) {
    return isSaveIndex(ref(ptr(value)));
  }

  public Header header() {
    return header;
  }

  public int heapSize() {
    return heapSize;
  }

  public int imageArgs() {
    int args = nilValue;
    for (int i = 0; i < imageArgs.size(); i = i + 2) {
      String key = (String) imageArgs.elementAt(i);
      String value = (String) imageArgs.elementAt(i + 1);
      args = mkCons(mkCons(mkString(key), mkString(value)), args);
    }
    return args;
  }

  public boolean inheritsFrom(int type1, int type2) {

    // Returns true when the first type inherits
    // from the second type...

    if (type1 == type2)
      return true;
    else {
      int parents = asSeq(objAttValue(type1, theSymbolParents));
      while (parents != nilValue) {
        int parent = consHead(parents);
        if (inheritsFrom(parent, type2))
          return true;
        else parents = consTail(parents);
      }
      return false;
    }
  }

  public void init() {

    // initialise the data structures.
    if (imageFile == null) {
      words = new int[heapSize];
      valueStack = new ValueStack(stackSize);
      threads = new Thread("INIT", valueStack, 0, 0);
    }
    gcWords = new int[heapSize];
    gcLimit = heapSize - freeHeap;
    initConstants();
  }

  public void init(int codeBox) {
    initStack(valueStack, codeBox, 0);
    currentFrame = 0;
    setGlobalDynamics();
  }

  public void setGlobalDynamics() {

    // Called when a new machine is initialised to construct
    // the built-in dynamics...

    if (imageFile == null) {
      int Kernel_Root_Table = mkHashtable(100);
      int Kernel_XCore_Table = mkHashtable(100);
      int Kernel_XCore_Table_Symbol = mkSymbol("Kernel_XCore_Table");
      int Kernel_Root_Table_Symbol = mkSymbol("Kernel_Root_Table");
      int Kernel_stdout_Symbol = mkSymbol("Kernel_stdout");
      int Kernel_stdin_Symbol = mkSymbol("Kernel_stdin");
      int stdout = mkImmediate(OUTPUT_CHANNEL, 0);
      int stdin = mkImmediate(INPUT_CHANNEL, 0);
      bindDynamic(mkSymbol("nil"), nilValue);
      bindDynamic(mkSymbol("null"), undefinedValue);
      bindDynamic(mkSymbol("Kernel_Symbol_Table"), symbolTable);
      bindDynamic(Kernel_Root_Table_Symbol, Kernel_Root_Table);
      symbolSetValue(Kernel_Root_Table_Symbol, Kernel_Root_Table);
      bindDynamic(Kernel_XCore_Table_Symbol, Kernel_XCore_Table);
      symbolSetValue(Kernel_XCore_Table_Symbol, Kernel_XCore_Table);
      bindDynamic(Kernel_stdout_Symbol, stdout);
      symbolSetValue(Kernel_stdout_Symbol, stdout);
      bindDynamic(Kernel_stdin_Symbol, stdin);
      symbolSetValue(Kernel_stdin_Symbol, stdin);
      importTable(Kernel_Root_Table);
      importTable(Kernel_XCore_Table);
      ForeignFuns.builtinForeignFuns(this, Kernel_Root_Table);
      globalDynamics = frameDynamics();
    }
  }

  public void importTable(int table) {
    setFrameDynamics(mkCons(mkDynamicTable(table), frameDynamics()));
  }

  public void init(String[] args) {

    // Initialise the machine from the command line arguments.
    // If the arguments specify an image then the heap, stack
    // and threads are initialised from the image. Otherwise
    // an empty heap and stack are created. The args may also
    // specify an initial object file to load as the starting
    // point for execution.

    args = ArgParser.parseArgs(XVMargSpecs, args);
    parseOpts(args);
    install("INT");
    init();
    loadImage();
    loadInit();
  }

  public void install(String signalName) {
    try {
      Signal diagSignal = new Signal(signalName);
      Signal.handle(diagSignal, this);
    } catch (IllegalArgumentException x) {
      System.out.println("Warning: unable to install interrupt signal handler.");
    }
  }

  void initConstants() {
    if (imageFile == null) {
      emptyArray = mkEmptyArray();
      emptySet = mkEmptySet();
      initSymbols();
      operatorTable = mkHashtable(operatorTableSize);
      constructorTable = mkHashtable(constructorTableSize);
      newListenersTable = mkHashtable(newListenersTableSize);
      clientInterface = mkHashtable(clientInterfaceSize);
      foreignTypeMapping = mkHashtable(foreignTypeMappingSize);
      foreignMOPMapping = mkHashtable(foreignMOPMappingSize);
    }
  }

  public void initStack(ValueStack stack, int codeBox, int arity) {
    stack.push(currentFrame);
    stack.push(openFrame);
    stack.push(codeBox);
    stack.push(mkInt(0)); // Code Index!!
    stack.push(undefinedValue); // Globals Vector!!
    stack.push(nilValue); // Dynamics Linked List!!
    stack.push(codeBoxLocals(codeBox) + arity);
    stack.push(undefinedValue); // Self!!
    stack.push(nilValue); // Super!!
    stack.push(undefinedValue); // Handler!!
    stack.push(mkInt(0)); // LineCount!!
    stack.push(mkInt(0)); // charCount!!
    stack.pushn((codeBoxLocals(codeBox) * 2) + arity, undefinedValue);
  }

  void initSymbols() {
    symbolTable = mkHashtable(5000);
    theSymbolAttributes = mkSymbol("attributes");
    theSymbolDefault = mkSymbol("default");
    theSymbolInit = mkSymbol("init");
    theSymbolMachineInit = mkSymbol("machineInit");
    theSymbolType = mkSymbol("type");
    theSymbolOperations = mkSymbol("operations");
    theSymbolParents = mkSymbol("parents");
    theSymbolName = mkSymbol("name");
    theSymbolArity = mkSymbol("arity");
    theSymbolOwner = mkSymbol("owner");
    theSymbolContents = mkSymbol("contents");
    theSymbolInvoke = mkSymbol("invoke");
    theSymbolDot = mkSymbol("dot");
    theSymbolFire = mkSymbol("fire");
    theSymbolValue = mkSymbol("value");
    theSymbolDocumentation = mkSymbol("documentation");
    theSymbolHead = mkSymbol("head");
    theSymbolTail = mkSymbol("tail");
    theSymbolIsEmpty = mkSymbol("isEmpty");
    theSymbolNewListener = mkSymbol("newListener");
  }

  public InputStream inputChannel(int inputChannel) {
    return XOS.inputChannel(value(inputChannel));
  }

  public String inputChannelToString(int index) {
    return "<InputChannel " + value(index) + ">";
  }

  public int instantiate(int c) {

    // Creates an instance of c. All the slots defined by
    // c are initialised to the default values specified
    // by the attribute types. No initialization expressions
    // defined by attributes are honoured.

    int obj = mkObj();
    objSetType(obj, c);
    addSlots(obj, c);
    return obj;
  }

  public void interrupt() {
    error(INTERRUPT, "Execution interrupted.");
    interrupt = false;
  }

  public boolean isNewListener(int fun) {
    return newListenerClasses(fun) != nilValue;
  }

  public boolean isNotVMNew(int obj) {

    // Implements Kernel_objIsNotVMNew...

    return getBit(value(objProperties(obj)), OBJ_NOT_VM_NEW) == 1;
  }

  public boolean isNamedElementReference(int nameSpaces, int namedElement) {
    boolean isReference = false;
    int ns = nameSpaces;
    while (ns != Machine.nilValue && !isReference) {
      int nameSpace = consHead(ns);
      ns = consTail(ns);
      int table = objGetContents(nameSpace);
      if (table != -1 && hashTableHasValue(table, namedElement)) isReference = true;
    }
    return isReference;
  }

  public boolean isSaveAsLookup(int obj) {

    // Implements Kernel_objIsSaveAsLookup...

    return getBit(value(objProperties(obj)), OBJ_SAVE_AS_LOOKUP) == 1;
  }

  public void kill(int thread) {

    // Implements Kernel_threadKill...

    if (getThread(thread) == threads)
      currentFrame = -1;
    else {
      Thread t = getThread(thread);
      if (t != null) {
        t.setCurrentFrame(-1);
      }
    }
  }

  public void killCurrentThread() {

    // Called when the current thread is to be removed from the machine.
    // The currently active thread is then selected at random (actually)
    // the next thread in the sequence). Note that if the current thread
    // is the only active thread then the machine will be left in a
    // terminated state.

    threads.kill();
    threads = threads.remove();
  }

  public int messageValue(xos.Value value) {
    switch (value.type) {
    case XData.INT:
      return mkInt(value.intValue);
    case XData.BOOL:
      return value.boolValue ? trueValue : falseValue;
    case XData.STRING:
      return mkString(value.strValue());
    case XData.FLOAT:
      return mkFloat(value.floatValue);
    case XData.VECTOR:
      int array = mkArray(value.values.length);
      for (int i = 0; i < value.values.length; i++)
        arraySet(array, i, messageValue(value.values[i]));
      return array;
    default:
      throw new MachineError(TYPE, "Unknown message arg type: " + value.type);
    }
  }

  public int newForeignFun(ForeignFun fun) {

    // Allocate a new foreign function and return its index...

    int index = foreignFuns.size();
    foreignFuns.push(fun);
    return mkForeignFun(index);
  }

  public ForeignObjectMOP getForeignMOP(Class<?> c) {

    // A Java class can be associated with a foreign MOP which
    // defined how to implement slot access, update and how
    // to send messages to its instances. There is a default
    // MOP. This method returns the MOP for a supplied class...

    ForeignObjectMOP mop = getForeignMOPInternal(c);
    if (mop == null)
      return ForeignObjectMOP.value;
    else return mop;
  }

  public ForeignObjectMOP getForeignMOPInternal(Class<?> c) {
    if (c == null)
      return null;
    else {
      ForeignObjectMOP mop = getForeignMOPForClass(c);
      if (mop == null) {
        mop = getForeignMOPInternal(c.getSuperclass());
        if (mop == null) {
          for (Class<?> i : c.getInterfaces()) {
            mop = getForeignMOPInternal(i);
            if (mop != null) return mop;
          }
        }
      }
      return mop;
    }
  }

  public ForeignObjectMOP getForeignMOPForClass(Class<?> c) {
    String className = c.getName();
    int MOPName = hashTableGet(foreignMOPMapping, className);
    if (MOPName != -1) {
      if (stringLength(MOPName) > 0) {
        String mopName = valueToString(MOPName);
        Class<?> mopClass;
        try {
          mopClass = XJ.loader.loadClass(mopName);
          return (ForeignObjectMOP) mopClass.newInstance();
        } catch (ClassNotFoundException e) {
          e.printStackTrace();
        } catch (InstantiationException e) {
          e.printStackTrace();
        } catch (IllegalAccessException e) {
          e.printStackTrace();
        }
      } else return ForeignObjectMOP.value;
    }
    return null;
  }

  public int getForeignType(Class<?> c) {

    // A Java class can be associated with an XOCL class so
    // the instances of the class masquerade as instances of
    // the XOCl class. This method returns the XOCL class
    // that is associated with the Java Class...

    int type = getForeignTypeInternal(c);
    if (type == -1)
      return theClassForeignObject;
    else return type;
  }

  public int getForeignTypeInternal(Class<?> c) {
    if (c == null)
      return -1;
    else {
      int type = getForeignTypeForClass(c);
      if (type == -1) for (Class<?> i : c.getInterfaces())
        type = getForeignTypeInternal(i);
      if (type == -1) type = getForeignTypeInternal(c.getSuperclass());
      return type;
    }
  }

  public int getForeignTypeForClass(Class<?> c) {
    String className = c.getName();
    return hashTableGet(foreignTypeMapping, className);
  }

  public int newForeignObj(Object obj) {

    // Return a foreign object. Foreign objects are maintained in
    // a table and manipulated via their index. It is important
    // that we return the same index when presented with the same
    // Java object...

    try {
      for (ForeignObject f : foreignObjects)
        if (f.getMop().equalObjects(f.getObject(), obj)) return mkForeignObj(foreignObjects.indexOf(f));
      int index = foreignObjects.size();
      Class<?> c = obj.getClass();
      int type = getForeignType(c);
      ForeignObjectMOP mop = getForeignMOP(c);
      foreignObjects.push(new ForeignObject(obj, type, mop));
      return mkForeignObj(index);
    } catch (Throwable t) {
      t.printStackTrace();
      return -1;
    }
  }

  public int newListenerClasses(int fun) {
    int properties = funProperties(fun);
    while (properties != nilValue) {
      int property = consHead(properties);
      int pname = consHead(property);
      if (pname == theSymbolNewListener)
        return consTail(property);
      else properties = consTail(properties);
    }
    return nilValue;
  }

  public int newListeners() {
    return newListenersTable;
  }

  public void nextThread() {

    // Select the next thread that is not sleeping.
    // There must be one thread that is awake.

    do {
      threads = threads.next();
    } while (threads.isSleeping());
  }

  public int nextToken(int inputChannel) {

    // Implements Kernel_nextToken (deprecated?)...

    if (isInputChannel(inputChannel))
      return readNextToken(value(inputChannel));

    else throw new MachineError(TYPE, "Machine.nextToken expects an input channel: " + valueToString(inputChannel));
  }

  public int nonReferencedElements(int elements, int nameSpaces) {

    // Implements Kernel_getNonReferencedElements...

    int newElements = mkNil();
    while (elements != nilValue) {
      int head = consHead(elements);
      if (!isReference(nameSpaces, head)) newElements = including(newElements, head);
      elements = consTail(elements);
    }
    return newElements;
  }

  public boolean isReference(int nameSpaces, int element) {

    // A named element is a reference when it occurs in one of
    // the ref tables. It may be referenced because all entries
    // in the ref tables are assumed to persist across different
    // instantiations of XMF.

    if (!(isObj(element) && objGetName(element) != -1 && objGetOwner(element) != -1)) return false;
    return isNamedElementReference(nameSpaces, element);
  }

  public OutputStream outputChannel(int outputChannel) {
    return XOS.outputChannel(value(outputChannel));
  }

  public String outputChannelToString(int index) {
    return "<OutputChannel " + value(index) + ">";
  }

  public void parseImageArg(String argSpec) {
    int separator = argSpec.indexOf(':');
    if (separator == -1) {
      System.out.println("An arg should take the form <KEY>:<VALUE> " + argSpec);
      System.exit(1);
    }
    String key = argSpec.substring(0, separator);
    String value = argSpec.substring(separator + 1, argSpec.length());
    imageArgs.addElement(key);
    imageArgs.addElement(value);
  }

  public void parseOpts(String[] args) {

    // Parse the command line options. Must be done before any
    // memory structures are initialised. The VM is created and
    // initialised from XOS. The first command line argument to
    // XOS is te port number used to connect clients. All VM
    // args start at index 1.

    int index = 0;
    while (index < args.length) {
      if (args[index].equals("-heapSize"))
        heapSize = Integer.parseInt(args[++index]) * K;
      else if (args[index].equals("-stackSize"))
        stackSize = Integer.parseInt(args[++index]) * K;
      else if (args[index].equals("-initFile"))
        initFile = args[++index];
      else if (args[index].equals("-freeHeap"))
        freeHeap = Integer.parseInt(args[++index]) * K;
      else if (args[index].equals("-stackDump"))
        stackDump = true;
      else if (args[index].equals("-image"))
        imageFile = args[++index];
      else if (args[index].equals("-arg"))
        parseImageArg(args[++index]);
      else {
        System.out.println("Unknown option: " + args[index]);
        displayOptions();
        exitAbnormal();
      }
      index++;
    }
  }

  public void displayOptions() {
    System.out.println("Options: ");
    System.out.println("  -heapSize <SIZE IN K UNITS>");
    System.out.println("  -stackSize <SIZE IN K UNITS>");
    System.out.println("  -freeHeap <SIZE IN K UNITS>");
    System.out.println("  -image <IMAGE FILE>");
    System.out.println("  -arg <NAME>:<VALUE>");
  }

  public int peek(int in) {

    // Implements Kernel_peek...

    if (isInputChannel(in))
      return mkInt(XOS.peek(value(in)));
    else return error(ERROR, "Machine.peek() expects an input channel: " + valueToString(in));
  }

  public int popStack() {
    return valueStack.pop();
  }

  public void pushOperators(int classifier) {
    int operators = setElements(objAttValue(classifier, theSymbolOperations));
    while (operators != nilValue) {
      int operator = consHead(operators);
      valueStack.push(operator);
      operators = consTail(operators);
    }
  }

  public void pushStack(int value) {
    valueStack.push(value);
  }

  public void rebindStdin(int in) {
    if (isInputChannel(in))
      XOS.rebindStandardInput(value(in));
    else error(ERROR, "Machine.rebindStdin expects an input channel: " + valueToString(in));
  }

  public void rebindStdout(int out) {
    if (isOutputChannel(out))
      XOS.rebindStandardOutput(value(out));
    else error(ERROR, "MAchine,rebindStdout expects an output channel: " + valueToString(out));
  }

  public void registerNewListener(int fun) {
    int classes = newListenerClasses(fun);
    while (classes != nilValue) {
      int c = consHead(classes);
      classes = consTail(classes);
      registerNewListener(c, fun);
    }
  }

  public void registerNewListener(int classifier, int fun) {
    int classes = hashTableGet(newListenersTable, classifier);
    if (classes == -1)
      hashTablePut(newListenersTable, classifier, mkCons(fun, nilValue));
    else hashTablePut(newListenersTable, classifier, mkCons(fun, classes));
  }

  public void resetOperatorTable() {
    hashTableClear(operatorTable);
    hashTableClear(constructorTable);
  }

  public void resetSaveLoad() {
    serializer.reset();
  }

  public void resetToInitialState(int in) {
    if (isInputChannel(in))
      if (XOS.isTokenChannel(value(in)))
        XOS.resetToInitialState(value(in));
      else throw new MachineError(TYPE, "Machine.resetToInitialState expects a token channel.");
    else throw new MachineError(TYPE, "Machine.resetToInitialState expects a token channel.");
  }

  public void restoreCurrentThread() {
    if (threads != null) {
      valueStack = threads.stack();
      currentFrame = threads.currentFrame();
      openFrame = threads.openFrame();
    }
  }

  public int result() {
    return valueStack.top();
  }

  public Serializer serializer() {
    return serializer;
  }

  public void setConstructorArgs(int klass, int args) {
    hashTablePut(constructorTable, klass, args);
  }

  public void setForwardRefs(int forwardRefs) {
    this.forwardRefs = forwardRefs;
  }

  public void setInterruptFlag() {
    interrupt = true;
  }

  public int setLength(int set) {
    return consLength(setElements(set));
  }

  // */ // End of double caching

  public boolean setMember(int value, int set) {
    int elements = setElements(set);
    while (elements != nilValue) {
      int element = consHead(elements);
      if (equalValues(element, value))
        return true;
      else elements = consTail(elements);
    }
    return false;
  }

  public void setSaveIndex(int word, int index) {
    set(ptr(word), mkSaveIndex(index));
  }

  public void setSetElements(int set, int list) {
    set(ptr(set), list);
  }

  public void setSymbol(int name, int symbol) {
    hashTablePut(symbolTable, name, symbol);
  }

  public void setThread(Thread thread) {
    threads = thread;
    restoreCurrentThread();
  }

  public int sortNamedElements(int elements, boolean casep) {
    int[] array = new int[size(elements)];
    int index = 0;
    while (elements != nilValue) {
      array[index++] = consHead(elements);
      elements = consTail(elements);
    }
    try {
      QuickSort.sort(array, casep, this);
    } catch (Throwable t) {
      System.out.println("Sorting error: " + t);
    }
    int sorted = nilValue;
    for (int i = array.length - 1; i >= 0; i--)
      sorted = mkCons(array[i], sorted);
    return sorted;
  }

  public boolean stackFrameAvailable() {
    return valueStack.index != -1;
  }

  private int stringBufferToXMFString(int buf) {
    buffer.setLength(0);
    int size = value(bufferSize(buf));
    for (int i = 0; i < size; i++)
      buffer.append((char) value(bufferRef(buf, i)));
    return mkString(buffer);
  }

  public int textTo(int tokenChannel, int position) {
    if (isInputChannel(tokenChannel))
      return mkString(XOS.textTo(value(tokenChannel), value(position)));
    else throw new MachineError(TYPE, "Machine.textTo expects an input channel: " + valueToString(tokenChannel));
  }

  public int thread() {
    // Return the current thread as a user level value.
    return mkThread(threads.id());
  }

  public int threadsAllocated() {
    if (threads == null)
      return 0;
    else return threads.length();
  }

  public int token(int type, int token, int posValue, int lineCount, int charCount, int rawChars) {
    int cons = nilValue;
    cons = mkCons(rawChars, cons);
    cons = mkCons(charCount, cons);
    cons = mkCons(lineCount, cons);
    cons = mkCons(posValue, cons);
    cons = mkCons(token, cons);
    cons = mkCons(type, cons);
    // System.out.println("Token " + valueToString(cons));
    return cons;
  }

  public boolean topLevelNameSpace(int nameSpace) {
    int owner = objAttValue(nameSpace, theSymbolOwner);
    return owner == nameSpace || owner == undefinedValue;
  }

  public void traceFun(int fun, int target, int args, int supers) {

    // Open a new frame and send the fun an invoke operation....
    // The invoke operation will deal with printing the trace information
    // then un-setting trace, representing the function invocation again
    // and re-setting the trace.

    openFrame();
    valueStack.push(fun);
    valueStack.push(mkCons(target, mkCons(args, mkCons(supers, nilValue))));
    valueStack.push(funTraced(fun));
    send(2, mkSymbol("invoke"));
  }

  public int usedHeap() {
    return freePtr;
  }

  public void wake(int value, int result) {

    // Wake the sleeping thread by changing its state to ACTIVE,
    // pushing the return value and scheduling the thread.

    Thread t = getThread(value);
    if (t != null) {
      if (t.state() == Thread.SLEEPING) {
        t.wake(result);
        XOS.schedule(t);
      }
    } else error(TYPE, "Thread::wake expects a thread.", value, theClassThread);
  }

  public void yield() {

    // Called when a thread wants to give up control to any
    // other threads that are currently waiting to be scheduled
    // by the OS.

    yield = true;
  }

  public int getForeignTypeMapping() {
    return foreignTypeMapping;
  }

  public void setForeignTypeMapping(int foreignTypeMapping) {
    this.foreignTypeMapping = foreignTypeMapping;
  }

  public int getForeignMOPMapping() {
    return foreignMOPMapping;
  }

  public void setForeignMOPMapping(int foreignMOPMapping) {
    this.foreignMOPMapping = foreignMOPMapping;
  }

  public ForeignObject getForeignObject(int target) {
    int index = foreignObjIndex(target);
    return foreignObjects.elementAt(index);
  }

  public boolean foreignObjHasSlot(int foreignObj, int name) {
    int index = foreignObjIndex(foreignObj);
    ForeignObject f = foreignObjects.elementAt(index);
    return f.getMop().hasSlot(this, foreignObj, name);
  }

  public int charCount(int string, int charPos) {

    // Implements Kernel_charCount (deprecated?)...

    switch (tag(string)) {
    case STRING:
      return stringCharCount(string, charPos);
    case BUFFER:
      return bufferCharCount(string, charPos);
    default:
      throw new Error("Illegal type of element for charCount: " + valueToString(string));
    }
  }

  public int lineCount(int string, int charPos) {

    // Implements kernel_lineCount (deprecated?)...

    switch (tag(string)) {
    case STRING:
      return stringLineCount(string, charPos);
    case BUFFER:
      return bufferLineCount(string, charPos);
    default:
      throw new Error("Illegal type of element for lineCount: " + valueToString(string));
    }
  }

  public void addBreakpoint(String filename, int line) {

    // Define a new breakpoint...

    debugger.addBreakpoint(filename, line);
  }

  public void clearBreakpoint(String filename, int line) {

    // Remove the breakpoint...

    debugger.clearBreakpoint(filename, line);

  }

  public synchronized boolean isReady() {

    // Returns the ready state of the VM. When ready, clients can
    // send messages and use the services of the VM...

    return ready;
  }

  public synchronized void setReady(boolean ready) {

    // Sets the ready state of the VM. Should be called from user
    // code when the machine has been initialised...

    this.ready = ready;
  }

  public Debugger getDebugger() {
    return debugger;
  }

  public void setDebugger(Debugger debugger) {
    this.debugger = debugger;
  }

  public static void main(String[] args) {
    String[] xosArgs = new String[args.length + 1];
    for (int i = 1; i < args.length + 1; i++)
      xosArgs[i] = args[i - 1];
    Machine machine = new Machine(null);
    machine.init(xosArgs);
  }

  public void breakpoint() {

    // A breakpoint has been hit at the current instruction.
    // This causes the current thread to change state and for
    // it to yield.

    threads.breakpoint();
    yield();
  }

  public void stepInto(String threadId, StepListener listener) {

    debugger.stepInto(threadId, value(frameLineCount()), currentFrame(), listener);
    resume(threadId);
  }

  public void stepOver(String threadId, StepListener listener) {

    debugger.stepOver(threadId, value(frameLineCount()), currentFrame(), listener);
    resume(threadId);
  }

  public void stepReturn(String threadId, StepListener listener) {

    debugger.stepReturn(threadId, value(frameLineCount()), currentFrame(), listener);
    resume(threadId);
  }

  public void resume(String threadId) {

    // Restart the thread that is in breakpoint mode...

    XOS.resume(threadId);
  }

  public byte[] asByteArray(int[] intArray) {

    byte[] byteArray = new byte[intArray.length * 4];

    for (int i = 0; i < intArray.length; i++) {
      byteArray[i] = (byte) byte1(intArray[i]);
      byteArray[i + 1] = (byte) byte2(intArray[i]);
      byteArray[i + 2] = (byte) byte3(intArray[i]);
      byteArray[i + 3] = (byte) byte4(intArray[i]);
    }
    return byteArray;
  }

  public File getFile(String fileName) {

    // Return a file from the operating system...

    return XOS.getFile(fileName);

  }

  public Header getHeader() {
    return header;
  }

  public int getFreePtr() {
    return freePtr;
  }

  public ValueStack getStack() {
    if (valueStack == null) valueStack = new ValueStack(stackSize);
    return valueStack;
  }

  public Stack<ForeignFun> getForeignFuns() {
    return foreignFuns;
  }

  public int[] getHeap(int minSize) {
    if (minSize > heapSize) {
      heapSize = minSize + (1000 * K);
      gcLimit = heapSize - (10 * K);
    }
    if (words == null || words.length < heapSize) {
      words = new int[heapSize];
      gcWords = new int[heapSize];
    }
    return words;
  }

  public int[] getHeap() {
    return words;
  }

  public void setHeader(Header header) {
    this.header = header;
  }

  public void setFreePtr(int freePtr) {
    this.freePtr = freePtr;
  }

  public int getOpenFrame() {
    return openFrame;
  }

  public void setCurrentFrame(int frame) {
    currentFrame = frame;
  }

  public void setOpenFrame(int frame) {
    openFrame = frame;
  }


}