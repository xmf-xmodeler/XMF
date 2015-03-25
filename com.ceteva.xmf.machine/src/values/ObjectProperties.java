package values;

/******************************************************************************
 *                                                                            *
 *                             Object Properties                              *
 *             ------------------------------------------------               *
 *                                                                            *
 *  Objects have properties that are encoded as bits in a bit-sequence. This  *
 *  file defines the constants used to index into the property bit string.    *
 *                                                                            *
 ******************************************************************************/

public interface ObjectProperties {

  // Are the daemons being executed when the owning object is changed...

  public final static int OBJ_DAEMONS_ACTIVE   = 0;

  // After being loaded via an element channel, will this object be
  // sent a hotLoaded message...

  public final static int OBJ_HOT_LOAD         = 1;

  // If the receiver is a named element then if the following property
  // is set then the object is saved as lookup rather than being serialised...

  public final static int OBJ_SAVE_AS_LOOKUP   = 2;

  // If the object is a class then if the following property is set then
  // the instances of the class use the basic implementation for get slot...

  public final static int OBJ_DEFAULT_GET_MOP  = 3;

  // If the object is a class then if the following property is set then
  // the instances of the class use the basic implementation for set slot...

  public final static int OBJ_DEFAULT_SET_MOP  = 4;

  // If the object is a class then if the following property is set then
  // the instances of the class use the basic implementation for send...

  public final static int OBJ_DEFAULT_SEND_MOP = 5;

  // If the object is a class then if the following property is set then
  // the class cannot be instantiated in the VM and must go through the
  // appropriate new operation...

  public final static int OBJ_NOT_VM_NEW       = 6;

  // The default properties for objects...

  public final static int OBJ_DEFAULT_PROPS    = 1 << OBJ_DAEMONS_ACTIVE;

}
