package xos;

import java.io.File;

public class WorkspaceResolver implements FileResolver {
	
	private String workspace;

	public WorkspaceResolver(String workspace) {
		this.workspace = workspace;
	}

	public File getFile(String path) {

		// If the path starts with @ then it is
		// a path relative to the workspace. Replace
		// the @ with the workspace path...
		
		if(path.charAt(0) == '@' && path.length() > 1)
			return new File(workspace + "/" + path.substring(1));
		else return new File(path);
	}

}
