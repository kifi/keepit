package com.keepit.search.util.join

import org.specs2.mutable._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import scala.math.abs

class DataBufferTest extends Specification {
  private val rand = new Random

  def taggedFloatValueFromFloat(value: Float) = {
    val bits = java.lang.Float.floatToRawIntBits(value) & 0xffffff00
    java.lang.Float.intBitsToFloat(bits)
  }

  "DataBuffer" should {
    "read/write records with no data" in {
      val buf = new DataBuffer
      val writer = new DataBufferWriter

      var recType = 0
      val expected = (0 until 3000).map { id =>
        val datum = (recType, false)
        buf.alloc(writer, recType, 0)
        recType = (recType + 1) % DataBuffer.MAX_RECTYPEID
        datum
      }
      buf.numPages === 3

      var result = new ArrayBuffer[(Int, Boolean)]()
      buf.scan(new DataBufferReader) { reader =>
        result += ((reader.recordType, reader.hasMore))
      }

      (result == expected) === true
    }
  }

  "DataBuffer" should {
    "read/write records with Long, Int, Short and Float" in {
      val buf = new DataBuffer
      val writer = new DataBufferWriter

      var recType = 0
      val expected = (0 until 200).map { id =>
        val datum = (recType, true, rand.nextLong, rand.nextInt, rand.nextInt.toShort, rand.nextFloat(), false)
        buf.alloc(writer, recType, 18)
        writer.putLong(datum._3)
        writer.putInt(datum._4)
        writer.putShort(datum._5)
        writer.putFloat(datum._6)
        recType = (recType + 1) % DataBuffer.MAX_RECTYPEID
        datum
      }
      buf.numPages === 2

      var result = new ArrayBuffer[(Int, Boolean, Long, Int, Short, Float, Boolean)]()
      buf.scan(new DataBufferReader) { reader =>
        result += ((reader.recordType, reader.hasMore, reader.nextLong, reader.nextInt, reader.nextShort, reader.nextFloat, reader.hasMore))
      }

      (result == expected) === true
    }
  }

  "DataBuffer" should {
    "read/write records with Tagged Float" in {

      val buf = new DataBuffer
      val writer = new DataBufferWriter

      var recType = 0
      val expected = (0 until 100).map { id =>
        val tag = rand.nextInt().toByte
        val value = rand.nextFloat()
        val datum = (recType, tag, value, false)
        buf.alloc(writer, recType, 4)
        writer.putTaggedFloat(tag, value)
        recType = (recType + 1) % DataBuffer.MAX_RECTYPEID
        datum
      }
      buf.numPages === 1

      var result = new ArrayBuffer[(Int, Long, Float, Boolean)]()
      buf.scan(new DataBufferReader) { reader =>
        result += ((reader.recordType, reader.getTaggedFloatTag, reader.nextTaggedFloatValue, reader.hasMore))
      }

      (result == expected.map(d => (d._1, d._2, taggedFloatValueFromFloat(d._3), d._4))) === true

      (result.map(_._3) zip expected.map(_._3)).forall {
        case (f1, f2) => abs((f1 - f2) / f2) <= (1.0f / (0x7fff.toFloat))
      } === true
    }
  }

  "read records with skipping fields" in {
    val buf = new DataBuffer
    val writer = new DataBufferWriter

    var recType = 0
    val expected = (0 until 100).map { id =>
      val datum = (recType, rand.nextLong, rand.nextInt, rand.nextInt.toShort, rand.nextFloat(), rand.nextInt.toByte, rand.nextFloat(), false)
      buf.alloc(writer, recType, 22)
      writer.putLong(datum._2)
      writer.putInt(datum._3)
      writer.putShort(datum._4)
      writer.putFloat(datum._5)
      writer.putTaggedFloat(datum._6, datum._7)
      recType = (recType + 1) % DataBuffer.MAX_RECTYPEID
      datum
    }

    // skip Long
    var result = new ArrayBuffer[(Int, Long, Int, Short, Float, Byte, Float, Boolean)]()
    buf.scan(new DataBufferReader) { reader =>
      result += ((reader.recordType, { reader.skipLong; 0L }, reader.nextInt, reader.nextShort, reader.nextFloat, reader.getTaggedFloatTag, reader.nextTaggedFloatValue, reader.hasMore))
    }
    val expectedWithSkippingLong = expected.map {
      case (t, l, i, s, f, tt, tv, b) => (t, 0L, i, s, f, tt, taggedFloatValueFromFloat(tv), b)
    }
    (result == expectedWithSkippingLong) === true

    // skip Int
    result.clear()
    buf.scan(new DataBufferReader) { reader =>
      result += ((reader.recordType, reader.nextLong, { reader.skipInt; 0 }, reader.nextShort, reader.nextFloat, reader.getTaggedFloatTag, reader.nextTaggedFloatValue, reader.hasMore))
    }
    val expectedWithSkippingInt = expected.map {
      case (t, l, i, s, f, tt, tv, b) => (t, l, 0, s, f, tt, taggedFloatValueFromFloat(tv), b)
    }
    (result == expectedWithSkippingInt) === true

    // skip Short
    result.clear()
    buf.scan(new DataBufferReader) { reader =>
      result += ((reader.recordType, reader.nextLong, reader.nextInt, { reader.skipShort; 0.toShort }, reader.nextFloat, reader.getTaggedFloatTag, reader.nextTaggedFloatValue, reader.hasMore))
    }
    val expectedWithSkippingShort = expected.map {
      case (t, l, i, s, f, tt, tv, b) => (t, l, i, 0.toShort, f, tt, taggedFloatValueFromFloat(tv), b)
    }
    (result == expectedWithSkippingShort) === true

    // skip TaggedFloat
    result.clear()
    buf.scan(new DataBufferReader) { reader =>
      result += ((reader.recordType, reader.nextLong, reader.nextInt, { reader.skipShort; 0.toShort }, reader.nextFloat, 0.toByte, { reader.skipTaggedFloat; 0f }, reader.hasMore))
    }
    val expectedWithSkippingTaggedFloat = expected.map {
      case (t, l, i, s, f, tt, tv, b) => (t, l, i, 0.toShort, f, 0.toByte, 0f, b)
    }
    (result == expectedWithSkippingTaggedFloat) === true
  }
}
