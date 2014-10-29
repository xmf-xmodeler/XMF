package images;

/******************************************************************************
 *                                                                            *
 *                    Serialising and Inflating Images                        *
 *             ------------------------------------------------               *
 *                                                                            *
 *  An image is a saved machine state consisting of the heap, stack and any   * 
 *  information necessary to continue running when the image is inflated.     *
 *  An image is encoded in a binary format (a byte array) by writing it to    *
 *  an image buffer before writing the buffer to an output stream. This file  *
 *  describes how the various components of a running XMF VM support system   *
 *  are encoded in the image buffer (serialised) and how the VM is recreated  *
 *  when the saved buffer is read back in (inflated).                         *
 *                                                                            *
 ******************************************************************************/

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Stack;

import threads.Thread;
import values.ValueStack;
import engine.Machine;
import foreignfuns.ForeignFun;

public class ImageSerializer {

  // The following constants are the size of a block of information
  // used to encode various data structured in a serialised image...

  private static final int STRING_HEADER        = 4;

  private static final int HEAP_HEADER          = 8;

  private static final int VALUE_STACK_HEADER   = 8;

  private static final int STACK_HEADER         = 8;

  private static final int THREAD_HEADER        = 16;

  private static final int FOREIGN_TABLE_HEADER = 4;

  private static final int MACHINE_CONSTANTS    = 57;

  private static final int HEADER_HEADER        = 4;

  private Machine          machine;

  // The image buffer and its index...

  private byte[]           image;

  private int              index                = 0;

  // Date format...

  private String           dateFormat           = "E MMM dd hh:mm:ss Z yyyy";
  private SimpleDateFormat sdf                  = new SimpleDateFormat(dateFormat);

  public ImageSerializer(Machine machine) {
    this.machine = machine;
  }

  private int headerSize() {
    Header header = machine.getHeader();
    Date date = header.getCreationDate();
    Hashtable<String, String> properties = header.getProperties();
    // long time = date.getTime();
    // String timeString = "" + time;
    // int size = stringSize(timeString);
    int size = sdf.format(date).length();
    Enumeration<String> keys = properties.keys();
    while (keys.hasMoreElements()) {
      String key = keys.nextElement();
      String value = properties.get(key);
      size = size + stringSize(key);
      size = size + stringSize(value);
    }
    return size + HEADER_HEADER;
  }

  private int heapSize() {
    int freePtr = machine.getFreePtr();
    return ((freePtr - 1) * 4) + HEAP_HEADER;
  }

  private int imageSize() {

    // Calculates the size of the image in bytes...

    int header = headerSize();
    int heap = heapSize();
    int stack = stackSize();
    int threads = threadsSize();
    int tables = tablesSize();
    int constants = MACHINE_CONSTANTS * 4;
    return header + heap + stack + threads + tables + constants;
  }

  private void inflate() {
    inflateHeader();
    inflateHeap();
    inflateThreads();
    inflateStack();
    inflateTables();
    inflateConstants();
  }

  public boolean inflate(String path) {
    try {
      FileInputStream fin = new FileInputStream(machine.getFile(path));
      int size = readInt(fin);
      image = new byte[size];
      fin.read(image);
      inflate();
      fin.close();
      return true;
    } catch (FileNotFoundException e) {
      e.printStackTrace();
      return false;
    } catch (IOException e) {
      e.printStackTrace();
      return false;
    }
  }

