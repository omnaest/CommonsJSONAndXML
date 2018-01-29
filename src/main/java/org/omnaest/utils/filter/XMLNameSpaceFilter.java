package org.omnaest.utils.filter;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.XMLFilterImpl;

public class XMLNameSpaceFilter extends XMLFilterImpl
{
    private String namespace;

    public XMLNameSpaceFilter(String namespace)
    {
        super();
        this.namespace = namespace;
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException
    {
        super.endElement(this.namespace, localName, qName);
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException
    {
        super.startElement(this.namespace, localName, qName, atts);
    }

}
