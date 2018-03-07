/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.index.schema;

import org.junit.Test;

import java.time.ZoneId;

import org.neo4j.values.storable.DateTimeValue;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.Values;

import static java.lang.String.format;
import static org.junit.Assert.assertEquals;

public class ZonedDateTimeSchemaKeyTest
{
    @Test
    public void compareToSameAsValue()
    {
        Value[] values = {DateTimeValue.datetime( 9999, 100, ZoneId.of( "+18:00" ) ),
                          DateTimeValue.datetime( 10000, 100, ZoneId.of( "-18:00" ) ),
                          DateTimeValue.datetime( 10000, 100, ZoneId.of( "UTC" ) ),
                          DateTimeValue.datetime( 10000, 100, ZoneId.of( "Europe/Stockholm" ) ),
                          DateTimeValue.datetime( 10000, 100, ZoneId.of( "+01:00" ) ),
                          DateTimeValue.datetime( 10000, 100, ZoneId.of( "+03:00" ) ),
                          DateTimeValue.datetime( 10000, 101, ZoneId.of( "-18:00" ) )};

        ZonedDateTimeSchemaKey keyI = new ZonedDateTimeSchemaKey();
        ZonedDateTimeSchemaKey keyJ = new ZonedDateTimeSchemaKey();

        for ( Value vi : values )
        {
            for ( Value vj : values )
            {
                vi.writeTo( keyI );
                vj.writeTo( keyJ );

                int expected = Values.COMPARATOR.compare( vi, vj );
                assertEquals( format( "comparing %s and %s", vi, vj ), expected, keyI.compareValueTo( keyJ ) );
            }
        }
    }
}
