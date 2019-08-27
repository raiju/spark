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

package org.apache.spark.shuffle

import java.io.{Closeable, IOException, OutputStream}

import org.apache.spark.serializer.{SerializationStream, SerializerInstance, SerializerManager}
import org.apache.spark.shuffle.api.ShufflePartitionWriter
import org.apache.spark.storage.BlockId
import org.apache.spark.util.Utils
import org.apache.spark.util.collection.PairsWriter

/**
 * A key-value writer inspired by {@link DiskBlockObjectWriter} that pushes the bytes to an
 * arbitrary partition writer instead of writing to local disk through the block manager.
 */
private[spark] class ShufflePartitionPairsWriter(
    partitionWriter: ShufflePartitionWriter,
    serializerManager: SerializerManager,
    serializerInstance: SerializerInstance,
    blockId: BlockId,
    writeMetrics: ShuffleWriteMetricsReporter)
  extends PairsWriter with Closeable {

  private var isOpen = false
  private var partitionStream: OutputStream = _
  private var wrappedStream: OutputStream = _
  private var objOut: SerializationStream = _
  private var numRecordsWritten = 0
  private var curNumBytesWritten = 0L

  override def write(key: Any, value: Any): Unit = {
    if (!isOpen) {
      open()
      isOpen = true
    }
    objOut.writeKey(key)
    objOut.writeValue(value)
    recordWritten()
  }

  private def open(): Unit = {
    try {
      partitionStream = partitionWriter.openStream
      wrappedStream = serializerManager.wrapStream(blockId, partitionStream)
      objOut = serializerInstance.serializeStream(wrappedStream)
    } catch {
      case e: Exception =>
        Utils.tryLogNonFatalError {
          close()
        }
        throw e
    }
  }

  override def close(): Unit = {
    Utils.tryWithSafeFinally {
      objOut = closeIfNonNull(objOut)
    } {
      Utils.tryWithSafeFinally {
        wrappedStream = closeIfNonNull(wrappedStream)
      } {
        Utils.tryWithSafeFinally {
          partitionStream = closeIfNonNull(partitionStream)
        }
      }
    }
    isOpen = false
    updateBytesWritten()
  }

  private def closeIfNonNull[T <: Closeable](closeable: T): T = {
    if (closeable != null) {
      closeable.close()
    }
    null.asInstanceOf[T]
  }

  private def tryCloseOrAddSuppressed(
      closeable: Closeable, prevException: Option[IOException]): Option[IOException] = {
    var resolvedException = prevException
    try {
      closeable.close()
    } catch {
      case e: IOException =>
        resolvedException = prevException.map(presentPrev => {
          presentPrev.addSuppressed(e)
          presentPrev
        }).orElse(Some(e))
    }
    resolvedException
  }

  /**
   * Notify the writer that a record worth of bytes has been written with OutputStream#write.
   */
  private def recordWritten(): Unit = {
    numRecordsWritten += 1
    writeMetrics.incRecordsWritten(1)

    if (numRecordsWritten % 16384 == 0) {
      updateBytesWritten()
    }
  }

  private def updateBytesWritten(): Unit = {
    val numBytesWritten = partitionWriter.getNumBytesWritten
    val bytesWrittenDiff = numBytesWritten - curNumBytesWritten
    writeMetrics.incBytesWritten(bytesWrittenDiff)
    curNumBytesWritten = numBytesWritten
  }
}