  private void inflateConstants() {
    machine.clientInterface = readInt();
    machine.foreignTypeMapping = readInt();
    machine.foreignMOPMapping = readInt();
    machine.symbolTable = readInt();
    machine.emptyArray = readInt();
    machine.emptySet = readInt();
    machine.theTypeBoolean = readInt();
    machine.theClassCompiledOperation = readInt();
    machine.theTypeInteger = readInt();
    machine.theTypeFloat = readInt();
    machine.theTypeString = readInt();
    machine.theTypeNull = readInt();
    machine.theTypeSeqOfElement = readInt();
    machine.theTypeSetOfElement = readInt();
    machine.theClassElement = readInt();
    machine.theClassForeignObject = readInt();
    machine.theClassForeignOperation = readInt();
    machine.theClassForwardRef = readInt();
    machine.theClassException = readInt();
    machine.theClassClass = readInt();
    machine.theClassPackage = readInt();
    machine.theClassBind = readInt();
    machine.theClassCodeBox = readInt();
    machine.theClassTable = readInt();
    machine.theClassThread = readInt();
    machine.theTypeSeq = readInt();
    machine.theTypeSet = readInt();
    machine.theClassDataType = readInt();
    machine.theClassDaemon = readInt();
    machine.theClassBuffer = readInt();
    machine.theClassVector = readInt();
    machine.theClassSymbol = readInt();
    machine.theSymbolAttributes = readInt();
    machine.theSymbolInit = readInt();
    machine.theSymbolMachineInit = readInt();
    machine.theSymbolType = readInt();
    machine.theSymbolDefault = readInt();
    machine.theSymbolOperations = readInt();
    machine.theSymbolParents = readInt();
    machine.theSymbolName = readInt();
    machine.theSymbolArity = readInt();
    machine.theSymbolOwner = readInt();
    machine.theSymbolContents = readInt();
    machine.theSymbolInvoke = readInt();
    machine.theSymbolDot = readInt();
    machine.theSymbolFire = readInt();
    machine.theSymbolValue = readInt();
    machine.theSymbolDocumentation = readInt();
    machine.theSymbolHead = readInt();
    machine.theSymbolTail = readInt();
    machine.theSymbolIsEmpty = readInt();
    machine.theSymbolNewListener = readInt();
    machine.operatorTable = readInt();
    machine.constructorTable = readInt();
    machine.newListenersTable = readInt();
    machine.globalDynamics = readInt();
  }

  private ForeignFun inflateForeignFun() {
    int arity = readInt();
    String className = readString();
    String methodName = readString();
    return new ForeignFun(className, methodName, arity);
  }

  private void inflateHeader() {
    Date date = null;
    try {
      String s = readString();
      date = sdf.parse(s);
    } catch (ParseException e) {
      e.printStackTrace();
    }
    int size = readInt();
    Hashtable<String, String> properties = new Hashtable<String, String>();
    for (int i = 0; i < size; i++) {
      String key = readString();
      String value = readString();
      properties.put(key, value);
    }
    machine.setHeader(new Header(date, properties));

  }

  private void inflateHeap() {
    int freePtr = readInt();
    int[] heap = machine.getHeap(freePtr);
    machine.setFreePtr(freePtr);
    for (int i = 0; i < freePtr; i++)
      heap[i] = readInt();
  }

  private void inflateStack() {
    ValueStack stack = machine.getStack();
    int[] elements = stack.getElements();
    int TOS = readInt();
    stack.setTOS(TOS);
    for (int i = 0; i < TOS; i++)
      elements[i] = readInt();
    machine.setCurrentFrame(readInt());
    machine.setOpenFrame(readInt());
  }

  private void inflateTables() {
    int size = readInt();
    Stack<ForeignFun> foreignFuns = machine.getForeignFuns();
    foreignFuns.clear();
    for (int i = 0; i < size; i++)
      foreignFuns.push(inflateForeignFun());
  }

  private Thread inflateThread() {
    int id = readInt();
    int currentFrame = readInt();
    int openFrame = readInt();
    int state = readInt();
    String name = readString();
    String client = readString();
    ValueStack stack = inflateValueStack();
    Thread thread = new Thread(id, name, stack, currentFrame, openFrame, state);
    thread.setClient(client);
    return thread;
  }

  private void inflateThreads() {
    int length = readInt();
    Thread thread = inflateThread();
    for (int i = 1; i < length; i++) {
      Thread nextThread = inflateThread();
      thread.add(nextThread);
      thread = nextThread;
    }
    machine.setThread(thread.next());
  }

  private ValueStack inflateValueStack() {
    int TOS = readInt();
    ValueStack stack = new ValueStack(machine.stackSize);
    int[] elements = stack.getElements();
    stack.setTOS(TOS);
    for (int i = 0; i < TOS; i++)
      elements[i] = readInt();
    return stack;

  }

  private int readByte() {
    int b = image[index++];
    return b;
  }

