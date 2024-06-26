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

package org.omnaest.utils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonGenerator.Feature;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.type.TypeFactory;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JsonUtils
{
    /**
     * @see #serialize(Object)
     * @param object
     * @return
     */
    public static String prettyPrint(Object object)
    {
        return serializer().withPrettyPrint()
                           .apply(object);
    }

    /**
     * Serializes the given {@link Object} without pretty formatting.
     * 
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
        return serializer().withPrettyPrint(pretty)
                           .withExceptionHandler(e ->
                           {
                               throw new IllegalStateException(e);
                           })
                           .apply(object);

        //        String retval = null;
        //        try
        //        {
        //            ObjectMapper objectMapper = new ObjectMapper();
        //            if (pretty)
        //            {
        //                objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        //            }
        //            else
        //            {
        //                objectMapper.disable(SerializationFeature.INDENT_OUTPUT);
        //            }
        //
        //            retval = objectMapper.writeValueAsString(object);
        //        }
        //        catch (Exception e)
        //        {
        //            LOGGER.debug("Exception serializing object into json" + object, e);
        //            throw new IllegalStateException(e);
        //        }
        //        return retval;
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

            try
            {
                writer.flush();
            }
            catch (Exception e)
            {
                // ignore
            }
        }
        catch (Exception e)
        {
            LOGGER.debug("Exception serializing object into json" + object, e);
            throw new IllegalStateException(e);
        }
    }

    public static void serializeArray(Stream<? extends Object> stream, Writer writer)
    {
        boolean pretty = false;
        serializeArray(stream, writer, pretty);
    }

    /**
     * Similar to {@link #serialize(Object, Writer, boolean)} but consumes a {@link Stream} of {@link Object}s in a memory efficient way.
     * 
     * @param stream
     * @param writer
     * @param pretty
     */
    public static void serializeArray(Stream<? extends Object> stream, Writer writer, boolean pretty)
    {
        try
        {
            ObjectMapper objectMapper = new ObjectMapper().configure(SerializationFeature.INDENT_OUTPUT, pretty);

            try (JsonGenerator jsonGenerator = objectMapper.createGenerator(writer)
                                                           .disable(Feature.AUTO_CLOSE_TARGET))
            {
                jsonGenerator.writeStartArray();

                Optional.ofNullable(stream)
                        .orElse(Stream.empty())
                        .forEach(object ->
                        {
                            try
                            {
                                objectMapper.writeValue(jsonGenerator, object);
                            }
                            catch (Exception e)
                            {
                                LOGGER.debug("Exception serializing array object into json " + object, e);
                                throw new IllegalStateException(e);
                            }
                        });

                jsonGenerator.writeEndArray();
            }

            //
            try
            {
                writer.flush();
            }
            catch (Exception e)
            {
                // ignore
            }
        }
        catch (Exception e)
        {
            LOGGER.debug("Exception serializing array into json", e);
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

                try
                {
                    writer.flush();
                }
                catch (Exception e)
                {
                    // ignore
                }
            }
            catch (Exception e)
            {
                LOGGER.debug("Exception serializing object into json" + object, e);
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

    public static <T> Stream<T> readArrayFromReader(Reader reader, Class<T> type)
    {
        return readJson((objectMapper) ->
        {
            try
            {
                return Optional.ofNullable(reader)
                               .map(r ->
                               {
                                   try
                                   {
                                       JsonParser jsonParser = objectMapper.getFactory()
                                                                           .createParser(reader);

                                       if (jsonParser.nextToken() != JsonToken.START_ARRAY)
                                       {
                                           throw new IllegalStateException("Content must contain a JSON array on root level");
                                       }

                                       Iterable<T> iterable = () -> new Iterator<T>()
                                       {
                                           private AtomicReference<JsonToken> token = new AtomicReference<>();

                                           @Override
                                           public boolean hasNext()
                                           {
                                               this.fetchNextTokenIfNoTokenPresent();
                                               return this.token.get() != JsonToken.END_ARRAY;
                                           }

                                           private void fetchNextTokenIfNoTokenPresent()
                                           {
                                               this.token.updateAndGet(token ->
                                               {
                                                   if (token != null)
                                                   {
                                                       return token;
                                                   }
                                                   else
                                                   {
                                                       try
                                                       {
                                                           return jsonParser.nextToken();
                                                       }
                                                       catch (IOException e)
                                                       {
                                                           throw new RuntimeException(e);
                                                       }
                                                   }
                                               });
                                           }

                                           @Override
                                           public T next()
                                           {
                                               try
                                               {
                                                   this.fetchNextTokenIfNoTokenPresent();

                                                   T value = objectMapper.readValue(jsonParser, type);

                                                   this.clearToken();

                                                   return value;
                                               }
                                               catch (Exception e)
                                               {
                                                   throw new RuntimeException(e);
                                               }
                                           }

                                           private void clearToken()
                                           {
                                               this.token.set(null);
                                           }
                                       };
                                       return StreamSupport.stream(iterable.spliterator(), false)
                                                           .onClose(() ->
                                                           {
                                                               try
                                                               {
                                                                   jsonParser.close();
                                                               }
                                                               catch (IOException e)
                                                               {
                                                                   throw new RuntimeException(e);
                                                               }
                                                           });
                                   }
                                   catch (Exception e)
                                   {
                                       throw new RuntimeException(e);
                                   }
                               })
                               .orElse(Stream.empty());
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
                return data != null && !data.isEmpty() ? objectMapper.readValue(data, type) : null;
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
            LOGGER.error("Exception deserializing into json", e);
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
        public default JsonStringSerializer<T> withPrettyPrint()
        {
            return this.withPrettyPrint(true);
        }

        public JsonStringSerializer<T> withPrettyPrint(boolean active);

        public JsonStringSerializer<T> withExceptionHandler(Consumer<Exception> exceptionHandler);

        public JsonByteArraySerializer<T> asByteArraySerializer();

        public <K> JsonStringSerializer<T> withKeySerializer(Class<K> type, JsonSerializer<K> keySerializer);
    }

    /**
     * Serializer to generate byte arrays as output
     * 
     * @author omnaest
     * @param <T>
     */
    public static interface JsonByteArraySerializer<T> extends Function<T, byte[]>
    {

    }

    public static interface JsonWriterSerializer<T> extends BiConsumer<T, Writer>
    {
        public JsonWriterSerializerWithWriter<T> withWriter(Writer writer);

        public JsonWriterSerializerWithObject<T> wrapObject(T object);

        public JsonWriterArraySerializer<T> forArray();
    }

    public static interface JsonWriterSerializerWithWriter<T> extends Consumer<T>
    {
    }

    public static interface JsonWriterSerializerWithObject<T> extends Consumer<Writer>
    {
    }

    public static interface JsonWriterArraySerializer<T> extends BiConsumer<Stream<T>, Writer>
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

        public JsonStringDeserializer<T> withExceptionHandler(Consumer<Exception> exceptionHandler);

        public JsonStringDeserializer<T> withKeyDeserializer(Class<?> type, KeyDeserializer keyDeserializer);

        public JsonByteArrayDeserializer<T> asByteArrayDeserializer();

    }

    public static interface JsonByteArrayDeserializer<T> extends Function<byte[], T>
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
        public JsonReaderArrayDeserializer<T> forArray();
    }

    public static interface JsonReaderArrayDeserializer<T> extends Function<Reader, Stream<T>>
    {

    }

    /**
     * @see JsonStringSerializer
     * @see #writerSerializer(Class)
     * @return
     */
    public static <T> JsonStringSerializer<T> serializer()
    {
        return new JsonStringSerializer<T>()
        {
            private ObjectMapper                         objectMapper   = new ObjectMapper();
            private Function<ObjectMapper, ObjectWriter> writerResolver = om -> om.writer();
            private Consumer<Exception>                  exceptionHandler;

            @Override
            public String apply(T object)
            {
                Function<ObjectWriter, String> objectWriterExecutor = ow ->
                {
                    try
                    {
                        return ow.writeValueAsString(object);
                    }
                    catch (JsonProcessingException e)
                    {
                        throw new IllegalStateException(e);
                    }
                };
                return this.<String>applyWithExecutor(object, objectWriterExecutor);
            }

            private <R> R applyWithExecutor(T object, Function<ObjectWriter, R> objectWriterExecutor)
            {
                R retval = null;
                try
                {
                    retval = this.writerResolver.andThen(objectWriterExecutor)
                                                .apply(this.objectMapper);
                }
                catch (Exception e)
                {
                    LOGGER.debug("Exception serializing object into json" + object, e);
                    Optional.ofNullable(this.exceptionHandler)
                            .ifPresent(handler -> handler.accept(e));
                }
                return retval;
            }

            @Override
            public JsonStringSerializer<T> withPrettyPrint(boolean active)
            {
                this.writerResolver = active ? om -> om.writerWithDefaultPrettyPrinter() : om -> om.writer();
                return this;
            }

            @Override
            public JsonStringSerializer<T> withExceptionHandler(Consumer<Exception> exceptionHandler)
            {
                this.exceptionHandler = exceptionHandler;
                return this;
            }

            @Override
            public JsonByteArraySerializer<T> asByteArraySerializer()
            {
                return new JsonByteArraySerializer<T>()
                {

                    @Override
                    public byte[] apply(T object)
                    {
                        Function<ObjectWriter, byte[]> objectWriterExecutor = ow ->
                        {
                            try
                            {
                                return ow.writeValueAsBytes(object);
                            }
                            catch (JsonProcessingException e)
                            {
                                throw new IllegalStateException(e);
                            }
                        };
                        return applyWithExecutor(object, objectWriterExecutor);
                    }
                };
            }

            @Override
            public <K> JsonStringSerializer<T> withKeySerializer(Class<K> type, JsonSerializer<K> keySerializer)
            {
                SimpleModule simpleModule = new SimpleModule();
                simpleModule.addKeySerializer(type, keySerializer);
                this.objectMapper.registerModule(simpleModule);
                return this;
            }
        };
    }

    /**
     * @see JsonStringSerializer
     * @see JsonStringSerializer#withPrettyPrint()
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <T> JsonStringSerializer<T> prettyPrintSerializer()
    {
        return (JsonStringSerializer<T>) serializer().withPrettyPrint();
    }

    /**
     * @see JsonStringSerializer
     * @see #serializer()
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
                serialize(object, writer, true);
            }

            @Override
            public JsonWriterSerializerWithWriter<T> withWriter(Writer writer)
            {
                JsonWriterSerializer<T> serializer = this;
                return new JsonWriterSerializerWithWriter<T>()
                {
                    @Override
                    public void accept(T object)
                    {
                        serializer.accept(object, writer);
                    }
                };
            }

            @Override
            public JsonWriterSerializerWithObject<T> wrapObject(T object)
            {
                JsonWriterSerializer<T> serializer = this;
                return new JsonWriterSerializerWithObject<T>()
                {
                    @Override
                    public void accept(Writer writer)
                    {
                        serializer.accept(object, writer);
                    }
                };
            }

            @Override
            public JsonWriterArraySerializer<T> forArray()
            {
                return new JsonWriterArraySerializer<T>()
                {
                    @Override
                    public void accept(Stream<T> stream, Writer writer)
                    {
                        serializeArray(stream, writer);
                    }
                };
            }
        };
    }

    /**
     * Similar to {@link #serializer()} but allows to specify if pretty printing is enabled or not
     * 
     * @param type
     * @param pretty
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <T> JsonStringSerializer<T> serializer(Class<? super T> type, boolean pretty)
    {
        return (JsonStringSerializer<T>) serializer().withPrettyPrint(pretty);
    }

    /**
     * Similar to {@link #serializer(Class, boolean)} with pretty print
     * 
     * @param type
     * @return
     */
    public static <T> JsonStringSerializer<T> serializer(Class<? super T> type)
    {
        return serializer(type, true);
    }

    /**
     * @see JsonStringDeserializer
     * @see #readerDeserializer(Class)
     * @param type
     * @param genericParameterTypes
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <T> JsonStringDeserializer<T> deserializer(Class<? super T> type, Class<?>... genericParameterTypes)
    {
        return (JsonStringDeserializer<T>) deserializer(tf -> tf.constructParametricType(type, genericParameterTypes));
    }

    public static <T> JsonStringDeserializer<T> deserializer(Function<TypeFactory, JavaType> typeFunction)
    {
        return new JsonStringDeserializer<T>()
        {
            private ObjectMapper                         objectMapper     = new ObjectMapper();
            private Function<ObjectMapper, ObjectReader> writerResolver   = om -> om.readerFor(typeFunction.apply(TypeFactory.defaultInstance()));
            private Consumer<Exception>                  exceptionHandler = e -> LOGGER.warn("Failed to deserialize json", e);;

            @Override
            public JsonStringDeserializer<T> withKeyDeserializer(Class<?> type, KeyDeserializer keyDeserializer)
            {
                SimpleModule simpleModule = new SimpleModule();
                simpleModule.addKeyDeserializer(type, keyDeserializer);
                this.objectMapper.registerModule(simpleModule);
                return this;
            }

            @Override
            public T apply(String data)
            {
                Function<ObjectReader, T> objectReaderExecutor = or ->
                {
                    try
                    {
                        return or.readValue(data);
                    }
                    catch (JsonProcessingException e)
                    {
                        throw new IllegalStateException(e);
                    }
                };
                return this.applyWithExecutor(data, objectReaderExecutor);
            }

            private <I> T applyWithExecutor(I data, Function<ObjectReader, T> objectReaderExecutor)
            {
                T retval = null;
                if (data != null)
                {
                    try
                    {
                        retval = this.writerResolver.andThen(objectReaderExecutor)
                                                    .apply(this.objectMapper);
                    }
                    catch (Exception e)
                    {
                        LOGGER.debug("Exception deserializing json into object" + data, e);
                        Optional.ofNullable(this.exceptionHandler)
                                .ifPresent(handler -> handler.accept(e));
                    }
                }
                return retval;
            }

            @Override
            public JsonStringDeserializer<T> withExceptionHandler(Consumer<Exception> exceptionHandler)
            {
                this.exceptionHandler = exceptionHandler;
                return this;
            }

            @Override
            public JsonByteArrayDeserializer<T> asByteArrayDeserializer()
            {
                return new JsonByteArrayDeserializer<T>()
                {
                    @Override
                    public T apply(byte[] data)
                    {
                        Function<ObjectReader, T> objectReaderExecutor = or ->
                        {
                            try
                            {
                                return or.readValue(data);
                            }
                            catch (IOException e)
                            {
                                throw new IllegalStateException(e);
                            }
                        };
                        return applyWithExecutor(data, objectReaderExecutor);
                    }
                };
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
                T value = (T) readFromReader(reader, type);
                this.closeReader(reader);
                return value;
            }

            private void closeReader(Reader reader)
            {
                try
                {
                    reader.close();
                }
                catch (IOException e)
                {
                    // ignore
                }
            }

            @Override
            public JsonReaderArrayDeserializer<T> forArray()
            {
                return new JsonReaderArrayDeserializer<T>()
                {
                    @SuppressWarnings("unchecked")
                    @Override
                    public Stream<T> apply(Reader reader)
                    {
                        return (Stream<T>) readArrayFromReader(reader, type);
                    }
                };
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
        return (E) cloner().apply(element);
    }

    /**
     * Returns an {@link UnaryOperator} {@link Function} which does clone the element given to it
     * 
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <E> JsonCloner<E> cloner()
    {
        return (JsonCloner<E>) cloner(Object.class);
    }

    public static <E> JsonCloner<E> cloner(Class<E> type)
    {
        return new JsonCloner<E>()
        {
            private ObjectMapper objectMapper = new ObjectMapper();

            @Override
            public E apply(E element)
            {
                return Optional.ofNullable(element)
                               .map(e -> this.objectMapper.convertValue(e, this.determineEffectiveType(element)))
                               .orElse(null);
            }

            @SuppressWarnings("unchecked")
            private Class<E> determineEffectiveType(E element)
            {
                return type != Object.class ? type : (Class<E>) element.getClass();
            }

            @Override
            public JsonCloner<E> usingKeyDeserializer(Class<?> type, KeyDeserializer keyDeserializer)
            {
                SimpleModule simpleModule = new SimpleModule();
                simpleModule.addKeyDeserializer(type, keyDeserializer);
                this.objectMapper.registerModule(simpleModule);
                return this;
            }

            @Override
            public <K> JsonCloner<E> withKeySerializer(Class<K> type, JsonSerializer<K> keySerializer)
            {
                SimpleModule simpleModule = new SimpleModule();
                simpleModule.addKeySerializer(type, keySerializer);
                this.objectMapper.registerModule(simpleModule);
                return this;
            }
        };
    }

    public static interface JsonCloner<E> extends UnaryOperator<E>
    {
        public JsonCloner<E> usingKeyDeserializer(Class<?> type, KeyDeserializer keyDeserializer);

        public <K> JsonCloner<E> withKeySerializer(Class<K> type, JsonSerializer<K> keySerializer);
    }

    /**
     * @see JsonStringSerializer
     * @see JsonStringDeserializer
     * @author omnaest
     * @param <T>
     */
    public static interface JsonStringConverter<T>
    {
        public JsonStringSerializer<T> serializer();

        public JsonStringDeserializer<T> deserializer();

        public JsonStringConverter<T> withExceptionHandler(Consumer<Exception> exceptionHandler);
    }

    /**
     * Returns a {@link JsonStringConverter} which contains a {@link JsonStringSerializer} and {@link JsonStringDeserializer}
     * 
     * @param type
     * @param genericParameterTypes
     * @return
     */
    public static <T> JsonStringConverter<T> converter(Class<T> type, Class<?>... genericParameterTypes)
    {
        return converter(tf -> tf.constructParametricType(type, genericParameterTypes));
    }

    public static <T> JsonStringConverter<T> converter(Function<TypeFactory, JavaType> typeFunction)
    {
        JsonStringSerializer<T> serializer = serializer();
        JsonStringDeserializer<T> deserializer = deserializer(typeFunction);
        return new JsonStringConverter<T>()
        {
            @Override
            public JsonStringSerializer<T> serializer()
            {
                return serializer;
            }

            @Override
            public JsonStringDeserializer<T> deserializer()
            {
                return deserializer;
            }

            @Override
            public JsonStringConverter<T> withExceptionHandler(Consumer<Exception> exceptionHandler)
            {
                serializer.withExceptionHandler(exceptionHandler);
                deserializer.withExceptionHandler(exceptionHandler);
                return this;
            }
        };
    }

    /**
     * Returns an {@link Optional} that contains the given {@link JsonNode} instance, if it is from type {@link ArrayNode}. Otherwise {@link Optional#empty()}
     * is returned.
     * 
     * @param node
     * @return
     */
    public static Optional<ArrayNode> toArrayNode(JsonNode node)
    {
        return Optional.ofNullable(node)
                       .filter(arrayNode -> arrayNode instanceof ArrayNode)
                       .map(arrayNode -> (ArrayNode) arrayNode);
    }
}
