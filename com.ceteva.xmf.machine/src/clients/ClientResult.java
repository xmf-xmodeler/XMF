package clients;

/******************************************************************************
 *                                                                            *
 *                         Client Result Interface                            *
 *             ------------------------------------------------               *
 *                                                                            *
 *  Java can call XOCL code in the VM via the client interface. XOCL publishes*
 *  services that are available to Java via this interface. When a service is *
 *  invoked, Java must supply a ClientResult object that will be supplied     *
 *  with the result returned by the service. The Java data interface will     *
 *  translate the XMF value into a Java value before calling 'reult'.         *
 *                                                                            *
 ******************************************************************************/


public interface ClientResult {
	
	public void result(Object value);
	
	public void error(String message);

}