  private int readInt() {
    int byte1 = readByte();
    int byte2 = readByte();
    int byte3 = readByte();
    int byte4 = readByte();
    return Machine.mkWord(byte4, byte3, byte2, byte1);
  }

  private int readInt(FileInputStream fin) throws IOException {
    int b1 = fin.read();
    int b2 = fin.read();
    int b3 = fin.read();
    int b4 = fin.read();
    return Machine.mkWord(b4, b3, b2, b1);
  }

  private String readString() {
    int length = readInt();
    char[] buffer = new char[length];
    for (int i = 0; i < length; i++)
      buffer[i] = (char) image[index++];
    return new String(buffer);
  }

  public byte[] serialize() {
    image = new byte[imageSize()];
    serializeHeader();
    serializeHeap();
    serializeThreads();
    serializeStack();
    serializeTables();
    serializeConstants();
    return image;
  }

  public boolean serialize(String path) {
    try {
      FileOutputStream fout = new FileOutputStream(machine.getFile(path));
      serialize();
      writeInt(fout, index);
      fout.write(image);
      fout.close();
      return true;
    } catch (FileNotFoundException e) {
      e.printStackTrace(System.err);
      return false;
    } catch (IOException e) {
      e.printStackTrace(System.err);
      return false;
    } catch (Throwable t) {
      t.printStackTrace(System.err);
      return false;
    }
  }

  private void serializeConstants() {
    writeInt(machine.clientInterface);
    writeInt(machine.foreignTypeMapping);
    writeInt(machine.foreignMOPMapping);
    writeInt(machine.symbolTable);
    writeInt(machine.emptyArray);
    writeInt(machine.emptySet);
    writeInt(machine.theTypeBoolean);
    writeInt(machine.theClassCompiledOperation);
    writeInt(machine.theTypeInteger);
    writeInt(machine.theTypeFloat);
    writeInt(machine.theTypeString);
    writeInt(machine.theTypeNull);
    writeInt(machine.theTypeSeqOfElement);
    writeInt(machine.theTypeSetOfElement);
    writeInt(machine.theClassElement);
    writeInt(machine.theClassForeignObject);
    writeInt(machine.theClassForeignOperation);
    writeInt(machine.theClassForwardRef);
    writeInt(machine.theClassException);
    writeInt(machine.theClassClass);
    writeInt(machine.theClassPackage);
    writeInt(machine.theClassBind);
    writeInt(machine.theClassCodeBox);
    writeInt(machine.theClassTable);
    writeInt(machine.theClassThread);
    writeInt(machine.theTypeSeq);
    writeInt(machine.theTypeSet);
    writeInt(machine.theClassDataType);
    writeInt(machine.theClassDaemon);
    writeInt(machine.theClassBuffer);
    writeInt(machine.theClassVector);
    writeInt(machine.theClassSymbol);
    writeInt(machine.theSymbolAttributes);
    writeInt(machine.theSymbolInit);
    writeInt(machine.theSymbolMachineInit);
    writeInt(machine.theSymbolType);
    writeInt(machine.theSymbolDefault);
    writeInt(machine.theSymbolOperations);
    writeInt(machine.theSymbolParents);
    writeInt(machine.theSymbolName);
    writeInt(machine.theSymbolArity);
    writeInt(machine.theSymbolOwner);
    writeInt(machine.theSymbolContents);
    writeInt(machine.theSymbolInvoke);
    writeInt(machine.theSymbolDot);
    writeInt(machine.theSymbolFire);
    writeInt(machine.theSymbolValue);
    writeInt(machine.theSymbolDocumentation);
    writeInt(machine.theSymbolHead);
    writeInt(machine.theSymbolTail);
    writeInt(machine.theSymbolIsEmpty);
    writeInt(machine.theSymbolNewListener);
    writeInt(machine.operatorTable);
    writeInt(machine.constructorTable);
    writeInt(machine.newListenersTable);
    writeInt(machine.globalDynamics);

  }

  private void serializeForeignFun(ForeignFun foreignFun) {
    String className = foreignFun.getClassName();
    String methodName = foreignFun.getMethodName();
    int arity = foreignFun.getArity();
    writeInt(arity);
    writeString(className);
    writeString(methodName);
  }

