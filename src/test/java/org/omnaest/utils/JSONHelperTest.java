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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class JSONHelperTest
{
    @Test
    public void testToMapO() throws Exception
    {
        Map<String, Object> map = JSONHelper.toMap(new Domain("value1"));
        Domain domain = JSONHelper.toObjectWithType(map, Domain.class);
        assertEquals("value1", domain.getField1());
    }

    @Test
    public void testClone() throws Exception
    {
        Domain clone = JSONHelper.clone(new Domain("value1"));
        assertNotNull(clone);
        assertEquals("value1", clone.getField1());
    }

    @Test
    public void testToObjectWithTypeMapper() throws Exception
    {
        Map<String, String> map = new HashMap<>();
        map.put("field1", "value1");
        Domain domain = JSONHelper.toObjectWithTypeMapper(Domain.class)
                                  .apply(map);

        assertEquals("value1", domain.getField1());
    }

    @Test
    public void testPrettyPrint() throws Exception
    {
        assertEquals("{\n  \"field1\" : \"value1\"\n}", JSONHelper.prettyPrint(new Domain("value1"))
                                                                  .replaceAll("[\\n\\r]+", "\n"));
    }

    @Test
    public void testSerializeObject() throws Exception
    {
        assertEquals("{\"field1\":\"value1\"}", JSONHelper.serialize(new Domain("value1")));
    }

    @Test
    public void testReadArrayFromReader() throws Exception
    {
        assertEquals(Arrays.asList(new Domain("value1"), new Domain("value2")), JSONHelper.readerDeserializer(Domain.class)
                                                                                          .forArray()
                                                                                          .apply(new StringReader("[{\"field1\":\"value1\"},{\"field1\":\"value2\"}]"))
                                                                                          .collect(Collectors.toList()));
    }

    @Test
    public void testWriteArrayToWriter() throws Exception
    {
        StringWriter writer = new StringWriter();
        JSONHelper.writerSerializer(Domain.class)
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
