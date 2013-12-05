package swing;

/******************************************************************************
 *                                                                            *
 *                             Action Listeners                               *
 *             ------------------------------------------------               *
 *                                                                            *
 *  Many elements in Java Swing rely on listeners waiting to process actions. *
 *  This class allows XMF to create an instance of an action listener from    *
 *  XOCL code that can be used to handle events via a call-back to an XMF     *
 *  service. The listener is created by supplying the machine and the name of *
 *  the service which should expect to receive the action event as an         *
 *  argument.                                                                 *
 *                                                                            *
 ******************************************************************************/

import java.awt.event.*;

import threads.ThreadInitiator;
import clients.ClientResult;

import engine.Machine;

public class Listener implements ActionListener {
    
    private Machine machine;
    private String service;

    public Listener(Machine machine, String service) {
        super();
        this.machine = machine;
        this.service = service;
    }

    public void actionPerformed(ActionEvent event) {
        
        machine.clientSend(service, new Object[] {event}, new ThreadInitiator(), new ClientResult() {
            
            // The service is invoked for its side effect so
            // nothing to do here...
            
            public void result(Object result) {}

            public void error(String message) {
                System.out.println(message);
            }
        }, null);
    }

}
