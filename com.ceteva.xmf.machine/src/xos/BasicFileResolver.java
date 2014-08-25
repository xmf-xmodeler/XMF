package xos;

import java.io.File;
import java.util.Arrays;

public class BasicFileResolver implements FileResolver {

	public File getFile(String path) {
		return new File(path);
	}

}
