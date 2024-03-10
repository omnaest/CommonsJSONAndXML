/*******************************************************************************
 * Copyright 2021 Danny Kunz
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
/*

	Copyright 2017 Danny Kunz

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

		http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.


*/
package org.omnaest.utils;

import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Source;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;

import org.omnaest.utils.filter.XMLNameSpaceFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;

/**
 * Helper class for parsing and serializing xml content
 *
 * @see #parse(String, Class)
 * @see #serialize(Object)
 * @author Omnaest
 */
public class XMLHelper
{
    private static final Logger LOG = LoggerFactory.getLogger(XMLHelper.class);

    private static final class XMLParserImpl implements XMLParserLoadedWithSaxPreParser, XMLParserLoaded
    {
        private Reader                       reader;
        private XMLNameSpaceFilter           nameSpaceFilter;
        private boolean                      namespaces             = true;
        private boolean                      namespacePrefixes      = true;
        private boolean                      usingSAXParser         = false;
        private List<Consumer<Unmarshaller>> unmarshallerConfigurer = new ArrayList<>();

        private XMLParserImpl(Reader reader)
        {
            this.reader = reader;
        }

        @Override
        public XMLParserLoadedWithSaxPreParser withSAXParser()
        {
            this.usingSAXParser = true;
            return this;
        }

        @Override
        public XMLParserLoadedWithSaxPreParser enforceNamespace(String namespace)
        {
            this.nameSpaceFilter = new XMLNameSpaceFilter(namespace);
            return this;
        }

        @Override
        public XMLParserLoadedWithSaxPreParser useNamespaces(boolean namespaces)
        {
            this.namespaces = namespaces;
            return this;
        }

        @Override
        public XMLParserLoadedWithSaxPreParser useNamespacePrefixes(boolean namespacePrefixes)
        {
            this.namespacePrefixes = namespacePrefixes;
            return this;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T into(Class<T> type)
        {
            T retval = null;

            if (this.reader != null)
            {
                try
                {
                    Source xmlSource;
                    if (this.usingSAXParser)
                    {
                        xmlSource = this.generateSAXParserSource();
                    }
                    else
                    {
                        xmlSource = new StreamSource(this.reader);
                    }

                    //
                    JAXBContext jaxbContext = JAXBContext.newInstance(type);
                    Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();

                    this.unmarshallerConfigurer.forEach(consumer -> consumer.accept(unmarshaller));

                    //
                    if (JAXBElement.class.isAssignableFrom(type))
                    {
                        retval = (T) unmarshaller.unmarshal(xmlSource, Object.class);
                    }
                    else
                    {
                        retval = (T) unmarshaller.unmarshal(xmlSource);
                    }

                }
                catch (Exception e)
                {
                    throw new ParseRuntimException(e);
                }
            }

            return retval;
        }

        private Source generateSAXParserSource() throws ParserConfigurationException, SAXNotRecognizedException, SAXNotSupportedException, SAXException
        {
            Source xmlSource;
            SAXParserFactory parserFactory = SAXParserFactory.newInstance();
            parserFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            parserFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            parserFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

            XMLReader xmlReader = parserFactory.newSAXParser()
                                               .getXMLReader();
            xmlReader.setFeature("http://xml.org/sax/features/namespaces", this.namespaces);
            xmlReader.setFeature("http://xml.org/sax/features/namespace-prefixes", this.namespacePrefixes);

            if (this.nameSpaceFilter != null)
            {
                xmlSource = new SAXSource(this.nameSpaceFilter, new InputSource(this.reader));
                this.nameSpaceFilter.setParent(xmlReader);

            }
            else
            {
                xmlSource = new SAXSource(xmlReader, new InputSource(this.reader));
            }
            return xmlSource;
        }
    }

    public static class ParseRuntimException extends RuntimeException
    {
        private static final long serialVersionUID = -2172248039344150351L;

        public ParseRuntimException(Exception e)
        {
            super("Exception when parsing xml", e);
        }
    }

    public static class SerializeRuntimException extends RuntimeException
    {
        private static final long serialVersionUID = -2172223439344150351L;

        public SerializeRuntimException(Exception e)
        {
            super("Exception serializing xml object", e);
        }

    }

    public static interface XMLParser
    {
        public XMLParserLoaded from(String xml);

        public XMLParserLoaded from(Reader reader);

    }

