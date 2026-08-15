package org.omnaest.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonUtilsTest
{

    @Test
    public void testToMapO() throws Exception
    {
        Map<String, Object> map = JsonUtils.toMap(new Domain("value1"));
        Domain domain = JsonUtils.toObjectWithType(map, Domain.class);
        assertEquals("value1", domain.getField1());
    }

    @Test
    public void testClone() throws Exception
    {
        Domain clone = JsonUtils.clone(new Domain("value1"));
        assertNotNull(clone);
        assertEquals("value1", clone.getField1());
    }

    @Test
    public void testToObjectWithTypeMapper() throws Exception
    {
        Map<String, String> map = new HashMap<>();
        map.put("field1", "value1");
        Domain domain = JsonUtils.toObjectWithTypeMapper(Domain.class)
                                 .apply(map);

        assertEquals("value1", domain.getField1());
    }

    @Test
    public void testPrettyPrint() throws Exception
    {
        assertEquals("{\n  \"field1\" : \"value1\"\n}", JsonUtils.prettyPrint(new Domain("value1"))
                                                                 .replaceAll("[\\n\\r]+", "\n"));
    }

    @Test
    public void testSerializeObject() throws Exception
    {
        assertEquals("{\"field1\":\"value1\"}", JsonUtils.serialize(new Domain("value1")));
    }

    /**
     * {@link JsonUtils#serialize(Object, Writer, boolean)} has to flush the given {@link Writer} through, but
     * leave it open and owned by the caller. A {@link BufferedWriter} is used on purpose here: a bare
     * {@link StringWriter} cannot tell the two apart, since its {@code flush()} is a no-op and it stays
     * writable after {@code close()}.
     */
    @Test
    public void testSerializeObjectToWriterFlushesButDoesNotClose() throws Exception
    {
        AtomicBoolean closed = new AtomicBoolean();
        StringWriter sink = new StringWriter() {
            @Override
            public void close() throws IOException
            {
                closed.set(true);
                super.close();
            }
        };
        BufferedWriter writer = new BufferedWriter(sink);

        JsonUtils.serialize(new Domain("value1"), writer, false);

        // ... flushed all the way through without the caller having to close anything
        assertEquals("{\"field1\":\"value1\"}", sink.toString());

        // ... and still open, so the caller can keep appending to it
        assertFalse(closed.get());
        writer.write("!");
        writer.flush();
        assertEquals("{\"field1\":\"value1\"}!", sink.toString());
    }

    @Test
    public void testReadFromString() throws Exception
    {
        assertEquals(new Domain("value1"), JsonUtils.readFromString("{\"field1\":\"value1\"}", Domain.class));
    }

    @Test
    public void testReadFromStringWithNullAndEmptyInput() throws Exception
    {
        assertNull(JsonUtils.readFromString(null, Domain.class));
        assertNull(JsonUtils.readFromString("", Domain.class));
    }

    @Test
    public void testReadFromReader() throws Exception
    {
        assertEquals(new Domain("value1"), JsonUtils.readFromReader(new StringReader("{\"field1\":\"value1\"}"), Domain.class));
    }

    /**
     * The read paths share a single static {@link com.fasterxml.jackson.databind.ObjectMapper} with the
     * serialization paths, so a read must not be able to disturb what a subsequent write produces.
     */
    @Test
    public void testReadDoesNotAffectSubsequentNonPrettySerialization() throws Exception
    {
        JsonUtils.readFromString("{\"field1\":\"value1\"}", Domain.class);

        assertEquals("{\"field1\":\"value1\"}", JsonUtils.serialize(new Domain("value1")));

        StringWriter writer = new StringWriter();
        JsonUtils.serialize(new Domain("value1"), writer, false);
        assertEquals("{\"field1\":\"value1\"}", writer.toString());
    }

    /**
     * A root level element that is not an array has to fail, and must not leak the {@link java.io.Reader} the
     * parser was built on while doing so.
     */
    @Test
    public void testReadArrayFromReaderReleasesReaderOnNonArrayContent() throws Exception
    {
        AtomicBoolean closed = new AtomicBoolean();
        StringReader reader = new StringReader("{\"field1\":\"value1\"}") {
            @Override
            public void close()
            {
                closed.set(true);
                super.close();
            }
        };

        assertThrows(IllegalStateException.class, () -> JsonUtils.readArrayFromReader(reader, Domain.class));
        assertTrue(closed.get());
    }

    @Test
    public void testReadArrayFromReaderClosesReaderWhenStreamIsClosed() throws Exception
    {
        AtomicBoolean closed = new AtomicBoolean();
        StringReader reader = new StringReader("[{\"field1\":\"value1\"}]") {
            @Override
            public void close()
            {
                closed.set(true);
                super.close();
            }
        };

        try (Stream<Domain> stream = JsonUtils.readArrayFromReader(reader, Domain.class))
        {
            assertEquals(Arrays.asList(new Domain("value1")), stream.collect(Collectors.toList()));
        }
        assertTrue(closed.get());
    }

    @Test
    public void testReadArrayFromReaderWithNullReader() throws Exception
    {
        assertEquals(Arrays.asList(), JsonUtils.readArrayFromReader(null, Domain.class)
                                               .collect(Collectors.toList()));
    }

    /**
     * {@link java.util.Iterator#next()} beyond the end of the array has to fail with the exception its contract
     * demands, instead of trying to read a value off the closing bracket.
     */
    @Test
    public void testReadArrayIteratorRejectsNextBeyondEndOfArray() throws Exception
    {
        Iterator<Domain> iterator = JsonUtils.readArrayFromReader(new StringReader("[{\"field1\":\"value1\"}]"), Domain.class)
                                             .iterator();

        assertEquals(new Domain("value1"), iterator.next());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, () -> iterator.next());
    }

    /**
     * {@link JsonUtils#prepareAsReaderToObjectFunction(Class)} documents that it closes the
     * {@link java.io.Reader}, and documents a {@link JsonUtils.JSONDeserializationException} - both of which
     * have to hold on the failure path too. The close held there before as well, but only via Jackson's
     * AUTO_CLOSE_SOURCE default; the exception type did not, since it used to be rewrapped into an
     * {@link IllegalStateException}.
     */
    @Test
    public void testPrepareAsReaderToObjectFunctionClosesReaderOnFailure()
    {
        AtomicBoolean closed = new AtomicBoolean();
        StringReader reader = new StringReader("this is not json at all") {
            @Override
            public void close()
            {
                closed.set(true);
                super.close();
            }
        };

        assertThrows(JsonUtils.JSONDeserializationException.class, () -> JsonUtils.prepareAsReaderToObjectFunction(Domain.class)
                                                                                  .apply(reader));
        assertTrue(closed.get());
    }

    /**
     * A generic type without any given type parameters has to resolve to its raw form rather than making
     * Jackson's {@code constructParametricType} complain about the parameter count.
     */
    @Test
    public void testDeserializerWithGenericTypeAndNoGenericParameterTypes() throws Exception
    {
        Map<String, Object> map = JsonUtils.<Map<String, Object>>deserializer(Map.class)
                                           .apply("{\"field1\":\"value1\"}");

        assertEquals("value1", map.get("field1"));
    }

    @Test
    public void testReadArrayFromReader() throws Exception
    {
        assertEquals(Arrays.asList(new Domain("value1"), new Domain("value2")), JsonUtils.readerDeserializer(Domain.class)
                                                                                         .forArray()
                                                                                         .apply(new StringReader("[{\"field1\":\"value1\"},{\"field1\":\"value2\"}]"))
                                                                                         .collect(Collectors.toList()));
    }

    /**
     * The single object and the array variant of {@link JsonUtils#writerSerializer(Class)} have to agree on the
     * output format; the single object one used to pretty print while the array one did not.
     */
    @Test
    public void testWriteObjectToWriterIsCompactLikeTheArrayVariant() throws Exception
    {
        StringWriter writer = new StringWriter();
        JsonUtils.writerSerializer(Domain.class)
                 .withWriter(writer)
                 .accept(new Domain("value1"));

        assertEquals("{\"field1\":\"value1\"}", writer.toString());
    }

    @Test
    public void testWriteArrayToWriter() throws Exception
    {
        StringWriter writer = new StringWriter();
        JsonUtils.writerSerializer(Domain.class)
                 .forArray()
                 .accept(Stream.of(new Domain("value1"), new Domain("value2")), writer);
        writer.close();
        assertEquals("[{\"field1\":\"value1\"},{\"field1\":\"value2\"}]", writer.toString());
    }

    /**
     * Pins the "deliberate asymmetry" the {@link JsonUtils#serializer(Class)} javadoc calls out:
     * {@link JsonUtils#serializer(Class)} (no boolean) defaults to pretty printing while
     * {@link JsonUtils#serializer(Class, boolean)} with {@code false} produces compact output identical in shape
     * to {@link #testSerializeObject()}'s baseline.
     */
    @Test
    public void testSerializerClassDefaultsToPrettyPrintAsymmetricToSerializeObject() throws Exception
    {
        assertEquals("{\n  \"field1\" : \"value1\"\n}", JsonUtils.<Domain>serializer(Domain.class)
                                                                 .apply(new Domain("value1"))
                                                                 .replaceAll("[\\n\\r]+", "\n"));

        assertEquals("{\"field1\":\"value1\"}", JsonUtils.<Domain>serializer(Domain.class, false)
                                                         .apply(new Domain("value1")));
    }

    @Test
    public void testSerializeArrayCompact() throws Exception
    {
        StringWriter writer = new StringWriter();
        JsonUtils.serializeArray(Stream.of(new Domain("value1"), new Domain("value2")), writer, false);
        assertEquals("[{\"field1\":\"value1\"},{\"field1\":\"value2\"}]", writer.toString());
    }

    /**
     * Pretty-printed output embeds the system line separator ({@code DefaultIndenter}'s default), which is
     * {@code \r\n} on Windows - normalised here the same way {@link #testPrettyPrint()} already does, so the
     * assertion stays exact and portable instead of only passing on this platform.
     */
    @Test
    public void testSerializeArrayPretty() throws Exception
    {
        StringWriter writer = new StringWriter();
        JsonUtils.serializeArray(Stream.of(new Domain("value1"), new Domain("value2")), writer, true);
        assertEquals("[ {\n  \"field1\" : \"value1\"\n}, {\n  \"field1\" : \"value2\"\n} ]", writer.toString()
                                                                                                   .replaceAll("[\\n\\r]+", "\n"));
    }

    /**
     * {@link JsonUtils#serializeArray(Stream, Writer, boolean)} is documented to match
     * {@link JsonUtils#serialize(Object, Writer, boolean)}: flush through, leave the writer open. Reuses the
     * recording-writer technique from {@link #testSerializeObjectToWriterFlushesButDoesNotClose()}.
     */
    @Test
    public void testSerializeArrayFlushesButDoesNotClose() throws Exception
    {
        AtomicBoolean closed = new AtomicBoolean();
        StringWriter sink = new StringWriter() {
            @Override
            public void close() throws IOException
            {
                closed.set(true);
                super.close();
            }
        };
        BufferedWriter writer = new BufferedWriter(sink);

        JsonUtils.serializeArray(Stream.of(new Domain("value1")), writer, false);

        assertEquals("[{\"field1\":\"value1\"}]", sink.toString());
        assertFalse(closed.get());

        writer.write("!");
        writer.flush();
        assertEquals("[{\"field1\":\"value1\"}]!", sink.toString());
    }

    @Test
    public void testSerializeArrayWithEmptyAndNullStream() throws Exception
    {
        StringWriter emptyStreamWriter = new StringWriter();
        JsonUtils.serializeArray(Stream.empty(), emptyStreamWriter, false);
        assertEquals("[]", emptyStreamWriter.toString());

        StringWriter nullStreamWriter = new StringWriter();
        JsonUtils.serializeArray(null, nullStreamWriter, false);
        assertEquals("[]", nullStreamWriter.toString());
    }

    @Test
    public void testPrepareAsPrettyPrintWriterConsumerProducesIndentedJson() throws Exception
    {
        StringWriter writer = new StringWriter();
        JsonUtils.prepareAsPrettyPrintWriterConsumer(new Domain("value1"))
                 .accept(writer);
        assertEquals("{\n  \"field1\" : \"value1\"\n}", writer.toString()
                                                              .replaceAll("[\\n\\r]+", "\n"));
    }

    /**
     * The documented opposite of {@link #testSerializeArrayFlushesButDoesNotClose()} and
     * {@link #testSerializeObjectToWriterFlushesButDoesNotClose()}:
     * {@link JsonUtils#prepareAsPrettyPrintWriterConsumer(Object)} does close the writer.
     */
    @Test
    public void testPrepareAsPrettyPrintWriterConsumerClosesWriter() throws Exception
    {
        AtomicBoolean closed = new AtomicBoolean();
        StringWriter sink = new StringWriter() {
            @Override
            public void close() throws IOException
            {
                closed.set(true);
                super.close();
            }
        };

        JsonUtils.prepareAsPrettyPrintWriterConsumer(new Domain("value1"))
                 .accept(sink);

        assertTrue(closed.get());
    }

    /**
     * Currently zero coverage (plan-125 AC1.11). Round-trips a value through {@link JsonUtils#deserializer},
     * then proves {@link JsonUtils.JsonStringDeserializer#withKeyDeserializer(Class, com.fasterxml.jackson.databind.KeyDeserializer)}
     * actually reaches the parser: the custom {@link PrefixingKeyDeserializer} prefixes every map key with
     * {@code "KEY:"}, so the assertion fails loudly if the handler were silently ignored.
     */
    @Test
    public void testDeserializerRoundTripsAndKeyDeserializerChangesParsedKey() throws Exception
    {
        Domain domain = JsonUtils.<Domain>deserializer(Domain.class)
                                 .apply("{\"field1\":\"value1\"}");
        assertEquals(new Domain("value1"), domain);

        Map<String, String> map = JsonUtils.<Map<String, String>>deserializer(Map.class, String.class, String.class)
                                           .withKeyDeserializer(String.class, new PrefixingKeyDeserializer())
                                           .apply("{\"abc\":\"x\"}");

        assertTrue(map.containsKey("KEY:abc"));
        assertFalse(map.containsKey("abc"));
    }

    /**
     * Currently zero coverage (plan-125 AC1.12). A clone is equal-but-not-same, and both
     * {@link JsonUtils.JsonCloner#usingKeyDeserializer} and {@link JsonUtils.JsonCloner#withKeySerializer} take
     * effect on the clone - proven the same way as AC1.11, by a prefix that must survive into the result.
     */
    @Test
    public void testClonerReturnsEqualButNotSameInstanceAndKeyHandlersTakeEffectOnClone() throws Exception
    {
        Domain original = new Domain("value1");
        Domain clone = JsonUtils.cloner(Domain.class)
                                .apply(original);
        assertEquals(original, clone);
        assertNotSame(original, clone);

        Map<String, String> map = new HashMap<>();
        map.put("abc", "x");

        MapHolder deserializerClone = JsonUtils.cloner(MapHolder.class)
                                               .usingKeyDeserializer(String.class, new PrefixingKeyDeserializer())
                                               .apply(new MapHolder(map));
        assertTrue(deserializerClone.getMap()
                                    .containsKey("KEY:abc"));

        MapHolder serializerClone = JsonUtils.cloner(MapHolder.class)
                                             .withKeySerializer(String.class, new PrefixingKeySerializer())
                                             .apply(new MapHolder(map));
        assertTrue(serializerClone.getMap()
                                  .containsKey("KEY:abc"));
    }

    /**
     * Currently zero coverage (plan-125 AC1.13). {@link JsonUtils.JsonStringConverter#serializer()} hands out
     * the same instance on every call, and a reconfiguration through one retrieval is visible through another -
     * the shared-mutable-instance semantics the copy-on-mutate redesign must preserve exactly.
     */
    @Test
    public void testConverterSharesSameSerializerInstanceAcrossRetrievals() throws Exception
    {
        JsonUtils.JsonStringConverter<MapHolder> converter = JsonUtils.converter(MapHolder.class);

        assertSame(converter.serializer(), converter.serializer());

        converter.serializer()
                 .withKeySerializer(String.class, new PrefixingKeySerializer());

        Map<String, String> map = new HashMap<>();
        map.put("abc", "x");

        String json = converter.serializer()
                               .apply(new MapHolder(map));

        assertTrue(json.contains("\"KEY:abc\""));
    }

    /**
     * Currently zero coverage (plan-125 AC1.14). A weak {@code assertNotNull} would not catch the key
     * serializer being ignored; asserting the transformed key form does.
     */
    @Test
    public void testSerializerWithKeySerializerChangesMapKey() throws Exception
    {
        Map<String, String> map = new HashMap<>();
        map.put("abc", "x");

        String json = JsonUtils.<MapHolder>serializer()
                               .withKeySerializer(String.class, new PrefixingKeySerializer())
                               .apply(new MapHolder(map));

        assertTrue(json.contains("\"KEY:abc\""));
        assertFalse(json.contains("\"abc\":"));
    }

    /**
     * Pins the invariant Cliff 1 of plan-125 depends on: the mapper is read at use time, not captured at
     * configuration time, so {@code withPrettyPrint} and {@code withKeySerializer} may be called in either
     * order on one instance and produce identical output.
     */
    @Test
    public void testSerializerConfigureInAnyOrderProducesIdenticalOutput() throws Exception
    {
        Map<String, String> map = new HashMap<>();
        map.put("abc", "x");
        MapHolder holder = new MapHolder(map);

        JsonUtils.JsonStringSerializer<MapHolder> prettyThenKey = JsonUtils.serializer();
        prettyThenKey.withPrettyPrint(true);
        prettyThenKey.withKeySerializer(String.class, new PrefixingKeySerializer());

        JsonUtils.JsonStringSerializer<MapHolder> keyThenPretty = JsonUtils.serializer();
        keyThenPretty.withKeySerializer(String.class, new PrefixingKeySerializer());
        keyThenPretty.withPrettyPrint(true);

        String outputA = prettyThenKey.apply(holder)
                                      .replaceAll("[\\n\\r]+", "\n");
        String outputB = keyThenPretty.apply(holder)
                                      .replaceAll("[\\n\\r]+", "\n");

        assertEquals(outputA, outputB);
        assertTrue(outputA.contains("KEY:abc"));
    }

    /**
     * AC2.1 (plan-125 Slice 2), absolute shared-mapper invariant. Exercises EVERY mutating entry point in the
     * file - both serializer() and both cloner() mutators, the deserializer() mutator, plus the two Part-B
     * writer paths that were reworked onto the shared mapper (serializeArray(...,true) and
     * prepareAsPrettyPrintWriterConsumer(...)) - then asserts, by reflective read of the private static
     * {@code SHARED_OBJECT_MAPPER} field, that its registered-module set is empty. Deliberately an absolute
     * assertion rather than a before/after equality: a before/after comparison would be inert to any
     * corruption that is constant across both reads.
     * <p>
     * Intentional coupling to internal structure (design guideline P12, plan-125 section 2.4 "Recorded
     * coupling"): no behavioural observation can distinguish "reused the shared mapper" from "constructed an
     * identical fresh one", so a reflective read of the private field name {@code SHARED_OBJECT_MAPPER} is the
     * only available discriminator. This test must fail against an implementation that calls
     * {@code registerModule} directly on the shared mapper instead of on a private copy.
     */
    @Test
    public void testSharedObjectMapperNeverAccumulatesRegisteredModules() throws Exception
    {
        Map<String, String> map = new HashMap<>();
        map.put("abc", "x");

        JsonUtils.<MapHolder>serializer()
                 .withKeySerializer(String.class, new PrefixingKeySerializer())
                 .apply(new MapHolder(map));

        JsonUtils.<Map<String, String>>deserializer(Map.class, String.class, String.class)
                 .withKeyDeserializer(String.class, new PrefixingKeyDeserializer())
                 .apply("{\"abc\":\"x\"}");

        JsonUtils.cloner(MapHolder.class)
                 .usingKeyDeserializer(String.class, new PrefixingKeyDeserializer())
                 .apply(new MapHolder(map));

        JsonUtils.cloner(MapHolder.class)
                 .withKeySerializer(String.class, new PrefixingKeySerializer())
                 .apply(new MapHolder(map));

        StringWriter arrayWriter = new StringWriter();
        JsonUtils.serializeArray(Stream.of(new Domain("value1")), arrayWriter, true);

        StringWriter consumerWriter = new StringWriter();
        JsonUtils.prepareAsPrettyPrintWriterConsumer(new Domain("value1"))
                 .accept(consumerWriter);

        ObjectMapper sharedObjectMapper = readSharedObjectMapper();
        assertTrue("SHARED_OBJECT_MAPPER must never accumulate a registered module", sharedObjectMapper.getRegisteredModuleIds()
                                                                                                       .isEmpty());
    }

    /**
     * AC2.2 (plan-125 Slice 2). No per-call mapper on the common (unconfigured) path: the private
     * {@code objectMapper} field held by an unconfigured serializer(), deserializer(...) and cloner(...) is
     * reflectively read and must be the SAME instance as {@code SHARED_OBJECT_MAPPER}, and two successive
     * serializer() calls must both hold that same instance.
     * <p>
     * Intentional coupling to internal structure, same rationale as
     * {@link #testSharedObjectMapperNeverAccumulatesRegisteredModules()} - see that javadoc and plan-125
     * section 2.4 "Recorded coupling". This test must fail against an implementation that does
     * {@code new ObjectMapper()} per call, and equally against one that does
     * {@code SHARED_OBJECT_MAPPER.copy()} unconditionally per call - both produce a behaviourally identical
     * but NOT identity-same mapper, which only a reflective identity read can tell apart from the real thing.
     */
    @Test
    public void testUnconfiguredEntryPointsShareTheSharedObjectMapperInstance() throws Exception
    {
        ObjectMapper sharedObjectMapper = readSharedObjectMapper();

        Object serializer1 = JsonUtils.serializer();
        Object serializer2 = JsonUtils.serializer();
        Object deserializer = JsonUtils.deserializer(Domain.class);
        Object cloner = JsonUtils.cloner(Domain.class);

        assertSame(sharedObjectMapper, readObjectMapperField(serializer1));
        assertSame(sharedObjectMapper, readObjectMapperField(serializer2));
        assertSame(sharedObjectMapper, readObjectMapperField(deserializer));
        assertSame(sharedObjectMapper, readObjectMapperField(cloner));
    }

    /**
     * AC2.3 (plan-125 Slice 2). Isolation across instances: configuring one serializer()/deserializer()/
     * cloner() instance must not be visible through a separately obtained instance of the same kind, nor
     * through the static {@link JsonUtils#serialize(Object)} convenience method. This is the exact bug the
     * copy-on-mutate design could introduce - a leaked module registration on the shared mapper would make
     * every one of these assertions fail together.
     */
    @Test
    public void testMutatingOneInstanceDoesNotAffectASeparatelyObtainedInstance() throws Exception
    {
        Map<String, String> map = new HashMap<>();
        map.put("abc", "x");

        // serializer()
        JsonUtils.JsonStringSerializer<MapHolder> configuredSerializer = JsonUtils.serializer();
        configuredSerializer.withKeySerializer(String.class, new PrefixingKeySerializer());
        assertTrue(configuredSerializer.apply(new MapHolder(map))
                                       .contains("\"KEY:abc\""));

        String freshSerializerJson = JsonUtils.<MapHolder>serializer()
                                              .apply(new MapHolder(map));
        assertTrue(freshSerializerJson.contains("\"abc\":"));
        assertFalse(freshSerializerJson.contains("KEY:abc"));

        String staticSerializeJson = JsonUtils.serialize(new MapHolder(map));
        assertTrue(staticSerializeJson.contains("\"abc\":"));
        assertFalse(staticSerializeJson.contains("KEY:abc"));

        // deserializer(...)
        JsonUtils.JsonStringDeserializer<Map<String, String>> configuredDeserializer = JsonUtils.deserializer(Map.class, String.class, String.class);
        configuredDeserializer.withKeyDeserializer(String.class, new PrefixingKeyDeserializer());
        assertTrue(configuredDeserializer.apply("{\"abc\":\"x\"}")
                                         .containsKey("KEY:abc"));

        Map<String, String> freshMap = JsonUtils.<Map<String, String>>deserializer(Map.class, String.class, String.class)
                                                .apply("{\"abc\":\"x\"}");
        assertTrue(freshMap.containsKey("abc"));
        assertFalse(freshMap.containsKey("KEY:abc"));

        // cloner(...).usingKeyDeserializer(...)
        JsonUtils.JsonCloner<MapHolder> configuredDeserializerCloner = JsonUtils.cloner(MapHolder.class);
        configuredDeserializerCloner.usingKeyDeserializer(String.class, new PrefixingKeyDeserializer());
        assertTrue(configuredDeserializerCloner.apply(new MapHolder(map))
                                               .getMap()
                                               .containsKey("KEY:abc"));

        MapHolder freshDeserializerClone = JsonUtils.cloner(MapHolder.class)
                                                    .apply(new MapHolder(map));
        assertTrue(freshDeserializerClone.getMap()
                                         .containsKey("abc"));
        assertFalse(freshDeserializerClone.getMap()
                                          .containsKey("KEY:abc"));

        // cloner(...).withKeySerializer(...)
        JsonUtils.JsonCloner<MapHolder> configuredSerializerCloner = JsonUtils.cloner(MapHolder.class);
        configuredSerializerCloner.withKeySerializer(String.class, new PrefixingKeySerializer());
        assertTrue(configuredSerializerCloner.apply(new MapHolder(map))
                                             .getMap()
                                             .containsKey("KEY:abc"));

        MapHolder freshSerializerClone = JsonUtils.cloner(MapHolder.class)
                                                  .apply(new MapHolder(map));
        assertTrue(freshSerializerClone.getMap()
                                       .containsKey("abc"));
        assertFalse(freshSerializerClone.getMap()
                                        .containsKey("KEY:abc"));
    }

    /**
     * AC2.4 (plan-125 Slice 2). Copy-once, registrations accumulate: two DIFFERENT key serializers registered
     * on ONE serializer instance both take effect. This test must fail against an implementation that assigns
     * {@code SHARED_OBJECT_MAPPER.copy()} on every mutation rather than only on the first - that
     * implementation would discard the first registration (String) when the second (Long) copies fresh from
     * the still-unmutated shared mapper, so only the Long key would come out prefixed.
     */
    @Test
    public void testTwoDifferentKeySerializersRegisteredOnOneInstanceBothTakeEffect() throws Exception
    {
        Map<String, String> stringKeyedMap = new HashMap<>();
        stringKeyedMap.put("abc", "x");
        Map<Long, String> longKeyedMap = new HashMap<>();
        longKeyedMap.put(42L, "y");

        JsonUtils.JsonStringSerializer<DualKeyHolder> serializer = JsonUtils.serializer();
        serializer.withKeySerializer(String.class, new PrefixingKeySerializer());
        serializer.withKeySerializer(Long.class, new PrefixingLongKeySerializer());

        String json = serializer.apply(new DualKeyHolder(stringKeyedMap, longKeyedMap));

        assertTrue(json.contains("\"KEY:abc\""));
        assertTrue(json.contains("\"LONGKEY:42\""));
    }

    private static ObjectMapper readSharedObjectMapper() throws Exception
    {
        Field field = JsonUtils.class.getDeclaredField("SHARED_OBJECT_MAPPER");
        field.setAccessible(true);
        return (ObjectMapper) field.get(null);
    }

    private static ObjectMapper readObjectMapperField(Object target) throws Exception
    {
        Field field = target.getClass()
                            .getDeclaredField("objectMapper");
        field.setAccessible(true);
        return (ObjectMapper) field.get(target);
    }

    /**
     * Key handler used by AC1.11-AC1.15: prefixes every map key with {@code "KEY:"} so that an assertion on the
     * prefixed key fails loudly if the custom (de)serializer path were silently ignored - a weaker
     * {@code assertNotNull}-grade check would not catch that.
     */
    private static final class PrefixingKeySerializer extends com.fasterxml.jackson.databind.JsonSerializer<String>
    {
        @Override
        public void serialize(String value, com.fasterxml.jackson.core.JsonGenerator gen, com.fasterxml.jackson.databind.SerializerProvider serializers) throws IOException
        {
            gen.writeFieldName("KEY:" + value);
        }
    }

    private static final class PrefixingKeyDeserializer extends com.fasterxml.jackson.databind.KeyDeserializer
    {
        @Override
        public Object deserializeKey(String key, com.fasterxml.jackson.databind.DeserializationContext ctxt) throws IOException
        {
            return "KEY:" + key;
        }
    }

    /**
     * Second key serializer used only by {@link #testTwoDifferentKeySerializersRegisteredOnOneInstanceBothTakeEffect()},
     * for a DIFFERENT key type ({@link Long}) than {@link PrefixingKeySerializer} ({@link String}), so that
     * both registrations landing on the same underlying {@link ObjectMapper} copy can be told apart.
     */
    private static final class PrefixingLongKeySerializer extends com.fasterxml.jackson.databind.JsonSerializer<Long>
    {
        @Override
        public void serialize(Long value, com.fasterxml.jackson.core.JsonGenerator gen, com.fasterxml.jackson.databind.SerializerProvider serializers) throws IOException
        {
            gen.writeFieldName("LONGKEY:" + value);
        }
    }

    /**
     * Fixture for {@link #testTwoDifferentKeySerializersRegisteredOnOneInstanceBothTakeEffect()}: holds two
     * maps with different key types so that two DIFFERENT key serializers registered on one
     * {@link JsonUtils.JsonStringSerializer} instance can both be exercised in a single {@code apply(...)}.
     */
    protected static class DualKeyHolder
    {
        private Map<String, String> stringKeyedMap;
        private Map<Long, String>   longKeyedMap;

        public DualKeyHolder()
        {
            super();
        }

        public DualKeyHolder(Map<String, String> stringKeyedMap, Map<Long, String> longKeyedMap)
        {
            super();
            this.stringKeyedMap = stringKeyedMap;
            this.longKeyedMap = longKeyedMap;
        }

        public Map<String, String> getStringKeyedMap()
        {
            return this.stringKeyedMap;
        }

        public void setStringKeyedMap(Map<String, String> stringKeyedMap)
        {
            this.stringKeyedMap = stringKeyedMap;
        }

        public Map<Long, String> getLongKeyedMap()
        {
            return this.longKeyedMap;
        }

        public void setLongKeyedMap(Map<Long, String> longKeyedMap)
        {
            this.longKeyedMap = longKeyedMap;
        }
    }

    protected static class MapHolder
    {
        private Map<String, String> map;

        public MapHolder()
        {
            super();
        }

        public MapHolder(Map<String, String> map)
        {
            super();
            this.map = map;
        }

        public Map<String, String> getMap()
        {
            return this.map;
        }

        public void setMap(Map<String, String> map)
        {
            this.map = map;
        }
    }

    protected static class Domain
    {
        private String field1;

        @JsonCreator
        public Domain(@JsonProperty("field1") String field1)
        {
            super();
            this.field1 = field1;
        }

        public String getField1()
        {
            return this.field1;
        }

        @Override
        public String toString()
        {
            return "Domain [field1=" + this.field1 + "]";
        }

        @Override
        public int hashCode()
        {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((this.field1 == null) ? 0 : this.field1.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
            {
                return true;
            }
            if (obj == null)
            {
                return false;
            }
            if (this.getClass() != obj.getClass())
            {
                return false;
            }
            Domain other = (Domain) obj;
            if (this.field1 == null)
            {
                if (other.field1 != null)
                {
                    return false;
                }
            }
            else if (!this.field1.equals(other.field1))
            {
                return false;
            }
            return true;
        }

    }

}
