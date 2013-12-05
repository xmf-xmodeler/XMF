package clients;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;


public class BasicClientResult implements ClientResult {

    public void error(String message) {
        Shell shell = Display.getCurrent().getActiveShell();
        MessageDialog.openError(shell, "Error", message);
    }

	public void result(Object value) {
	}

}
