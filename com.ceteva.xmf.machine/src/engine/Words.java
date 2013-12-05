package engine;

public interface Words {
	
	// Word snipping constants....
	
	public final static int BYTE1 = 0x000000FF;

	public final static int BYTE2 = 0x0000FF00;

	public final static int BYTE3 = 0x00FF0000;

	public final static int BYTE4 = 0xFF000000;
	
	// The PTR part of a machine word is an address in the heap...

	public final static int PTR = BYTE1 | BYTE2 | BYTE3;
	
	// The DATA part of a machine word is not generally a heap address...

	public final static int DATA = BYTE1 | BYTE2 | BYTE3;

}
