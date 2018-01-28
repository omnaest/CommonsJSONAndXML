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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class JSONHelper
{
    private static final Logger LOG = LoggerFactory.getLogger(JSONHelper.class);

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
            LOG.error("Exception serializing object into json" + object, e);
        }
        return retval;
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
                LOG.error("Exception serializing object into json" + object, e);
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
            catch (Exception e)
            {
                throw new JSONDeserializationException(e);
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
     * @param object
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <O> Map<String, Object> toMap(O object)
    {
        return toObjectWithType(object, Map.class);
    }

    /**
     * {@link Function} which does use {@link JSONHelper#prettyPrint(Object)}
     * 
     * @author omnaest
     * @param <T>
     */
    public static interface JsonSerializer<T> extends Function<T, String>
    {
    }

    /**
     * {@link Function} that does use {@link JSONHelper#readFromString(String, Class)}
     * 
     * @author omnaest
     * @param <T>
     */
    public static interface JsonDeserializer<T> extends Function<String, T>
    {
    }

    /**
     * @see JsonSerializer
     * @param type
     * @return
     */
    public static <T> JsonSerializer<T> serializer(Class<? super T> type)
    {
        return new JsonSerializer<T>()
        {
            @Override
            public String apply(T object)
            {
                return JSONHelper.prettyPrint(object);
            }
        };
    }

    /**
     * @see JsonDeserializer
     * @param type
     * @return
     */
    public static <T> JsonDeserializer<T> deserializer(Class<? super T> type)
    {
        return new JsonDeserializer<T>()
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