    public static interface XMLParserLoadedBase
    {
        public <T> T into(Class<T> type);
    }

    public static interface XMLParserLoaded extends XMLParserLoadedBase
    {
        public XMLParserLoadedWithSaxPreParser withSAXParser();
    }

    public static interface XMLParserLoadedWithSaxPreParser extends XMLParserLoadedBase
    {
        public XMLParserLoadedWithSaxPreParser enforceNamespace(String namespace);

        public XMLParserLoadedWithSaxPreParser useNamespacePrefixes(boolean namespacePrefixes);

        public XMLParserLoadedWithSaxPreParser useNamespaces(boolean namespaces);
    }

    public static XMLParser parse()
    {
        return new XMLParser()
        {

            @Override
            public XMLParserLoaded from(String xml)
            {
                return this.from(xml != null && !xml.isEmpty() ? new StringReader(xml) : null);
            }

            @Override
            public XMLParserLoaded from(Reader reader)
            {
                return new XMLParserImpl(reader);
            }
        };
    }

    /**
     * Parses the given xml string into the given {@link Class} type
     *
     * @param xml
     * @param type
     * @return
     */
    public static <T> T parse(String xml, Class<T> type)
    {
        return parse().from(xml)
                      .into(type);
    }

    /**
     * Builder to serialize JAXB xml objects
     * 
     * @see #serialize(Object)
     * @author omnaest
     */
    public static interface Serializer
    {
        public String serialize(Object model);

        public Serializer withCompactPrint();

        public Serializer withPrettyPrint();

        public Serializer withoutHeader();

        public Serializer withHeader();

        public Serializer withRootTypes(Class<?> type);
    }

    /**
     * @see Serializer
     * @return
     */
    public static Serializer serializer()
    {
        return new Serializer()
        {
            private boolean        renderHeader = true;
            private boolean        prettyPrint  = true;
            private List<Class<?>> rootTypes    = new ArrayList<>();

            @Override
            public Serializer withHeader()
            {
                this.renderHeader = true;
                return this;
            }

            @Override
            public Serializer withoutHeader()
            {
                this.renderHeader = false;
                return this;
            }

            @Override
            public Serializer withPrettyPrint()
            {
                this.prettyPrint = true;
                return this;
            }

            @Override
            public Serializer withCompactPrint()
            {
                this.prettyPrint = false;
                return this;
            }

            @Override
            public String serialize(Object model)
            {
                return XMLHelper.serialize(model, (UnaryOperator<Marshaller>) t ->
                {
                    try
                    {
                        t.setProperty(Marshaller.JAXB_FRAGMENT, !this.renderHeader);
                        t.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, this.prettyPrint);

                    }
                    catch (Exception e)
                    {
                        LOG.error("", e);
                    }
                    return t;
                }, this.rootTypes.toArray(new Class[0]));
            }

            @Override
            public Serializer withRootTypes(Class<?> type)
            {
                this.rootTypes.add(type);
                return this;
            }
        };
    }

    /**
     * Serializes the given model object into xml string
     *
     * @param model
     * @return
     */
    public static String serialize(Object model)
    {
        UnaryOperator<Marshaller> modifier = null;
        return serialize(model, modifier);
    }

    /**
     * @see #serialize(Object)
     * @param model
     * @param modifier
     * @return
     */
    public static String serialize(Object model, UnaryOperator<Marshaller> modifier, Class<?>... rootTypes)
    {
        String retval = null;

        try
        {
            StringWriter writer = new StringWriter();
            JAXBContext jaxbContext = JAXBContext.newInstance(Stream.concat(Stream.of(model.getClass()), Arrays.asList(rootTypes)
                                                                                                               .stream())
                                                                    .collect(Collectors.toList())
                                                                    .toArray(new Class[0]));
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
            jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

            if (modifier != null)
            {
                Marshaller modifiedJaxbMarshaller = modifier.apply(jaxbMarshaller);
                if (modifiedJaxbMarshaller != null)
                {
                    jaxbMarshaller = modifiedJaxbMarshaller;
                }
            }

            jaxbMarshaller.marshal(model, writer);

            writer.close();
            retval = writer.toString();

        }
        catch (Exception e)
        {
            throw new SerializeRuntimException(e);
        }

        return retval;
    }

    @SuppressWarnings("unchecked")
    public static <T> T clone(T model)
    {
        return parse(serialize(model), (Class<T>) model.getClass());
    }
}
