package xjava;

public class XMFToJavaTypeError extends Error {
    
    private static final long serialVersionUID = 1L;
    
    private int value;
    
    private Class<?> type;

    public XMFToJavaTypeError(String message,int value,Class<?> type) {
        super(message + "(" + value + " is not of type " + type + ")");
        this.value = value;
        this.type = type;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public Class<?> getType() {
        return type;
    }

    public void setType(Class<?> type) {
        this.type = type;
    }

}
