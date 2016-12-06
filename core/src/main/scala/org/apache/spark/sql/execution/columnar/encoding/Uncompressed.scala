/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql.execution.columnar.encoding

import java.math.{BigDecimal, BigInteger}

import org.apache.spark.sql.catalyst.expressions.{UnsafeArrayData, UnsafeMapData, UnsafeRow}
import org.apache.spark.sql.collection.Utils
import org.apache.spark.sql.execution.columnar.encoding.ColumnEncoding.littleEndian
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.Platform
import org.apache.spark.unsafe.types.{CalendarInterval, UTF8String}

trait Uncompressed extends ColumnEncoding {

  final def typeId: Int = 0

  final def supports(dataType: DataType): Boolean = true
}

final class UncompressedDecoder
    extends UncompressedDecoderBase with NotNullDecoder

final class UncompressedDecoderNullable
    extends UncompressedDecoderBase with NullableDecoder

final class UncompressedEncoder
    extends NotNullEncoder with UncompressedEncoderBase

final class UncompressedEncoderNullable
    extends NullableEncoder with UncompressedEncoderBase

abstract class UncompressedDecoderBase
    extends ColumnDecoder with Uncompressed {

  protected final var baseCursor = 0L

  override protected def initializeCursor(columnBytes: AnyRef, cursor: Long,
      field: StructField): Long = {
    // adjust cursor for the first next call to avoid extra checks in next
    Utils.getSQLDataType(field.dataType) match {
      case BooleanType | ByteType => cursor - 1
      case ShortType => cursor - 2
      case IntegerType | FloatType | DateType => cursor - 4
      case LongType | DoubleType | TimestampType => cursor - 8
      case CalendarIntervalType => cursor - 12
      case d: DecimalType if d.precision <= Decimal.MAX_LONG_DIGITS =>
        cursor - 8
      case StringType | BinaryType | _: DecimalType |
           _: ArrayType | _: MapType | _: StructType =>
        // these will check for zero value of cursor and adjust in first next
        baseCursor = cursor
        0L
      case NullType => 0L // no role of cursor for NullType
      case t => throw new UnsupportedOperationException(s"Unsupported type $t")
    }
  }

  override def nextBoolean(columnBytes: AnyRef, cursor: Long): Long =
    cursor + 1

  override def readBoolean(columnBytes: AnyRef, cursor: Long): Boolean =
    Platform.getByte(columnBytes, cursor) == 1

  override def nextByte(columnBytes: AnyRef, cursor: Long): Long =
    cursor + 1

  override def readByte(columnBytes: AnyRef, cursor: Long): Byte =
    Platform.getByte(columnBytes, cursor)

  override def nextShort(columnBytes: AnyRef, cursor: Long): Long =
    cursor + 2

  override def readShort(columnBytes: AnyRef, cursor: Long): Short =
    if (littleEndian) Platform.getShort(columnBytes, cursor)
    else java.lang.Short.reverseBytes(Platform.getShort(columnBytes, cursor))

  override def nextInt(columnBytes: AnyRef, cursor: Long): Long =
    cursor + 4

  override def readInt(columnBytes: AnyRef, cursor: Long): Int =
    if (littleEndian) Platform.getInt(columnBytes, cursor)
    else java.lang.Integer.reverseBytes(Platform.getInt(columnBytes, cursor))

  override def nextLong(columnBytes: AnyRef, cursor: Long): Long =
    cursor + 8

  override def readLong(columnBytes: AnyRef, cursor: Long): Long = {
    val result = if (littleEndian) Platform.getLong(columnBytes, cursor)
    else java.lang.Long.reverseBytes(Platform.getLong(columnBytes, cursor))
    result
  }

  override def nextFloat(columnBytes: AnyRef, cursor: Long): Long =
    cursor + 4

  override def readFloat(columnBytes: AnyRef, cursor: Long): Float =
    if (littleEndian) Platform.getFloat(columnBytes, cursor)
    else java.lang.Float.intBitsToFloat(java.lang.Integer.reverseBytes(
      Platform.getInt(columnBytes, cursor)))

  override def nextDouble(columnBytes: AnyRef, cursor: Long): Long =
    cursor + 8

  override def readDouble(columnBytes: AnyRef, cursor: Long): Double =
    if (littleEndian) Platform.getDouble(columnBytes, cursor)
    else java.lang.Double.longBitsToDouble(java.lang.Long.reverseBytes(
      Platform.getLong(columnBytes, cursor)))

  override def nextLongDecimal(columnBytes: AnyRef, cursor: Long): Long =
    cursor + 8

  override def readLongDecimal(columnBytes: AnyRef, precision: Int,
      scale: Int, cursor: Long): Decimal =
    Decimal.createUnsafe(ColumnEncoding.readLong(columnBytes, cursor),
      precision, scale)

  override def nextDecimal(columnBytes: AnyRef, cursor: Long): Long = {
    // cursor == 0 indicates first call so don't increment cursor
    if (cursor != 0) {
      val size = ColumnEncoding.readInt(columnBytes, cursor)
      cursor + 4 + size
    } else {
      baseCursor
    }
  }

  override def readDecimal(columnBytes: AnyRef, precision: Int,
      scale: Int, cursor: Long): Decimal = {
    Decimal.apply(new BigDecimal(new BigInteger(readBinary(columnBytes,
      cursor)), scale), precision, scale)
  }

  override def nextUTF8String(columnBytes: AnyRef, cursor: Long): Long = {
    // cursor == 0 indicates first call so don't increment cursor
    if (cursor != 0) {
      val size = ColumnEncoding.readInt(columnBytes, cursor)
      cursor + 4 + size
    } else {
      baseCursor
    }
  }

  override def readUTF8String(columnBytes: AnyRef, cursor: Long): UTF8String =
    ColumnEncoding.readUTF8String(columnBytes, cursor)

  override def nextInterval(columnBytes: AnyRef, cursor: Long): Long =
    cursor + 12

  override def readInterval(columnBytes: AnyRef,
      cursor: Long): CalendarInterval = {
    val months = ColumnEncoding.readInt(columnBytes, cursor)
    val micros = ColumnEncoding.readLong(columnBytes, cursor + 4)
    new CalendarInterval(months, micros)
  }

  override def nextBinary(columnBytes: AnyRef, cursor: Long): Long = {
    // cursor == 0 indicates first call so don't increment cursor
    if (cursor != 0) {
      val size = ColumnEncoding.readInt(columnBytes, cursor)
      cursor + 4 + size
    } else {
      baseCursor
    }
  }

  override def readBinary(columnBytes: AnyRef, cursor: Long): Array[Byte] = {
    val size = ColumnEncoding.readInt(columnBytes, cursor)
    val b = new Array[Byte](size)
    Platform.copyMemory(columnBytes, cursor + 4, b,
      Platform.BYTE_ARRAY_OFFSET, size)
    b
  }

  override def readArray(columnBytes: AnyRef, cursor: Long): UnsafeArrayData = {
    val result = new UnsafeArrayData
    val size = ColumnEncoding.readInt(columnBytes, cursor)
    result.pointTo(columnBytes, cursor + 4, size)
    result
  }

  override def readMap(columnBytes: AnyRef, cursor: Long): UnsafeMapData = {
    val result = new UnsafeMapData
    val size = ColumnEncoding.readInt(columnBytes, cursor)
    result.pointTo(columnBytes, cursor + 4, size)
    result
  }

  override def readStruct(columnBytes: AnyRef, numFields: Int,
      cursor: Long): UnsafeRow = {
    val result = new UnsafeRow(numFields)
    val size = ColumnEncoding.readInt(columnBytes, cursor)
    result.pointTo(columnBytes, cursor + 4, size)
    result
  }
}

