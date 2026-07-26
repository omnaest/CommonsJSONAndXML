package org.omnaest.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
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
        StringWriter sink = new StringWriter()
        {
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
        StringReader reader = new StringReader("{\"field1\":\"value1\"}")
        {
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
        StringReader reader = new StringReader("[{\"field1\":\"value1\"}]")
        {
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
        StringReader reader = new StringReader("this is not json at all")
        {
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
        Map<String, Object> map = JsonUtils.<Map<String, Object>> deserializer(Map.class)
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
