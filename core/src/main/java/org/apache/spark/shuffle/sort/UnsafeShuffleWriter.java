/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle.sort;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Iterator;

import scala.Option;
import scala.Product2;
import scala.collection.JavaConverters;
import scala.reflect.ClassTag;
import scala.reflect.ClassTag$;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.*;
import org.apache.spark.annotation.Private;
import org.apache.spark.api.shuffle.ShuffleMapOutputWriter;
import org.apache.spark.api.shuffle.ShufflePartitionWriter;
import org.apache.spark.api.shuffle.ShuffleWriteSupport;
import org.apache.spark.internal.config.package$;
import org.apache.spark.io.CompressionCodec;
import org.apache.spark.io.CompressionCodec$;
import org.apache.spark.io.NioBufferedFileInputStream;
import org.apache.commons.io.output.CloseShieldOutputStream;
import org.apache.spark.memory.TaskMemoryManager;
import org.apache.spark.network.util.LimitedInputStream;
import org.apache.spark.scheduler.MapStatus;
import org.apache.spark.scheduler.MapStatus$;
import org.apache.spark.shuffle.ShuffleWriteMetricsReporter;
import org.apache.spark.serializer.SerializationStream;
import org.apache.spark.serializer.SerializerInstance;
import org.apache.spark.shuffle.IndexShuffleBlockResolver;
import org.apache.spark.shuffle.ShuffleWriter;
import org.apache.spark.storage.BlockManager;
import org.apache.spark.storage.TimeTrackingOutputStream;
import org.apache.spark.unsafe.Platform;
import org.apache.spark.util.Utils;

@Private
public class UnsafeShuffleWriter<K, V> extends ShuffleWriter<K, V> {

  private static final Logger logger = LoggerFactory.getLogger(UnsafeShuffleWriter.class);

  private static final ClassTag<Object> OBJECT_CLASS_TAG = ClassTag$.MODULE$.Object();

  @VisibleForTesting
  static final int DEFAULT_INITIAL_SORT_BUFFER_SIZE = 4096;
  static final int DEFAULT_INITIAL_SER_BUFFER_SIZE = 1024 * 1024;

  private final BlockManager blockManager;
  private final IndexShuffleBlockResolver shuffleBlockResolver;
  private final TaskMemoryManager memoryManager;
  private final SerializerInstance serializer;
  private final Partitioner partitioner;
  private final ShuffleWriteMetricsReporter writeMetrics;
  private final ShuffleWriteSupport shuffleWriteSupport;
  private final int shuffleId;
  private final int mapId;
  private final TaskContext taskContext;
  private final SparkConf sparkConf;
  private final boolean transferToEnabled;
  private final int initialSortBufferSize;
  private final int inputBufferSizeInBytes;
  private final int outputBufferSizeInBytes;

  @Nullable private MapStatus mapStatus;
  @Nullable private ShuffleExternalSorter sorter;
  private long peakMemoryUsedBytes = 0;

  /** Subclass of ByteArrayOutputStream that exposes `buf` directly. */
  private static final class MyByteArrayOutputStream extends ByteArrayOutputStream {
    MyByteArrayOutputStream(int size) { super(size); }
    public byte[] getBuf() { return buf; }
  }

  private MyByteArrayOutputStream serBuffer;
  private SerializationStream serOutputStream;

  /**
   * Are we in the process of stopping? Because map tasks can call stop() with success = true
   * and then call stop() with success = false if they get an exception, we want to make sure
   * we don't try deleting files, etc twice.
   */
  private boolean stopping = false;

  private class CloseAndFlushShieldOutputStream extends CloseShieldOutputStream {

    CloseAndFlushShieldOutputStream(OutputStream outputStream) {
      super(outputStream);
    }

    @Override
    public void flush() {
      // do nothing
    }
  }

