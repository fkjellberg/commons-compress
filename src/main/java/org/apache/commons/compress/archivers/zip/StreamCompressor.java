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

package org.apache.commons.compress.archivers.zip;

import java.io.Closeable;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.parallel.ScatterGatherBackingStore;

/**
 * Encapsulates a {@link Deflater} and CRC calculator, handling multiple types of output streams. Currently {@link java.util.zip.ZipEntry#DEFLATED} and
 * {@link java.util.zip.ZipEntry#STORED} are the only supported compression methods.
 *
 * @since 1.10
 */
public abstract class StreamCompressor implements Closeable {

    private static final class DataOutputCompressor extends StreamCompressor {

        private final DataOutput raf;

        DataOutputCompressor(final Deflater deflater, final DataOutput raf) {
            super(deflater);
            this.raf = raf;
        }

        @Override
        protected void writeOut(final byte[] data, final int offset, final int length) throws IOException {
            raf.write(data, offset, length);
        }
    }

    private static final class OutputStreamCompressor extends StreamCompressor {

        private final OutputStream os;

        OutputStreamCompressor(final Deflater deflater, final OutputStream os) {
            super(deflater);
            this.os = os;
        }

        @Override
        protected void writeOut(final byte[] data, final int offset, final int length) throws IOException {
            os.write(data, offset, length);
        }
    }

    private static final class ScatterGatherBackingStoreCompressor extends StreamCompressor {

        private final ScatterGatherBackingStore bs;

        ScatterGatherBackingStoreCompressor(final Deflater deflater, final ScatterGatherBackingStore bs) {
            super(deflater);
            this.bs = bs;
        }

        @Override
        protected void writeOut(final byte[] data, final int offset, final int length) throws IOException {
            bs.writeOut(data, offset, length);
        }
    }

    private static final class SeekableByteChannelCompressor extends StreamCompressor {

        private final SeekableByteChannel channel;

        SeekableByteChannelCompressor(final Deflater deflater, final SeekableByteChannel channel) {
            super(deflater);
            this.channel = channel;
        }

        @Override
        protected void writeOut(final byte[] data, final int offset, final int length) throws IOException {
            channel.write(ByteBuffer.wrap(data, offset, length));
        }
    }

    /**
     * Apparently Deflater.setInput gets slowed down a lot on Sun JVMs when it gets handed a huge buffer. See
     * https://issues.apache.org/bugzilla/show_bug.cgi?id=45396
     *
     * Using a buffer size of {@value} bytes proved to be a good compromise
     */
    private static final int DEFLATER_BLOCK_SIZE = 8192;
    private static final int BUFFER_SIZE = 4096;

    /**
     * Creates a stream compressor with the given compression level.
     *
     * @param os       The DataOutput to receive output
     * @param deflater The deflater to use for the compressor
     * @return A stream compressor
     */
    static StreamCompressor create(final DataOutput os, final Deflater deflater) {
        return new DataOutputCompressor(deflater, os);
    }

    /**
     * Creates a stream compressor with the given compression level.
     *
     * @param compressionLevel The {@link Deflater} compression level
     * @param bs               The ScatterGatherBackingStore to receive output
     * @return A stream compressor
     */
    public static StreamCompressor create(final int compressionLevel, final ScatterGatherBackingStore bs) {
        final Deflater deflater = new Deflater(compressionLevel, true);
        return new ScatterGatherBackingStoreCompressor(deflater, bs);
    }

    /**
     * Creates a stream compressor with the default compression level.
     *
     * @param os The stream to receive output
     * @return A stream compressor
     */
    static StreamCompressor create(final OutputStream os) {
        return create(os, new Deflater(Deflater.DEFAULT_COMPRESSION, true));
    }

    /**
     * Creates a stream compressor with the given compression level.
     *
     * @param os       The stream to receive output
     * @param deflater The deflater to use
     * @return A stream compressor
     */
    static StreamCompressor create(final OutputStream os, final Deflater deflater) {
        return new OutputStreamCompressor(deflater, os);
    }

    /**
     * Creates a stream compressor with the default compression level.
     *
     * @param bs The ScatterGatherBackingStore to receive output
     * @return A stream compressor
     */
    public static StreamCompressor create(final ScatterGatherBackingStore bs) {
        return create(Deflater.DEFAULT_COMPRESSION, bs);
    }

    /**
     * Creates a stream compressor with the given compression level.
     *
     * @param os       The SeekableByteChannel to receive output
     * @param deflater The deflater to use for the compressor
     * @return A stream compressor
     * @since 1.13
     */
    static StreamCompressor create(final SeekableByteChannel os, final Deflater deflater) {
        return new SeekableByteChannelCompressor(deflater, os);
    }

