package xos;

import java.io.File;

public interface FileResolver {

	// This interface is implemented by an object that can
	// resolve file paths to produce Java File objects.
	
	public File getFile(String path);
}
