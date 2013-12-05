package foreignfuns;

/******************************************************************************
 *                                                                            *
 *                             Foreign Functions                              *
 *             ------------------------------------------------               *
 *                                                                            *
 *  A foreign function is a Java method that can be called directly from XOCL *
 *  source code. Unlike calls that are performed through the Java foreign     *
 *  object interface, foreign functions are passed the current VM state and   *
 *  can manipulate it directly. Java foreign operation calls translate the    *
 *  arguments to Java values and do not pass the VM. When a foreign function  *
 *  is called, the arguments are on the stack and the stack frame for the     *
 *  call has been closed. Typically, the foreign function will extract its    *
 *  arguments from the current stack frame, perform any processing, push its  *
 *  return value on the stack and then pop the call frame (causing the return *
 *  value to be passed back).                                                 *
 *                                                                            *
 ******************************************************************************/

import java.lang.reflect.*;
import java.io.*;

import engine.Machine;

public class ForeignFun implements Serializable {

	private static final long serialVersionUID = 1L;
    
    static Class<?>[] types = new Class<?>[] { Machine.class };
	static Object[] args = new Object[1];

	private String className;
	private String methodName;
	private Method method;
	private int arity;

	public ForeignFun(String className, String methodName, int arity) {
	    
	    // A foreign function is created using static methods of a class.
	    // The names of the class and the static method are passed to the
	    // constructor. At that point the class and method are referenced
	    // by name and the foreign function is initialised, ready to be
	    // called...
	    
		init(className, methodName, arity);
	}

	public int arity() {
		return arity;
	}
	
	public String className() {
		return className;
	}

	public int getArity() {
        return arity;
    }

	public String getClassName() {
        return className;
    }

	public Method getMethod() {
        return method;
    }

    public String getMethodName() {
        return methodName;
    }

    public void init(String className, String methodName, int arity) {
		try {
			Class<?> c = Class.forName(className);
			Method method = c.getDeclaredMethod(methodName, types);
			this.className = className;
			this.methodName = methodName;
			this.method = method;
			this.arity = arity;
		} catch (ClassNotFoundException cnf) {
			throw new Error("Cannot find class " + className);
		} catch (NoSuchMethodException nsm) {
			throw new Error("Cannot find method " + methodName + " of " + className);
		}
	}

    public void invoke(Machine machine) {
		try {
			args[0] = machine;
			method.invoke(null, args);
		} catch (InvocationTargetException ite) {
			throw new Error(ite.getTargetException().getMessage());
		} catch (IllegalAccessException iae) {
			if (machine.stackDump)
				iae.printStackTrace();
			throw new Error(iae.getMessage());
		}
	}

    public String name() {
		return methodName;
	}

    public void setArity(int arity) {
        this.arity = arity;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public void setMethod(Method method) {
        this.method = method;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }
}
