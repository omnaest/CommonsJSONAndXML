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
package org.omnaest.utils.json;

import org.omnaest.utils.JSONHelper;

/**
 * Defines the {@link #toString()} method to return json output of this instance
 * 
 * @author omnaest
 */
public abstract class AbstractJSONSerializable
{
    @Override
    public String toString()
    {
        return JSONHelper.prettyPrint(this);
    }
}
