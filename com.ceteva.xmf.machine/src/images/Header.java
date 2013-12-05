package images;

/******************************************************************************
 *                                                                            *
 *                                Image Headers                               *
 *             ------------------------------------------------               *
 *                                                                            *
 *  When an image is saved its header describes the date at which it was      *
 *  created and any properties the image has.                                 *
 *                                                                            *
 ******************************************************************************/

import java.io.Serializable;
import java.util.Date;
import java.util.Hashtable;

public class Header implements Serializable {
    
    // Each image has a header that describes
    // image properties...
    
    private static final long serialVersionUID = -827337376298952150L;

    private Date creationDate = new Date();
    
    private Hashtable<String,String> properties = new Hashtable<String,String>();
    
    public Header(Date date, Hashtable<String, String> properties) {
        this.creationDate = date;
        this.properties = properties;
    }

    public Header() {}

    public Date getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(Date creationDate) {
        this.creationDate = creationDate;
    }

    public Hashtable<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Hashtable<String, String> properties) {
        this.properties = properties;
    }

    public Date creationDate() {
        return creationDate;
    }
    
    public boolean hasProperty(String property) {
        return properties.containsKey(property);
    }
    
    public String propertyValue(String property) {
        if(hasProperty(property))
            return (String)properties.get(property);
        else return null;
    }
    
    public void setProperty(String property,String value) {
        properties.put(property,value);
    }
    
    public String toString() {
        return "Header(" + creationDate + "," + properties + ")";
    }
}