  private void serializeHeader() {
    Header header = machine.getHeader();
    Date date = header.getCreationDate();
    String dateString = sdf.format(date);
    Hashtable<String, String> properties = header.getProperties();
    Enumeration<String> keys = properties.keys();
    writeString(dateString);
    writeInt(properties.size());
    while (keys.hasMoreElements()) {
      String key = keys.nextElement();
      String value = properties.get(key);
      writeString(key);
      writeString(value);
    }
  }

  private void serializeHeap() {
    int freePtr = machine.getFreePtr();
    int[] heap = machine.getHeap(freePtr);
    writeInt(freePtr);
    for (int i = 0; i < freePtr; i++)
      writeInt(heap[i]);
  }

  private void serializeStack() {
    ValueStack stack = machine.getStack();
    serializeStack(stack);
    writeInt(machine.currentFrame());
    writeInt(machine.getOpenFrame());
  }

  private void serializeStack(ValueStack stack) {
    int TOS = stack.getTOS();
    int[] elements = stack.getElements();
    writeInt(TOS);
    for (int i = 0; i < TOS; i++)
      writeInt(elements[i]);
  }

  private void serializeTables() {
    Stack<ForeignFun> foreignFuns = machine.getForeignFuns();
    int size = foreignFuns.size();
    writeInt(size);
    for (int i = 0; i < size; i++)
      serializeForeignFun(foreignFuns.get(i));
  }

  private void serializeThread(Thread thread) {
    String name = thread.getName();
    ValueStack stack = thread.getStack();
    int id = thread.id();
    int state = thread.state();
    int currentFrame = thread.getCurrentFrame();
    int openFrame = thread.getOpenFrame();
    String client = thread.client() == null ? "" : thread.client();
    writeInt(id);
    writeInt(currentFrame);
    writeInt(openFrame);
    writeInt(state);
    writeString(name);
    writeString(client);
    serializeStack(stack);
  }

  private void serializeThreads() {
    Thread thread = machine.currentThread();
    machine.saveCurrentThread();
    int length = thread.length();
    writeInt(length);
    for (int i = 0; i < length; i++) {
      serializeThread(thread);
      thread = thread.next();
    }
  }

  private int stackSize() {
    ValueStack stack = machine.getStack();
    return stackSize(stack) + VALUE_STACK_HEADER;
  }

  private int stackSize(ValueStack stack) {
    int TOS = stack.getTOS();
    return (TOS * 4) + STACK_HEADER;
  }

  private int stringSize(String string) {
    return string.length() + STRING_HEADER;
  }

  private int tablesSize() {
    Stack<ForeignFun> foreignFuns = machine.getForeignFuns();
    int size = 0;
    for (ForeignFun f : foreignFuns) {
      String className = f.getClassName();
      String methodName = f.getMethodName();
      size = size + stringSize(className) + stringSize(methodName) + 4;
    }
    return size + FOREIGN_TABLE_HEADER;
  }

  private int threadSize(Thread thread) {
    String name = thread.getName();
    ValueStack stack = thread.getStack();
    String client = thread.client() == null ? "" : thread.client();
    return stringSize(name) + stackSize(stack) + stringSize(client) + THREAD_HEADER;
  }

  private int threadsSize() {
    Thread thread = machine.currentThread();
    int size = threadSize(thread);
    thread = thread.next();
    while (thread != machine.currentThread()) {
      size = size + threadSize(thread);
      thread = thread.next();
    }
    return size;
  }

  private void writeByte(byte b) {
    image[index++] = b;
  }

  private void writeInt(int i) {
    writeByte((byte) Machine.byte1(i));
    writeByte((byte) Machine.byte2(i));
    writeByte((byte) Machine.byte3(i));
    writeByte((byte) Machine.byte4(i));
  }

  private void writeInt(OutputStream out, int i) {
    try {
      out.write(Machine.byte1(i));
      out.write(Machine.byte2(i));
      out.write(Machine.byte3(i));
      out.write(Machine.byte4(i));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void writeString(String string) {
    writeInt(string.length());
    for (int i = 0; i < string.length(); i++)
      writeByte((byte) string.charAt(i));
  }

}