  public UnsafeShuffleWriter(
      BlockManager blockManager,
      IndexShuffleBlockResolver shuffleBlockResolver,
      TaskMemoryManager memoryManager,
      SerializedShuffleHandle<K, V> handle,
      int mapId,
      TaskContext taskContext,
      SparkConf sparkConf,
      ShuffleWriteMetricsReporter writeMetrics,
      ShuffleWriteSupport shuffleWriteSupport) throws IOException {
    final int numPartitions = handle.dependency().partitioner().numPartitions();
    if (numPartitions > SortShuffleManager.MAX_SHUFFLE_OUTPUT_PARTITIONS_FOR_SERIALIZED_MODE()) {
      throw new IllegalArgumentException(
        "UnsafeShuffleWriter can only be used for shuffles with at most " +
        SortShuffleManager.MAX_SHUFFLE_OUTPUT_PARTITIONS_FOR_SERIALIZED_MODE() +
        " reduce partitions");
    }
    this.blockManager = blockManager;
    this.shuffleBlockResolver = shuffleBlockResolver;
    this.memoryManager = memoryManager;
    this.mapId = mapId;
    final ShuffleDependency<K, V, V> dep = handle.dependency();
    this.shuffleId = dep.shuffleId();
    this.serializer = dep.serializer().newInstance();
    this.partitioner = dep.partitioner();
    this.writeMetrics = writeMetrics;
    this.shuffleWriteSupport = shuffleWriteSupport;
    this.taskContext = taskContext;
    this.sparkConf = sparkConf;
    this.transferToEnabled = sparkConf.getBoolean("spark.file.transferTo", true);
    this.initialSortBufferSize =
      (int) sparkConf.get(package$.MODULE$.SHUFFLE_SORT_INIT_BUFFER_SIZE());
    this.inputBufferSizeInBytes =
      (int) (long) sparkConf.get(package$.MODULE$.SHUFFLE_FILE_BUFFER_SIZE()) * 1024;
    this.outputBufferSizeInBytes =
      (int) (long) sparkConf.get(package$.MODULE$.SHUFFLE_UNSAFE_FILE_OUTPUT_BUFFER_SIZE()) * 1024;
    open();
  }

  private void updatePeakMemoryUsed() {
    // sorter can be null if this writer is closed
    if (sorter != null) {
      long mem = sorter.getPeakMemoryUsedBytes();
      if (mem > peakMemoryUsedBytes) {
        peakMemoryUsedBytes = mem;
      }
    }
  }

  /**
   * Return the peak memory used so far, in bytes.
   */
  public long getPeakMemoryUsedBytes() {
    updatePeakMemoryUsed();
    return peakMemoryUsedBytes;
  }

  /**
   * This convenience method should only be called in test code.
   */
  @VisibleForTesting
  public void write(Iterator<Product2<K, V>> records) throws IOException {
    write(JavaConverters.asScalaIteratorConverter(records).asScala());
  }

  @Override
  public void write(scala.collection.Iterator<Product2<K, V>> records) throws IOException {
    // Keep track of success so we know if we encountered an exception
    // We do this rather than a standard try/catch/re-throw to handle
    // generic throwables.
    boolean success = false;
    try {
      while (records.hasNext()) {
        insertRecordIntoSorter(records.next());
      }
      closeAndWriteOutput();
      success = true;
    } finally {
      if (sorter != null) {
        try {
          sorter.cleanupResources();
        } catch (Exception e) {
          // Only throw this error if we won't be masking another
          // error.
          if (success) {
            throw e;
          } else {
            logger.error("In addition to a failure during writing, we failed during " +
                         "cleanup.", e);
          }
        }
      }
    }
  }

  private void open() {
    assert (sorter == null);
    sorter = new ShuffleExternalSorter(
      memoryManager,
      blockManager,
      taskContext,
      initialSortBufferSize,
      partitioner.numPartitions(),
      sparkConf,
      writeMetrics);
    serBuffer = new MyByteArrayOutputStream(DEFAULT_INITIAL_SER_BUFFER_SIZE);
    serOutputStream = serializer.serializeStream(serBuffer);
  }

