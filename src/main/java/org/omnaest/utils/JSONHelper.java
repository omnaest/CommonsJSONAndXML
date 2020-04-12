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
import java.io.Writer;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

public class JSONHelper
{
    private static final Logger LOG = LoggerFactory.getLogger(JSONHelper.class);

    /**
     * @see #serialize(Object)
     * @param object
     * @return
     */
    public static String prettyPrint(Object object)
    {
        String retval = null;
        try
        {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

            retval = objectMapper.writeValueAsString(object);
        }
        catch (Exception e)
        {
            LOG.debug("Exception serializing object into json" + object, e);
        }
        return retval;
    }

    /**
     * @see #prettyPrint(Object)
     * @param object
     * @return
     */
    public static String serialize(Object object)
    {
        return serialize(object, false);
    }

    public static String serialize(Object object, boolean pretty)
    {
        String retval = null;
        try
        {
            ObjectMapper objectMapper = new ObjectMapper();
            if (pretty)
            {
                objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
            }
            else
            {
                objectMapper.disable(SerializationFeature.INDENT_OUTPUT);
            }

            retval = objectMapper.writeValueAsString(object);
        }
        catch (Exception e)
        {
            LOG.debug("Exception serializing object into json" + object, e);
            throw new IllegalStateException(e);
        }
        return retval;
    }

    /**
     * Similar to {@link #serialize(Object, Writer, boolean)} with no pretty print enabled
     * 
     * @param object
     * @param writer
     */
    public static void serialize(Object object, Writer writer)
    {
        boolean pretty = false;
        serialize(object, writer, pretty);
    }

    /**
     * Similar to {@link #serialize(Object, boolean)}
     * 
     * @param object
     * @param writer
     * @param pretty
     */
    public static void serialize(Object object, Writer writer, boolean pretty)
    {
        try
        {
            ObjectMapper objectMapper = new ObjectMapper();
            if (pretty)
            {
                objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
            }
            else
            {
                objectMapper.disable(SerializationFeature.INDENT_OUTPUT);
            }

            objectMapper.writeValue(writer, object);
        }
        catch (Exception e)
        {
            LOG.debug("Exception serializing object into json" + object, e);
            throw new IllegalStateException(e);
        }
    }

    public static class JSONSerializationException extends RuntimeException
    {
        private static final long serialVersionUID = 5857551929861868563L;

        public JSONSerializationException(Throwable cause)
        {
            super(cause);
        }

    }

    public static class JSONDeserializationException extends RuntimeException
    {
        private static final long serialVersionUID = 5857551923868563L;

        public JSONDeserializationException(Throwable cause)
        {
            super(cause);
        }

    }

    /**
     * Creates a {@link Consumer} for a {@link Writer} which holds the given object and appends it to the {@link Writer} as soon as the
     * {@link Consumer#accept(Object)} method is called. <br>
     * <br>
     * Note: calls {@link Writer#close()}
     * 
     * @see JSONHelper#prepareAsReaderToObjectFunction(Class)
     * @throws JSONSerializationException
     * @param object
     * @return
     */
    public static Consumer<Writer> prepareAsPrettyPrintWriterConsumer(Object object)
    {
        return writer ->
        {
            try
            {
                ObjectMapper objectMapper = new ObjectMapper();
                objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
                objectMapper.enable(SerializationFeature.CLOSE_CLOSEABLE);

                objectMapper.writeValue(writer, object);
            }
            catch (Exception e)
            {
                LOG.debug("Exception serializing object into json" + object, e);
                throw new JSONSerializationException(e);
            }
        };
    }

    /**
     * Returns a {@link Function} which maps the content of a given {@link Reader} to a typed {@link Object} instance of the given {@link Class} type
     * <br>
     * <br>
     * Note: calls {@link Reader#close()} at the end of the deserialization
     * 
     * @see #prepareAsPrettyPrintWriterConsumer(Object)
     * @throws JSONDeserializationException
     * @param type
     * @return
     */
    public static <T> Function<Reader, T> prepareAsReaderToObjectFunction(Class<T> type)
    {
        return reader -> readJson(objectMapper ->
        {
            try
            {
                T retval = objectMapper.readValue(reader, type);
                reader.close();
                return retval;
            }
            catch (MismatchedInputException e)
            {
                return null;
            }
            catch (Exception e)
            {
                throw new JSONDeserializationException(e);
            }
        });
    }

