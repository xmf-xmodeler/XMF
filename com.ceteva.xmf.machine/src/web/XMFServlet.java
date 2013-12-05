package web;

/******************************************************************************
 *                                                                            *
 *                              XMF Servlets                                  *
 *             ------------------------------------------------               *
 *                                                                            *
 *  XMF can act as a web server. This class is a simple example of how that   *
 *  can be done. You can implement your own versions of this class with more  *
 *  extensive facilities. The basic idea is that GET and POST requests from   *
 *  the web server are processed by calling a service published by XMF. The   *
 *  service receives the session id and the HTTP arguments and returns a      *
 *  string of HTML that is displayed as the next screen. XMF manages the state*
 *  of each session as appropriate. This servlet relies on the XMF service    *
 *  being make available via an image webserver.img which is placed in the    *
 *  appropriate place in the *real* web server (such as Tomcat) and is loaded *
 *  into an XMF machine when the servlet is initialised. The published XMF    *
 *  service should be called webserver and expect two arguments: the session  *
 *  id and a vector of HTPP arguments. If this Java class and XMF image is    *
 *  deployed on a web server then the XMF image will be started the first time*
 *  the appropriate location is referenced from a web browser.                *
 *                                                                            *
 ******************************************************************************/

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Enumeration;
import java.util.Vector;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import threads.ThreadInitiator;
import xos.XOSThread;
import clients.ClientResult;
import engine.Machine;

public class XMFServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    
    // The size of the XMF VM heap...

    private static final String heapSize = "5000";
    
    // The path to the image to be loaded into the VM...

    private static final String image    = "webserver.img";
    
    // The XMF VM runs (via the XMF OS) on a separate 
    // Java thread...

    private XOSThread           thread;
    
    // The monitor is used to implement a synchronous
    // call via the XMF clientSend method...
    
    private Monitor monitor = new Monitor();

    protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException {
        
        // When the web browser requests the deployed location then
        // the XMF service is called to handle the request and to
        // return a new HTML string...
        
        response.setContentType("text/html");
        final PrintWriter pw = response.getWriter();
        String sessionId = req.getSession().getId();
        Vector<Arg> args = httpArgs(req);
        Machine machine = thread.getOs().getXVM();
        
        // The machine may be loading the image at this point so
        // wait until the service has called xmf.setReady(true)...
        
        while (!machine.isReady()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        // Send is asynchronous so we need to use a Monitor to
        // enforce single threading through the machine. The result
        // is handled via a ClientResult...
        
        machine.clientSend("webserver", new Object[] {sessionId,args}, new ThreadInitiator(), new ClientResult() {
            public void result(Object result) {
                monitor.restart(result);
            }

            public void error(String message) {
                pw.println(message);
            }
        }, null);
        
        // This thread now waits for the result to be supplied
        // from the XMF service...
        
        monitor.monitor(pw);
    }

    @SuppressWarnings("unchecked")
    private Vector<Arg> httpArgs(HttpServletRequest req) {
        Vector<Arg> args = new Vector<Arg>();
        Enumeration<String> names = req.getParameterNames();
        while(names.hasMoreElements()) {
            String name = names.nextElement();
            String value = req.getParameter(name);
            args.addElement(new Arg(name,value));
        }
        return args;
    }

    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }

    public void init() throws ServletException {
        
        // Create a new XMF thread for the servlet and load the
        // appropriate XMF image with the XMF service...
        
        super.init();
        String root = getServletContext().getRealPath("/");
        String[] args = { "-image", root + image, "-heapSize", heapSize };
        thread = new XOSThread(args);
        thread.start();
    }

}