  @VisibleForTesting
  void closeAndWriteOutput() throws IOException {
    assert(sorter != null);
    updatePeakMemoryUsed();
    serBuffer = null;
    serOutputStream = null;
    final SpillInfo[] spills = sorter.closeAndGetSpills();
    sorter = null;
    final ShuffleMapOutputWriter mapWriter = shuffleWriteSupport
      .createMapOutputWriter(shuffleId, mapId, partitioner.numPartitions());
    final long[] partitionLengths;
    try {
      try {
        partitionLengths = mergeSpills(spills, mapWriter);
      } finally {
        for (SpillInfo spill : spills) {
          if (spill.file.exists() && !spill.file.delete()) {
            logger.error("Error while deleting spill file {}", spill.file.getPath());
          }
        }
      }
      mapWriter.commitAllPartitions();
    } catch (Exception e) {
      try {
        mapWriter.abort(e);
      } catch (Exception innerE) {
        logger.error("Failed to abort the Map Output Writer", innerE);
      }
      throw e;
    }
    mapStatus = MapStatus$.MODULE$.apply(blockManager.shuffleServerId(), partitionLengths);
  }

  @VisibleForTesting
  void insertRecordIntoSorter(Product2<K, V> record) throws IOException {
    assert(sorter != null);
    final K key = record._1();
    final int partitionId = partitioner.getPartition(key);
    serBuffer.reset();
    serOutputStream.writeKey(key, OBJECT_CLASS_TAG);
    serOutputStream.writeValue(record._2(), OBJECT_CLASS_TAG);
    serOutputStream.flush();

    final int serializedRecordSize = serBuffer.size();
    assert (serializedRecordSize > 0);

    sorter.insertRecord(
      serBuffer.getBuf(), Platform.BYTE_ARRAY_OFFSET, serializedRecordSize, partitionId);
  }

  @VisibleForTesting
  void forceSorterToSpill() throws IOException {
    assert (sorter != null);
    sorter.spill();
  }

  /**
   * Merge zero or more spill files together, choosing the fastest merging strategy based on the
   * number of spills and the IO compression codec.
   *
   * @return the partition lengths in the merged file.
   */
  private long[] mergeSpills(SpillInfo[] spills,
      ShuffleMapOutputWriter mapWriter) throws IOException {
    final boolean compressionEnabled = (boolean) sparkConf.get(package$.MODULE$.SHUFFLE_COMPRESS());
    final CompressionCodec compressionCodec = CompressionCodec$.MODULE$.createCodec(sparkConf);
    final boolean fastMergeEnabled =
      (boolean) sparkConf.get(package$.MODULE$.SHUFFLE_UNDAFE_FAST_MERGE_ENABLE());
    final boolean fastMergeIsSupported = !compressionEnabled ||
      CompressionCodec$.MODULE$.supportsConcatenationOfSerializedStreams(compressionCodec);
    final boolean encryptionEnabled = blockManager.serializerManager().encryptionEnabled();
    final int numPartitions = partitioner.numPartitions();
    long[] partitionLengths = new long[numPartitions];
    try {
      if (spills.length == 0) {
        return partitionLengths;
      } else if (spills.length == 1) {
        // Here, we don't need to perform any metrics updates because the bytes written to this
        // output file would have already been counted as shuffle bytes written.
        try (FileInputStream in = new FileInputStream(spills[0].file)) {
          for (int pId = 0; pId < spills[0].partitionLengths.length; pId++) {
            boolean copyThrewExecption = true;
            ShufflePartitionWriter writer = null;
            try {
              writer = mapWriter.getNextPartitionWriter();
              if (transferToEnabled) {
                WritableByteChannel channel = writer.toChannel();
                try (FileChannel input = in.getChannel()) {
                  Utils.copyFileStreamNIO(input, channel, 0, input.size());
                  copyThrewExecption = false;
                } finally {
                  Closeables.close(in, copyThrewExecption);
                }
              } else {
                OutputStream stream = writer.toStream();
                Utils.copyStream(in, stream, false, false);
                copyThrewExecption = false;
              }
            } finally {
              Closeables.close(writer, copyThrewExecption);
            }
            partitionLengths[pId] = writer.getNumBytesWritten();
          }
          return partitionLengths;
        }
      } else {
        // There are multiple spills to merge, so none of these spill files' lengths were counted
        // towards our shuffle write count or shuffle write time. If we use the slow merge path,
        // then the final output file's size won't necessarily be equal to the sum of the spill
        // files' sizes. To guard against this case, we look at the output file's actual size when
        // computing shuffle bytes written.
        //
        // We allow the individual merge methods to report their own IO times since different merge
        // strategies use different IO techniques.  We count IO during merge towards the shuffle
        // shuffle write time, which appears to be consistent with the "not bypassing merge-sort"
        // branch in ExternalSorter.
        if (fastMergeEnabled && fastMergeIsSupported) {
          // Compression is disabled or we are using an IO compression codec that supports
          // decompression of concatenated compressed streams, so we can perform a fast spill merge
          // that doesn't need to interpret the spilled bytes.
          if (transferToEnabled && !encryptionEnabled) {
            logger.debug("Using transferTo-based fast merge");
            partitionLengths = mergeSpillsWithTransferTo(spills, mapWriter);
          } else {
            logger.debug("Using fileStream-based fast merge");
            partitionLengths = mergeSpillsWithFileStream(spills, mapWriter, null);
          }
        } else {
          logger.debug("Using slow merge");
          partitionLengths = mergeSpillsWithFileStream(spills, mapWriter, compressionCodec);
        }
        // When closing an UnsafeShuffleExternalSorter that has already spilled once but also has
        // in-memory records, we write out the in-memory records to a file but do not count that
        // final write as bytes spilled (instead, it's accounted as shuffle write). The merge needs
        // to be counted as shuffle write, but this will lead to double-counting of the final
        // SpillInfo's bytes.
        writeMetrics.decBytesWritten(spills[spills.length - 1].file.length());
        return partitionLengths;
      }
    } catch (IOException e) {

      throw e;
    }
  }

