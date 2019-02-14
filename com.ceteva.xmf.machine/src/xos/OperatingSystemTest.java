package xos;

public class OperatingSystemTest {

	public static void main(String[] args) {
		System.out.println("!!! OPERATING SYSTEM WRAPPER");
		System.out.println("curDir: "+System.getProperty("user.dir"));
		
		String homeDir = "/home/user/git/XMF/com.ceteva.xmf.system/xmf-src";
		String imageDir = homeDir+"/../xmf-img";
		
		args = new String[]{
			"-debug",
			"-image", imageDir+"/compiler.img",
			"-port", "10101",
			"-initFile", homeDir+"/Boot/Boot.o",
			"-heapSize", "5000",
			"-arg", "version:3.0.1",
			"-arg", "home:"+homeDir,
			"-arg", "license:license.lic"
		};
		
		OperatingSystem.main(args);
	}

}
