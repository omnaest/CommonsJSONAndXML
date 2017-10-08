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

import java.io.StringReader;
import java.io.StringWriter;
import java.util.function.UnaryOperator;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Source;
import javax.xml.transform.sax.SAXSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;

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

	/**
	 * Parses the given xml string into the given {@link Class} type
	 *
	 * @param xml
	 * @param type
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static <T> T parse(String xml, Class<T> type)
	{
		T retval = null;

		if (xml != null && !xml.isEmpty())
		{
			try
			{
				SAXParserFactory parserFactory = SAXParserFactory.newInstance();
				parserFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
				parserFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
				parserFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

				Source xmlSource = new SAXSource(	parserFactory.newSAXParser()
																.getXMLReader(),
													new InputSource(new StringReader(xml)));

				JAXBContext jaxbContext = JAXBContext.newInstance(type);
				Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
				retval = (T) unmarshaller.unmarshal(xmlSource);
			} catch (Exception e)
			{
				throw new ParseRuntimException(e);
			}
		}

		return retval;
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
	}

	/**
	 * @see Serializer
	 * @return
	 */
	public static Serializer serializer()
	{
		return new Serializer()
		{
			private boolean	renderHeader	= true;
			private boolean	prettyPrint		= true;

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
					} catch (Exception e)
					{
						LOG.error("", e);
					}
					return t;
				});
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
	public static String serialize(Object model, UnaryOperator<Marshaller> modifier)
	{
		String retval = null;

		try
		{
			StringWriter writer = new StringWriter();
			JAXBContext jaxbContext = JAXBContext.newInstance(model.getClass());
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

		} catch (Exception e)
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
