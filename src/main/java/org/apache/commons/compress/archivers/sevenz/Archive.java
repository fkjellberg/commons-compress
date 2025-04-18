/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.commons.compress.archivers.sevenz;

import java.util.BitSet;

final class Archive {

    private static String lengthOf(final long[] a) {
        return a == null ? "(null)" : Integer.toString(a.length);
    }

    private static String lengthOf(final Object[] a) {
        return a == null ? "(null)" : Integer.toString(a.length);
    }

    /** Offset from beginning of file + SIGNATURE_HEADER_SIZE to packed streams. */
    long packPos;

    /** Size of each packed stream. */
    long[] packSizes = {};

    /** Whether each particular packed streams has a CRC. */
    BitSet packCrcsDefined;

    /** CRCs for each packed stream, valid only if that packed stream has one. */
    long[] packCrcs;

    /** Properties of solid compression blocks. */
    Folder[] folders = Folder.EMPTY_FOLDER_ARRAY;

    /** Temporary properties for non-empty files (subsumed into the files array later). */
    SubStreamsInfo subStreamsInfo;

    /** The files and directories in the archive. */
    SevenZArchiveEntry[] files = SevenZArchiveEntry.EMPTY_SEVEN_Z_ARCHIVE_ENTRY_ARRAY;

    /** Mapping between folders, files and streams. */
    StreamMap streamMap;

    @Override
    public String toString() {
        return "Archive with packed streams starting at offset " + packPos + ", " + lengthOf(packSizes) + " pack sizes, " + lengthOf(packCrcs) + " CRCs, "
                + lengthOf(folders) + " folders, " + lengthOf(files) + " files and " + streamMap;
    }
}