  /**
   * Merges spill files using Java FileStreams. This code path is typically slower than
   * the NIO-based merge, {@link UnsafeShuffleWriter#mergeSpillsWithTransferTo(SpillInfo[],
   * ShuffleMapOutputWriter)}, and it's mostly used in cases where the IO compression codec
   * does not support concatenation of compressed data, when encryption is enabled, or when
   * users have explicitly disabled use of {@code transferTo} in order to work around kernel bugs.
   * This code path might also be faster in cases where individual partition size in a spill
   * is small and UnsafeShuffleWriter#mergeSpillsWithTransferTo method performs many small
   * disk ios which is inefficient. In those case, Using large buffers for input and output
   * files helps reducing the number of disk ios, making the file merging faster.
   *
   * @param spills the spills to merge.
   * @param mapWriter the map output writer to use for output.
   * @param compressionCodec the IO compression codec, or null if shuffle compression is disabled.
   * @return the partition lengths in the merged file.
   */
  private long[] mergeSpillsWithFileStream(
      SpillInfo[] spills,
      ShuffleMapOutputWriter mapWriter,
      @Nullable CompressionCodec compressionCodec) throws IOException {
    assert (spills.length >= 2);
    final int numPartitions = partitioner.numPartitions();
    final long[] partitionLengths = new long[numPartitions];
    final InputStream[] spillInputStreams = new InputStream[spills.length];

    boolean threwException = true;
    try {
      for (int i = 0; i < spills.length; i++) {
        spillInputStreams[i] = new NioBufferedFileInputStream(
          spills[i].file,
          inputBufferSizeInBytes);
      }
      for (int partition = 0; partition < numPartitions; partition++) {
        boolean copyThrewExecption = true;
        ShufflePartitionWriter writer = null;
        OutputStream partitionOutput = null;
        try {
          writer = mapWriter.getNextPartitionWriter();
          partitionOutput = writer.toStream();
          partitionOutput = new TimeTrackingOutputStream(writeMetrics, partitionOutput);
          partitionOutput = blockManager.serializerManager().wrapForEncryption(partitionOutput);
          if (compressionCodec != null) {
            partitionOutput = compressionCodec.compressedOutputStream(partitionOutput);
          }
          // Shield the underlying output stream from close() and flush() calls, so we can close
          // the higher level streams to make sure all data is really flushed and internal state is
          // cleaned.
          for (int i = 0; i < spills.length; i++) {
            final long partitionLengthInSpill = spills[i].partitionLengths[partition];

            if (partitionLengthInSpill > 0) {
              InputStream partitionInputStream = new LimitedInputStream(spillInputStreams[i],
                partitionLengthInSpill, false);
              try {
                partitionInputStream = blockManager.serializerManager().wrapForEncryption(
                  partitionInputStream);
                if (compressionCodec != null) {
                  partitionInputStream = compressionCodec.compressedInputStream(
                    partitionInputStream);
                }
                ByteStreams.copy(partitionInputStream, partitionOutput);
              } finally {
                partitionInputStream.close();
              }
            }
            copyThrewExecption = false;
          }
        } finally {
          Closeables.close(writer, copyThrewExecption);
        }
        long numBytesWritten = writer.getNumBytesWritten();
        partitionLengths[partition] = numBytesWritten;
        writeMetrics.incBytesWritten(numBytesWritten);
      }
      threwException = false;
    } finally {
      // To avoid masking exceptions that caused us to prematurely enter the finally block, only
      // throw exceptions during cleanup if threwException == false.
      for (InputStream stream : spillInputStreams) {
        Closeables.close(stream, threwException);
      }
    }
    return partitionLengths;
  }