trait UncompressedEncoderBase extends ColumnEncoder with Uncompressed {

  override def writeBoolean(cursor: Long, value: Boolean): Long = {
    var position = cursor
    val b: Byte = if (value) 1 else 0
    if (position + 1 > columnData.endPosition) {
      position = expand(position, 1)
    }
    Platform.putByte(columnBytes, position, b)
    updateLongStats(b)
    position + 1
  }

  override def writeByte(cursor: Long, value: Byte): Long = {
    var position = cursor
    if (position + 1 > columnData.endPosition) {
      position = expand(position, 1)
    }
    Platform.putByte(columnBytes, position, value)
    updateLongStats(value)
    position + 1
  }

  override def writeShort(cursor: Long, value: Short): Long = {
    var position = cursor
    if (position + 2 > columnData.endPosition) {
      position = expand(position, 2)
    }
    ColumnEncoding.writeShort(columnBytes, position, value)
    updateLongStats(value)
    position + 2
  }

  override def writeInt(cursor: Long, value: Int): Long = {
    var position = cursor
    if (position + 4 > columnData.endPosition) {
      position = expand(position, 4)
    }
    ColumnEncoding.writeInt(columnBytes, position, value)
    updateLongStats(value)
    position + 4
  }

  override def writeLong(cursor: Long, value: Long): Long = {
    var position = cursor
    if (position + 8 > columnData.endPosition) {
      position = expand(position, 8)
    }
    ColumnEncoding.writeLong(columnBytes, position, value)
    updateLongStats(value)
    position + 8
  }

