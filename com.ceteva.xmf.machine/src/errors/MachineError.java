package errors;

/******************************************************************************
 *                                                                            *
 *                                VM Errors                                   *
 *             ------------------------------------------------               *
 *                                                                            *
 *  When an error is created in the VM an instance of the class MachineError  *
 *  is thrown. The class contains a description of the error including any    *
 *  contextual data elements.                                                 *
 *                                                                            *
 ******************************************************************************/

public class MachineError extends Error {

	private static final long serialVersionUID = 1L;
	
    private int error; // The error code.
    
	int[] data; // Contextual data.
	
	public MachineError(int error, String message,int... data) {
		super(message);
		this.error = error;
		this.data = data;
	}
	
	public String toString() {
		return "Machine Error: " + error + " " + getMessage();
	}

	public int[] getData() {
		return data;
	}

	public void setData(int[] data) {
		this.data = data;
	}

	public void setError(int error) {
		this.error = error;
	}

	public int getError() {
		return error;
	}

}