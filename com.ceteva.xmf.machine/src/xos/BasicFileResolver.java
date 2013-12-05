package xos;

import java.io.File;

public class BasicFileResolver implements FileResolver {

	public File getFile(String path) {
		return new File(path);
	}

}
