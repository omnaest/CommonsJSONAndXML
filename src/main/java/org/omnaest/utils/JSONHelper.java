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

import java.util.function.Function;

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

	public static <T> T readFromString(String data, Class<T> type)
	{
		return readJson((objectMapper) ->
		{
			try
			{
				return objectMapper.readValue(data, type);
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
		}
		return retval;
	}

}
