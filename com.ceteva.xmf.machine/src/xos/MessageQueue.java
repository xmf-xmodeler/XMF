package xos;

import java.util.LinkedList;


public class MessageQueue extends LinkedList<Message> {
    
    // Used to implement scheduled messages in XOS.
    
    private static final long serialVersionUID = 1L;

    public void insert(Message thread) {
        addLast(thread);
    }
    
    public Message next() {
        return (Message)removeFirst();
    }

}
