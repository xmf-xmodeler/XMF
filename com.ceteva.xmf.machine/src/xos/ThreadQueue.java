package xos;


import java.util.LinkedList;

import threads.Thread;


public class ThreadQueue extends LinkedList<Thread> {
    
    // Used to implement scheduled threads in XOS.
    
    private static final long serialVersionUID = 1L;

    public void insert(Thread thread) {
        addLast(thread);
    }
    
    public Thread next() {
        return (Thread)removeFirst();
    }

}
