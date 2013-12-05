package xjava;

/******************************************************************************
 *                                                                            *
 *                             Runnable Threads                               *
 *             ------------------------------------------------               *
 *                                                                            *
 *  Use this class to create a Java runnable that invokes an XMF service. The *
 *  class can be instantiated from within XMF allowing XOCL code to create    *
 *  a Java thread that is handled by an XMF thread (get that?).               *
 *                                                                            *
 ******************************************************************************/

import threads.ThreadInitiator;
import clients.ClientResult;
import engine.Machine;

public class Runnable implements java.lang.Runnable {
    
    private Machine machine;
    private String service;

    public Runnable(Machine machine, String service) {
        this.machine = machine;
        this.service = service;
    }

    public void run() {
        
        machine.clientSend(service, new Object[] {}, new ThreadInitiator(), new ClientResult() {
            
            // Cannot really do much with the result...
            
            public void result(Object result) {}

            public void error(String message) {
                System.out.println(message);
            }
        }, null);
    }

}
