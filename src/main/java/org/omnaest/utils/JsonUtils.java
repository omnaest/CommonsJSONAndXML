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
import java.util.NoSuchElementException;
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
     * Shared {@link ObjectMapper} for the paths that do not need a per-call configured mapper: the plain
     * {@link ObjectMapper#convertValue(Object, Class)} conversions ({@link #toObjectWithType(Object, Class)},
     * {@link #toObjectWithType(Map, Class)} and therefore {@link #toMap(Object)}), the {@link ObjectWriter}
     * derived by {@link #serialize(Object, Writer, boolean)}, and every read routed through
     * {@link #readJson(Function)}.
     * <p>
     * These previously constructed a fresh {@code new ObjectMapper()} on every single call.
     * {@link ObjectMapper} is expensive to build - it initialises annotation introspection, a root-name
     * lookup and the serializer/deserializer caches - and is explicitly documented as thread-safe and
     * intended to be reused once configured. Building one per call therefore threw away the very caches
     * that make the second and subsequent conversions cheap, and it showed up as ~14% of CPU samples in a
     * JFR profile of a NodeDB ingest run (Jackson's own {@code RootNameLookup.<init>} and
     * {@code AnnotatedClassResolver._addSuperTypes} frames sitting at top-of-stack), for records whose
     * payload was empty.
     * <p>
     * Never reconfigure this instance - no {@code enable}/{@code disable}/{@code registerModule} calls on it -
     * since a static mapper is shared by every caller in the JVM and such a change would leak globally.
     * Per-call tuning has to be expressed either by deriving an immutable {@link ObjectWriter}/
     * {@link ObjectReader} from it, as {@link #serialize(Object, Writer, boolean)} does, or by building a
     * local mapper - which is why pretty-printing, custom (de)serialization features and module registration
     * elsewhere in this class still construct their own.
     */
    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper();

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
     * Similar to {@link #serialize(Object, boolean)} but writes into the given {@link Writer}.
     * <p>
     * Note: the {@link Writer} is flushed but <b>not</b> closed - the caller keeps ownership of it. This
     * matches {@link #serializeArray(Stream, Writer, boolean)} and is the opposite of
     * {@link #prepareAsPrettyPrintWriterConsumer(Object)}, which does close. Jackson would otherwise close
     * the {@link Writer} on its own, since {@link Feature#AUTO_CLOSE_TARGET} is enabled by default, so that
     * feature is switched off explicitly below.
     *
     * @param object
     * @param writer
     * @param pretty
     */
    public static void serialize(Object object, Writer writer, boolean pretty)
    {
        try
        {
            ObjectWriter objectWriter = (pretty ? SHARED_OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                    : SHARED_OBJECT_MAPPER.writer()).without(Feature.AUTO_CLOSE_TARGET);

            objectWriter.writeValue(writer, object);

            // deliberately not swallowed: a failed flush means the json has not been fully written, which the
            // caller must not mistake for a successful serialization
            writer.flush();
        }
        catch (Exception e)
        {
            LOGGER.debug("Exception serializing object into json: {}", object, e);
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
            // try-with-resources rather than a close() on the success path only. The Reader did already get
            // closed on both paths, but through Jackson's JsonParser.Feature.AUTO_CLOSE_SOURCE default rather
            // than through anything stated here, so the documented Reader#close() contract now holds visibly
            // and no longer depends on that default staying enabled
            try (Reader closeableReader = reader)
            {
                return objectMapper.readValue(closeableReader, type);
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
        return readJson(objectMapper -> reader != null ? objectMapper.readValue(reader, type) : null);
    }

    /**
     * Reads a JSON array from the given {@link Reader} as a lazy {@link Stream}, pulling one element at a time
     * so that arrays larger than the heap can be processed.
     * <p>
     * Note: the returned {@link Stream} owns the underlying {@link JsonParser} and {@link Reader} and has to be
     * closed by the caller, ideally via try-with-resources, since {@link Stream} is {@link AutoCloseable}. If
     * this method throws instead of returning a {@link Stream}, it has already released the parser itself.
     *
     * @param reader
     * @param type
     * @return
     */
    public static <T> Stream<T> readArrayFromReader(Reader reader, Class<T> type)
    {
        if (reader == null)
        {
            return Stream.empty();
        }

        return readJson(objectMapper ->
        {
            JsonParser jsonParser = objectMapper.getFactory()
                                                .createParser(reader);
            try
            {
                if (jsonParser.nextToken() != JsonToken.START_ARRAY)
                {
                    throw new IllegalStateException("Content must contain a JSON array on root level");
                }

                Iterable<T> iterable = () -> new JsonArrayIterator<>(jsonParser, objectMapper, type);
                return StreamSupport.stream(iterable.spliterator(), false)
                                    .onClose(() -> closeParser(jsonParser));
            }
            catch (Exception e)
            {
                // from the createParser call on, the parser owns the reader, so every path that does not hand a
                // closing Stream back to the caller has to release it here instead
                try
                {
                    jsonParser.close();
                }
                catch (IOException suppressed)
                {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        });
    }

    private static void closeParser(JsonParser jsonParser)
    {
        try
        {
            jsonParser.close();
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@link Iterator} over the elements of a JSON array, reading one element at a time from a
     * {@link JsonParser} positioned just after the opening {@link JsonToken#START_ARRAY}.
     *
     * @author omnaest
     * @param <T>
     */
    private static class JsonArrayIterator<T> implements Iterator<T>
    {
        private final JsonParser   jsonParser;
        private final ObjectMapper objectMapper;
        private final Class<T>     type;

        /**
         * The token already pulled by {@link #hasNext()}, or null if the next one still has to be fetched.
         * <p>
         * A plain field on purpose: this is consumed by a sequential {@link Stream}, and the
         * {@link AtomicReference#updateAndGet(UnaryOperator)} used here before broke that method's contract,
         * whose update function has to be side effect free because it may be reapplied - while this one
         * advanced the {@link JsonParser}.
         */
        private JsonToken peekedToken;

        private JsonArrayIterator(JsonParser jsonParser, ObjectMapper objectMapper, Class<T> type)
        {
            super();
            this.jsonParser = jsonParser;
            this.objectMapper = objectMapper;
            this.type = type;
        }

        @Override
        public boolean hasNext()
        {
            JsonToken token = this.peekToken();

            // null means the input ended without a closing bracket; treated as the end of the array as well, as
            // otherwise the iterator would never terminate
            return token != null && token != JsonToken.END_ARRAY;
        }

        @Override
        public T next()
        {
            if (!this.hasNext())
            {
                throw new NoSuchElementException("No further element available within the JSON array");
            }

            try
            {
                T value = this.objectMapper.readValue(this.jsonParser, this.type);
                this.peekedToken = null;
                return value;
            }
            catch (IOException e)
            {
                throw new IllegalStateException(e);
            }
        }

        private JsonToken peekToken()
        {
            if (this.peekedToken == null)
            {
                try
                {
                    this.peekedToken = this.jsonParser.nextToken();
                }
                catch (IOException e)
                {
                    throw new IllegalStateException(e);
                }
            }
            return this.peekedToken;
        }
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
        return readJson(objectMapper -> data != null && !data.isEmpty() ? objectMapper.readValue(data, type) : null);
    }

    public static <T> T readFromString(String data, TypeReference<T> typeReference)
    {
        return readJson(objectMapper -> objectMapper.readValue(data, typeReference));
    }

    /**
     * A read operation against an {@link ObjectMapper} which is allowed to throw the checked exceptions that
     * Jackson's read methods declare, so that the operations passed to {@link #readJson(JsonReadOperation)} do
     * not each have to wrap them in a {@link RuntimeException} of their own.
     *
     * @author omnaest
     * @param <T>
     */
    @FunctionalInterface
    private static interface JsonReadOperation<T>
    {
        public T apply(ObjectMapper objectMapper) throws Exception;
    }

    /**
     * Runs the given read operation against the {@link #SHARED_OBJECT_MAPPER}.
     * <p>
     * Every read entry point of this class funnels through here ({@link #readFromString(String, Class)},
     * {@link #readFromString(String, TypeReference)}, {@link #readFromReader(Reader, Class)},
     * {@link #readArrayFromReader(Reader, Class)} and {@link #prepareAsReaderToObjectFunction(Class)}), and
     * none of them configures the mapper - they only call {@code readValue} or derive a {@link JsonParser}
     * from the shared, thread-safe {@code JsonFactory}. So there is nothing to tune per call and no reason to
     * pay for a fresh {@link ObjectMapper} every time, which is what this used to do.
     * <p>
     * The previous implementation additionally enabled {@link SerializationFeature#INDENT_OUTPUT} here, which
     * was dead configuration on a pure deserialization path - and keeping it while sharing the mapper would
     * have been an outright bug, since {@link ObjectMapper#writer()} inherits it and non-pretty
     * {@link #serialize(Object, Writer, boolean)} output would silently have become indented.
     * <p>
     * Unchecked exceptions are rethrown as they are: the operations raise meaningful ones of their own, like
     * the {@link JSONDeserializationException} that {@link #prepareAsReaderToObjectFunction(Class)} documents,
     * and wrapping those in an {@link IllegalStateException} here would make that documented contract a lie.
     * Only checked exceptions are wrapped, exactly once.
     */
    private static <T> T readJson(JsonReadOperation<T> operation)
    {
        try
        {
            return operation.apply(SHARED_OBJECT_MAPPER);
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
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
        return SHARED_OBJECT_MAPPER.convertValue(object, type);
    }

    /**
     * Uses {@link ObjectMapper} to map from a {@link Map} to another {@link Object} type
     *
     * <p>
     * Note: behaves exactly like {@link #toObjectWithType(Object, Class)}, which accepts a {@link Map} just as
     * well and which a {@link Map} argument would bind to were this overload not here. It is kept, and not
     * deprecated, because overload resolution always prefers it for a {@link Map} - so deprecating it would
     * warn callers that have no way of selecting the other one.
     *
     * @see #toMap(Object)
     * @param map
     * @param type
     * @return
     */
    public static <T> T toObjectWithType(Map<String, ? extends Object> map, Class<T> type)
    {
        return toObjectWithType((Object) map, type);
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
     * Similar to {@link #toObjectWithType(Object, Class)} where the target type is {@link JsonNode}.
     * 
     * @param object
     * @return
     */
    public static JsonNode toJsonNode(Object object)
    {
        return toObjectWithType(object, JsonNode.class);
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

    /**
     * {@link Function} which does use {@link #prettyPrint(Object)}
     * <p>
     * Note: mutable. The {@code with...} methods reconfigure this instance and return it rather than a copy, so
     * they have to be called before the first {@link #apply(Object)} - Jackson does not support registering
     * modules on an {@link ObjectMapper} that is already in use - and a serializer handed out by
     * {@link JsonStringConverter#serializer()} is shared with that converter, which means reconfiguring it
     * affects every other user of the same converter.
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
     * {@link Function} that does resolve an object instance from a JSON {@link String}
     * <p>
     * Note: mutable, with the same caveats as {@link JsonStringSerializer} - configure before the first
     * {@link #apply(Object)}, and be aware that {@link JsonStringConverter#deserializer()} hands out a shared
     * instance.
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
        return new JsonStringSerializer<T>() {
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
                return new JsonByteArraySerializer<T>() {

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
     * Note: none of the returned serializers close the {@link Writer} they are given - it is flushed and left
     * open, see {@link #serialize(Object, Writer, boolean)}.
     * <p>
     * All of them write compact JSON. The single object variant used to pretty print while
     * {@link JsonWriterSerializer#forArray()} did not, so the same builder produced two different formats; use
     * {@link #serialize(Object, Writer, boolean)} directly if indented output is wanted.
     *
     * @see JsonStringSerializer
     * @see #serializer()
     * @param type
     *            only binds the generic type of the returned serializer, its value is not otherwise used
     * @return
     */
    public static <T> JsonWriterSerializer<T> writerSerializer(Class<? super T> type)
    {
        return new JsonWriterSerializer<T>() {
            @Override
            public void accept(T object, Writer writer)
            {
                boolean pretty = false;
                serialize(object, writer, pretty);
            }

            @Override
            public JsonWriterSerializerWithWriter<T> withWriter(Writer writer)
            {
                JsonWriterSerializer<T> serializer = this;
                return new JsonWriterSerializerWithWriter<T>() {
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
                return new JsonWriterSerializerWithObject<T>() {
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
                return new JsonWriterArraySerializer<T>() {
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
     *            only binds the generic type of the returned serializer, its value is not otherwise used
     * @param pretty
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <T> JsonStringSerializer<T> serializer(Class<? super T> type, boolean pretty)
    {
        return (JsonStringSerializer<T>) serializer().withPrettyPrint(pretty);
    }

    /**
     * Similar to {@link #serializer(Class, boolean)} with pretty print.
     * <p>
     * Note the deliberate asymmetry to {@link #serialize(Object)}, which does not pretty print: this factory
     * defaults to indented output, so pick the explicit {@link #serializer(Class, boolean)} where the format
     * matters.
     *
     * @param type
     *            only binds the generic type of the returned serializer, its value is not otherwise used
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
        // constructParametricType insists that the number of given type parameters matches the number the class
        // declares, so a generic type without any given parameters - deserializer(Map.class) - would fail there.
        // constructType resolves those to their raw form instead.
        boolean hasGenericParameterTypes = genericParameterTypes != null && genericParameterTypes.length > 0;
        return (JsonStringDeserializer<T>) deserializer(tf -> hasGenericParameterTypes ? tf.constructParametricType(type, genericParameterTypes)
                : tf.constructType(type));
    }

    public static <T> JsonStringDeserializer<T> deserializer(Function<TypeFactory, JavaType> typeFunction)
    {
        return new JsonStringDeserializer<T>() {
            private ObjectMapper                         objectMapper     = new ObjectMapper();
            private Function<ObjectMapper, ObjectReader> writerResolver   = om -> om.readerFor(typeFunction.apply(TypeFactory.defaultInstance()));
            private Consumer<Exception>                  exceptionHandler = e -> LOGGER.warn("Failed to deserialize json", e);

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
                return new JsonByteArrayDeserializer<T>() {
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
     * @see #deserializer(Class, Class...)
     * @param type
     * @return
     */
    public static <T> JsonReaderDeserializer<T> readerDeserializer(Class<? super T> type)
    {
        return new JsonReaderDeserializer<T>() {
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
                return new JsonReaderArrayDeserializer<T>() {
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
        return new JsonCloner<E>() {
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
        return new JsonStringConverter<T>() {
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

}