  override def writeFloat(cursor: Long, value: Float): Long = {
    var position = cursor
    if (position + 4 > columnData.endPosition) {
      position = expand(position, 4)
    }
    if (littleEndian) Platform.putFloat(columnBytes, position, value)
    else Platform.putInt(columnBytes, position,
      java.lang.Integer.reverseBytes(java.lang.Float.floatToIntBits(value)))
    updateDoubleStats(value.toDouble)
    position + 4
  }

  override def writeDouble(cursor: Long, value: Double): Long = {
    var position = cursor
    if (position + 8 > columnData.endPosition) {
      position = expand(position, 8)
    }
    if (littleEndian) Platform.putDouble(columnBytes, position, value)
    else Platform.putLong(columnBytes, position,
      java.lang.Long.reverseBytes(java.lang.Double.doubleToLongBits(value)))
    updateDoubleStats(value)
    position + 8
  }

  override def writeLongDecimal(cursor: Long, value: Decimal,
      precision: Int, scale: Int): Long = {
    if (value.precision != precision || value.scale != scale) {
      value.changePrecision(precision, value.scale)
    }
    writeLong(cursor, value.toUnscaledLong)
  }

  override def writeDecimal(cursor: Long, value: Decimal,
      precision: Int, scale: Int): Long = {
    val b = value.toJavaBigDecimal.unscaledValue.toByteArray
    updateDecimalStats(value)
    writeBinary(cursor, b)
  }

  override def writeInterval(cursor: Long, value: CalendarInterval): Long = {
    val position = writeInt(cursor, value.months)
    writeLong(position, value.microseconds)
  }

  override def writeUTF8String(cursor: Long, value: UTF8String): Long = {
    var position = cursor
    val size = value.numBytes
    if (position + size + 4 > columnData.endPosition) {
      position = expand(position, size + 4)
    }
    updateStringStatsClone(value)
    ColumnEncoding.writeUTF8String(columnBytes, position, value, size)
  }

  override def writeBinary(cursor: Long, value: Array[Byte]): Long = {
    var position = cursor
    val size = value.length
    if (position + size + 4 > columnData.endPosition) {
      position = expand(position, size + 4)
    }
    val columnBytes = this.columnBytes
    ColumnEncoding.writeInt(columnBytes, position, size)
    position += 4
    Platform.copyMemory(value, Platform.BYTE_ARRAY_OFFSET, columnBytes,
      position, size)
    position + size
  }

  override def writeArray(cursor: Long, value: UnsafeArrayData): Long = {
    var position = cursor
    val size = value.getSizeInBytes
    if (position + size + 4 > columnData.endPosition) {
      position = expand(position, size + 4)
    }
    val columnBytes = this.columnBytes
    ColumnEncoding.writeInt(columnBytes, position, size)
    position += 4
    Platform.copyMemory(value.getBaseObject, value.getBaseOffset, columnBytes,
      position, size)
    position + size
  }

  override def writeMap(cursor: Long, value: UnsafeMapData): Long = {
    var position = cursor
    val size = value.getSizeInBytes
    if (position + size + 4 > columnData.endPosition) {
      position = expand(position, size + 4)
    }
    val columnBytes = this.columnBytes
    ColumnEncoding.writeInt(columnBytes, position, size)
    position += 4
    Platform.copyMemory(value.getBaseObject, value.getBaseOffset, columnBytes,
      position, size)
    position + size
  }

  override def writeStruct(cursor: Long, value: UnsafeRow): Long = {
    var position = cursor
    val size = value.getSizeInBytes
    if (position + size + 4 > columnData.endPosition) {
      position = expand(position, size + 4)
    }
    val columnBytes = this.columnBytes
    ColumnEncoding.writeInt(columnBytes, position, size)
    position += 4
    Platform.copyMemory(value.getBaseObject, value.getBaseOffset, columnBytes,
      position, size)
    position + size
  }
}
