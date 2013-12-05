package xos;

public class XOSThread extends Thread {
	
	// Creates a new instance of XOS and the XVM running in its own thread.
	// When the thread is started, the OS is initialized with the arguments.

	private OperatingSystem os;
	private String[] args;
	
	public XOSThread(String[] args) {
		this.args = args;
		this.os = new OperatingSystem();
	}

	public void run() {
		os.init(args);
	}

	public OperatingSystem getOs() {
		return os;
	}
}
