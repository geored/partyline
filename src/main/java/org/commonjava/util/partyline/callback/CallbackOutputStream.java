/**
 * Copyright (C) 2015 Red Hat, Inc. (jdcasey@commonjava.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*******************************************************************************
* Copyright (c) 2015 Red Hat, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the GNU Public License v3.0
* which accompanies this distribution, and is available at
* http://www.gnu.org/licenses/gpl.html
*
* Contributors:
* Red Hat, Inc. - initial API and implementation
******************************************************************************/
package org.commonjava.util.partyline.callback;

import java.io.IOException;
import java.io.OutputStream;

public class CallbackOutputStream
    extends OutputStream
{

    private final OutputStream delegate;

    private final StreamCallbacks callbacks;

    public CallbackOutputStream( final OutputStream delegate, final StreamCallbacks callbacks )
    {
        this.delegate = delegate;
        this.callbacks = callbacks;
    }

    @Override
    public void close()
        throws IOException
    {
        delegate.close();
        callbacks.closed();
    }

    @Override
    public void flush()
        throws IOException
    {
        delegate.flush();
        callbacks.flushed();
    }

    @Override
    public String toString()
    {
        return "Callback-wrapped: " + delegate.toString();
    }

    @Override
    public void write( final int b )
        throws IOException
    {
        delegate.write( b );
    }

    @Override
    public void write( final byte[] b )
        throws IOException
    {
        delegate.write( b );
    }

    @Override
    public void write( final byte[] b, final int off, final int len )
        throws IOException
    {
        delegate.write( b, off, len );
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + ( ( delegate == null ) ? 0 : delegate.hashCode() );
        return result;
    }

    @Override
    public boolean equals( final Object obj )
    {
        if ( this == obj )
        {
            return true;
        }
        if ( obj == null )
        {
            return false;
        }
        if ( getClass() != obj.getClass() )
        {
            return false;
        }
        final CallbackOutputStream other = (CallbackOutputStream) obj;
        if ( delegate == null )
        {
            if ( other.delegate != null )
            {
                return false;
            }
        }
        else if ( !delegate.equals( other.delegate ) )
        {
            return false;
        }
        return true;
    }

}
