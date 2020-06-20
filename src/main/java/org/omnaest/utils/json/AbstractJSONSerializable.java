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
