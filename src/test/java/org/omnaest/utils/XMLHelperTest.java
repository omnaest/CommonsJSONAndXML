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

import org.junit.Test;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlRootElement;

public class XMLHelperTest
{

    @Test
    public void testParse() throws Exception
    {
        String xml = "<input>test</input>";
        JAXBElement<?> element = XMLHelper.parse(xml, JAXBElement.class);
        //		System.out.println(XMLHelper.serializer()
        //									.withoutHeader()
        //									.serialize(element));
        assertNotNull(element);
    }

    @XmlRootElement
    @XmlAccessorType(XmlAccessType.NONE)
    public static class Domain
    {
        @XmlAttribute
        private String attr;

        public String getAttr()
        {
            return this.attr;
        }

        public void setAttr(String attr)
        {
            this.attr = attr;
        }

    }

    @Test
    public void testParseDomain()
    {
        String xml = "<domain attr=\"value\"></domain>";
        Domain element = XMLHelper.parse(xml, Domain.class);
        assertNotNull(element);
        assertEquals("value", element.getAttr());
    }

}
