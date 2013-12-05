package web;

/******************************************************************************
 *                                                                            *
 *                               HTTP Args                                    *
 *             ------------------------------------------------               *
 *                                                                            *
 *  When the servlet passes argument values to XMF it does so as a vector of  *
 *  instances of Arg. XMF will then receive them as as a sequence of foreign  *
 *  objects.                                                                  * 
 *                                                                            *
 ******************************************************************************/

public class Arg {
    
    private String name;
    private String value;
   
    public Arg(String name, String value) {
        super();
        this.name = name;
        this.value = value;
    }
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getValue() {
        return value;
    }
    public void setValue(String value) {
        this.value = value;
    }
    
    public String toString() {
        return "Arg(" + name + "," + value + ")";
    }

}