  /**
   * Merges spill files by using NIO's transferTo to concatenate spill partitions' bytes.
   * This is only safe when the IO compression codec and serializer support concatenation of
   * serialized streams.
   *
   * @param spills the spills to merge.
   * @param mapWriter the map output writer to use for output.
   * @return the partition lengths in the merged file.
   */
  private long[] mergeSpillsWithTransferTo(
      SpillInfo[] spills,
      ShuffleMapOutputWriter mapWriter) throws IOException {
    assert (spills.length >= 2);
    final int numPartitions = partitioner.numPartitions();
    final long[] partitionLengths = new long[numPartitions];
    final FileChannel[] spillInputChannels = new FileChannel[spills.length];
    final long[] spillInputChannelPositions = new long[spills.length];

    boolean threwException = true;
    try {
      for (int i = 0; i < spills.length; i++) {
        spillInputChannels[i] = new FileInputStream(spills[i].file).getChannel();
      }
      for (int partition = 0; partition < numPartitions; partition++) {
        boolean copyThrewExecption = true;
        ShufflePartitionWriter writer = null;
        long partitionLengthInSpill = 0L;
        try {
          writer = mapWriter.getNextPartitionWriter();
          WritableByteChannel channel = writer.toChannel();
          for (int i = 0; i < spills.length; i++) {
            partitionLengthInSpill += spills[i].partitionLengths[partition];
            final FileChannel spillInputChannel = spillInputChannels[i];
            final long writeStartTime = System.nanoTime();
            Utils.copyFileStreamNIO(
                    spillInputChannel,
                    channel,
                    spillInputChannelPositions[i],
                    partitionLengthInSpill);
            copyThrewExecption = false;
            spillInputChannelPositions[i] += partitionLengthInSpill;
            writeMetrics.incWriteTime(System.nanoTime() - writeStartTime);
          }
        } finally {
          Closeables.close(writer, copyThrewExecption);
        }
        long numBytes = writer.getNumBytesWritten();
        assert(partitionLengthInSpill == numBytes);
        partitionLengths[partition] = writer.getNumBytesWritten();
        writeMetrics.incBytesWritten(numBytes);
      }
      threwException = false;
    } finally {
      // To avoid masking exceptions that caused us to prematurely enter the finally block, only
      // throw exceptions during cleanup if threwException == false.
      for (int i = 0; i < spills.length; i++) {
        assert(spillInputChannelPositions[i] == spills[i].file.length());
        Closeables.close(spillInputChannels[i], threwException);
      }
    }
    return partitionLengths;
  }

  @Override
  public Option<MapStatus> stop(boolean success) {
    try {
      taskContext.taskMetrics().incPeakExecutionMemory(getPeakMemoryUsedBytes());

      if (stopping) {
        return Option.apply(null);
      } else {
        stopping = true;
        if (success) {
          if (mapStatus == null) {
            throw new IllegalStateException("Cannot call stop(true) without having called write()");
          }
          return Option.apply(mapStatus);
        } else {
          return Option.apply(null);
        }
      }
    } finally {
      if (sorter != null) {
        // If sorter is non-null, then this implies that we called stop() in response to an error,
        // so we need to clean up memory and spill files created by the sorter
        sorter.cleanupResources();
      }
    }
  }
}
