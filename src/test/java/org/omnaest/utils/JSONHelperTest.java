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

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class JSONHelperTest
{
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

    }

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

}
