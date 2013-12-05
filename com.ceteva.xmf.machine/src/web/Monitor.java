package web;

/******************************************************************************
 *                                                                            *
 *                            Processing Monitors                             *
 *             ------------------------------------------------               *
 *                                                                            *
 *  When the servlet calls XMF to produce the next HTML to display it must    *
 *  wait for the XMF to return. The servlet thread uses a single instance of  *
 *  Monitor to wait for the result.                                           *
 *                                                                            *
 ******************************************************************************/

import java.io.PrintWriter;

public class Monitor {
    
    private Object result;
    
    public synchronized void monitor(PrintWriter pw) {
        try {
            wait();
            pw.println(result.toString());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public synchronized void restart(Object result) {
        this.result = result;
        notifyAll();
    }

}
