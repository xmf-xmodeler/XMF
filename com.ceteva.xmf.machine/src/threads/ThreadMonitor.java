package threads;

/******************************************************************************
 *                                                                            *
 *                                Thread Monitors                             *
 *             ------------------------------------------------               *
 *                                                                            *
 *  When a thread dies, Java might need to clear up. The VM has a number of   *
 *  thread monitors that are called whenever a thread dies. Each monitor is   *
 *  supplied with the dying thread and the value that is currently on the head*
 *  of the stack. A specific example of a use of a thread monitor is for Java *
 *  call-back when a thread is created to handle a call from Java. In this    *
 *  case the monitor is used to supply the return value of the call back to   *
 *  Java.                                                                     *
 *                                                                            *
 ******************************************************************************/

public interface ThreadMonitor {
	
	public void threadDies(Thread thread,int result);

}
