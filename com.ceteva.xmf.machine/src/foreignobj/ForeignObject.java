package foreignobj;

/******************************************************************************
 *                                                                            *
 *                             Foreign Objects                                *
 *             ------------------------------------------------               *
 *                                                                            *
 *  A foreign object is a Java object that has been wrapped up to behave as   *
 *  an XMF object. By default foreign objects are instances of the class      *
 *  ForeignObject and provide access to their fields and methods via '.'.     *
 *  Control over field access, update and method invocation is provided by    *
 *  the MOP of a foreign object.                                              *
 *                                                                            *
 ******************************************************************************/

public class ForeignObject {
	
	private Object object;
	
	private int type;
	
	private ForeignObjectMOP mop;

	public ForeignObject(Object object, int type, ForeignObjectMOP mop) {
		super();
		this.object = object;
		this.type = type;
		this.mop = mop;
	}

	public Object getObject() {
		return object;
	}

	public void setObject(Object object) {
		this.object = object;
	}

	public int getType() {
		return type;
	}

	public void setType(int type) {
		this.type = type;
	}

	public ForeignObjectMOP getMop() {
		return mop;
	}

	public void setMop(ForeignObjectMOP mop) {
		this.mop = mop;
	}
	
	public String toString() {
		return "ForeignObj(" + object + "," + type + "," + mop + ")";
	}

}