    private final Deflater deflater;
    private final CRC32 crc = new CRC32();
    private long writtenToOutputStreamForLastEntry;
    private long sourcePayloadLength;
    private long totalWrittenToOutputStream;
    private final byte[] outputBuffer = new byte[BUFFER_SIZE];
    private final byte[] readerBuf = new byte[BUFFER_SIZE];

    StreamCompressor(final Deflater deflater) {
        this.deflater = deflater;
    }

    @Override
    public void close() throws IOException {
        deflater.end();
    }

    void deflate() throws IOException {
        final int len = deflater.deflate(outputBuffer, 0, outputBuffer.length);
        if (len > 0) {
            writeCounted(outputBuffer, 0, len);
        }
    }

    /**
     * Deflates the given source using the supplied compression method
     *
     * @param source The source to compress
     * @param method The #ZipArchiveEntry compression method
     * @throws IOException When failures happen
     */
    public void deflate(final InputStream source, final int method) throws IOException {
        reset();
        int length;
        while ((length = source.read(readerBuf, 0, readerBuf.length)) >= 0) {
            write(readerBuf, 0, length, method);
        }
        if (method == ZipEntry.DEFLATED) {
            flushDeflater();
        }
    }

    private void deflateUntilInputIsNeeded() throws IOException {
        while (!deflater.needsInput()) {
            deflate();
        }
    }

    void flushDeflater() throws IOException {
        deflater.finish();
        while (!deflater.finished()) {
            deflate();
        }
    }

    /**
     * Gets the number of bytes read from the source stream
     *
     * @return The number of bytes read, never negative
     */
    public long getBytesRead() {
        return sourcePayloadLength;
    }

    /**
     * Gets the number of bytes written to the output for the last entry
     *
     * @return The number of bytes, never negative
     */
    public long getBytesWrittenForLastEntry() {
        return writtenToOutputStreamForLastEntry;
    }

    /**
     * Gets the CRC-32 of the last deflated file
     *
     * @return the CRC-32
     */
    public long getCrc32() {
        return crc.getValue();
    }

    /**
     * Gets the total number of bytes written to the output for all files
     *
     * @return The number of bytes, never negative
     */
    public long getTotalBytesWritten() {
        return totalWrittenToOutputStream;
    }

    void reset() {
        crc.reset();
        deflater.reset();
        sourcePayloadLength = 0;
        writtenToOutputStreamForLastEntry = 0;
    }

    /**
     * Writes bytes to ZIP entry.
     *
     * @param b      the byte array to write
     * @param offset the start position to write from
     * @param length the number of bytes to write
     * @param method the comrpession method to use
     * @return the number of bytes written to the stream this time
     * @throws IOException on error
     */
    long write(final byte[] b, final int offset, final int length, final int method) throws IOException {
        final long current = writtenToOutputStreamForLastEntry;
        crc.update(b, offset, length);
        if (method == ZipEntry.DEFLATED) {
            writeDeflated(b, offset, length);
        } else {
            writeCounted(b, offset, length);
        }
        sourcePayloadLength += length;
        return writtenToOutputStreamForLastEntry - current;
    }

    /**
     * Writes the specified byte array to the output stream.
     *
     * @param data   the data.
     * @exception IOException if an I/O error occurs.
     */
    public void writeCounted(final byte[] data) throws IOException {
        writeCounted(data, 0, data.length);
    }

    /**
     * Writes {@code len} bytes from the specified byte array starting at offset {@code off} to the output stream.
     *
     * @param data   the data.
     * @param offset the start offset in the data.
     * @param length the number of bytes to write.
     * @exception IOException if an I/O error occurs.
     */
    public void writeCounted(final byte[] data, final int offset, final int length) throws IOException {
        writeOut(data, offset, length);
        writtenToOutputStreamForLastEntry += length;
        totalWrittenToOutputStream += length;
    }

    private void writeDeflated(final byte[] b, final int offset, final int length) throws IOException {
        if (length > 0 && !deflater.finished()) {
            if (length <= DEFLATER_BLOCK_SIZE) {
                deflater.setInput(b, offset, length);
                deflateUntilInputIsNeeded();
            } else {
                final int fullblocks = length / DEFLATER_BLOCK_SIZE;
                for (int i = 0; i < fullblocks; i++) {
                    deflater.setInput(b, offset + i * DEFLATER_BLOCK_SIZE, DEFLATER_BLOCK_SIZE);
                    deflateUntilInputIsNeeded();
                }
                final int done = fullblocks * DEFLATER_BLOCK_SIZE;
                if (done < length) {
                    deflater.setInput(b, offset + done, length - done);
                    deflateUntilInputIsNeeded();
                }
            }
        }
    }

    /**
     * Writes {@code len} bytes from the specified byte array starting at offset {@code off} to the output stream.
     *
     * @param data   the data.
     * @param offset the start offset in the data.
     * @param length the number of bytes to write.
     * @exception IOException if an I/O error occurs.
     */
    protected abstract void writeOut(byte[] data, int offset, int length) throws IOException;
}
