
package com.android.sdklib.repository.generated.repository.v1;

import com.android.sdklib.repository.meta.DetailsTypes;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlType;


/**
 * DO NOT EDIT
 * This file was generated by xjc from sdk-repository-01.xsd. Any changes will be lost upon recompilation of the schema.
 * See the schema file for instructions on running xjc.
 * 
 * 
 *                 The API level used by the LayoutLib included in a platform to communicate with the IDE.
 *             
 * 
 * <p>Java class for layoutlibType complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="layoutlibType"&gt;
 *   &lt;complexContent&gt;
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType"&gt;
 *       &lt;attribute name="api" use="required" type="{http://www.w3.org/2001/XMLSchema}int" /&gt;
 *     &lt;/restriction&gt;
 *   &lt;/complexContent&gt;
 * &lt;/complexType&gt;
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "layoutlibType")
@SuppressWarnings({
    "override",
    "unchecked"
})
public class LayoutlibType
  extends DetailsTypes.PlatformDetailsType.LayoutlibType
{

    @XmlAttribute(name = "api", required = true)
    protected int api;

    /**
     * Gets the value of the api property.
     * 
     */
    public int getApi() {
        return api;
    }

    /**
     * Sets the value of the api property.
     * 
     */
    public void setApi(int value) {
        this.api = value;
    }

    public ObjectFactory createFactory() {
        return new ObjectFactory();
    }

}