    /**
     * Reads a given {@link Class} type instance from the given {@link Reader}
     * 
     * @param reader
     * @param type
     * @return
     */
    public static <T> T readFromReader(Reader reader, Class<T> type)
    {
        return readJson((objectMapper) ->
        {
            try
            {
                return reader != null ? objectMapper.readValue(reader, type) : null;
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Reads a given {@link Class} type instance from the given {@link String}
     * 
     * @param data
     * @param type
     * @return
     */
    public static <T> T readFromString(String data, Class<T> type)
    {
        return readJson((objectMapper) ->
        {
            try
            {
                return data != null ? objectMapper.readValue(data, type) : null;
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        });
    }

    public static <T> T readFromString(String data, TypeReference<T> typeReference)
    {
        return readJson((objectMapper) ->
        {
            try
            {
                return objectMapper.readValue(data, typeReference);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        });
    }

    private static <T> T readJson(Function<ObjectMapper, T> supplier)
    {
        T retval = null;
        try
        {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

            retval = supplier.apply(objectMapper);
        }
        catch (Exception e)
        {
            LOG.error("Exception deserializing into json", e);
            throw new IllegalStateException(e);
        }
        return retval;
    }

    /**
     * Uses {@link ObjectMapper} to map from one object to another
     *
     * @see #toObjectWithType(Map, Class)
     * @see #toMap(Object)
     * @param object
     * @param type
     * @return
     */
    public static <O, T> T toObjectWithType(O object, Class<T> type)
    {
        return new ObjectMapper().convertValue(object, type);
    }

    /**
     * Uses {@link ObjectMapper} to map from a {@link Map} to another {@link Object} type
     *
     * @see #toMap(Object)
     * @param object
     * @param type
     * @return
     */
    public static <T> T toObjectWithType(Map<String, ? extends Object> map, Class<T> type)
    {
        return new ObjectMapper().convertValue(map, type);
    }

    /**
     * Returns a {@link Function} which maps from an object to a bean of the given {@link Class} type
     * 
     * @param type
     * @return
     */
    public static <O, T> Function<O, T> toObjectWithTypeMapper(Class<T> type)
    {
        return object -> toObjectWithType(object, type);
    }

    /**
     * Returns a nested {@link Map} generated from the given bean
     *
     * @see #toObjectWithType(Object, Class)
     * @see #toObjectWithType(Map, Class)
     * @param object
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <O, M extends Map<String, ? extends Object>> M toMap(O object)
    {
        return (M) toObjectWithType(object, Map.class);
    }

    /**
     * {@link Function} which does use {@link JSONHelper#prettyPrint(Object)}
     * 
     * @author omnaest
     * @param <T>
     */
    public static interface JsonStringSerializer<T> extends Function<T, String>
    {
    }

    public static interface JsonWriterSerializer<T> extends BiConsumer<T, Writer>
    {
    }

    /**
     * {@link Function} that does use a {@link Reader} to resolve an object instance
     * 
     * @author omnaest
     * @param <T>
     */
    public static interface JsonStringDeserializer<T> extends Function<String, T>
    {
    }

    /**
     * {@link Function} that does use {@link JSONHelper#readFromString(String, Class)}
     * 
     * @author omnaest
     * @param <T>
     */
    public static interface JsonReaderDeserializer<T> extends Function<Reader, T>
    {
    }

    /**
     * @see JsonStringSerializer
     * @see #writerSerializer(Class)
     * @param type
     * @return
     */
    public static <T> JsonStringSerializer<T> serializer(Class<? super T> type)
    {
        return new JsonStringSerializer<T>()
        {
            @Override
            public String apply(T object)
            {
                return JSONHelper.prettyPrint(object);
            }
        };
    }

    /**
     * @see JsonStringSerializer
     * @see #serializer(Class)
     * @param type
     * @return
     */
    public static <T> JsonWriterSerializer<T> writerSerializer(Class<? super T> type)
    {
        return new JsonWriterSerializer<T>()
        {
            @Override
            public void accept(T object, Writer writer)
            {
                JSONHelper.serialize(object, writer, true);
            }
        };
    }

    /**
     * Similar to {@link #serializer(Class)} but allows to specify if pretty printing is enabled or not
     * 
     * @param type
     * @param pretty
     * @return
     */
    public static <T> JsonStringSerializer<T> serializer(Class<? super T> type, boolean pretty)
    {
        return new JsonStringSerializer<T>()
        {
            @Override
            public String apply(T object)
            {
                return pretty ? JSONHelper.prettyPrint(object) : JSONHelper.serialize(object);
            }
        };
    }

    /**
     * @see JsonStringDeserializer
     * @see #readerDeserializer(Class)
     * @param type
     * @return
     */
    public static <T> JsonStringDeserializer<T> deserializer(Class<? super T> type)
    {
        return new JsonStringDeserializer<T>()
        {
            @SuppressWarnings("unchecked")
            @Override
            public T apply(String data)
            {
                return data != null ? (T) JSONHelper.readFromString(data, type) : null;
            }
        };
    }

    /**
     * @see #deserializer(Class)
     * @param type
     * @return
     */
    public static <T> JsonReaderDeserializer<T> readerDeserializer(Class<? super T> type)
    {
        return new JsonReaderDeserializer<T>()
        {
            @SuppressWarnings("unchecked")
            @Override
            public T apply(Reader reader)
            {
                return (T) JSONHelper.readFromReader(reader, type);
            }
        };
    }

    /**
     * Clones a given element
     * 
     * @param element
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <E> E clone(E element)
    {
        return (E) new ObjectMapper().convertValue(element, element.getClass());
    }

    /**
     * Returns an {@link UnaryOperator} {@link Function} which does clone the element given to it
     * 
     * @return
     */
    public static <E> UnaryOperator<E> cloner()
    {
        return element -> clone(element);
    }
}
