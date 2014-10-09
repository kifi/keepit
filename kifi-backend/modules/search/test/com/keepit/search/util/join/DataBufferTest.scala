package com.keepit.search.util.join

import org.specs2.mutable._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import scala.math.abs

class DataBufferTest extends Specification {
  private val rand = new Random

  "DataBuffer" should {
    "read/write records with no data" in {
      val buf = new DataBuffer
      val writer = new DataBufferWriter

      var recType = 0
      val expected = (0 until 5000).map { id =>
        val datum = (recType, false)
        buf.alloc(writer, recType, 0)
        recType = (recType + 1) % DataBuffer.MAX_RECTYPEID
        datum
      }
      buf.numPages must beGreaterThan(1)

      var result = new ArrayBuffer[(Int, Boolean)]()
      buf.scan(new DataBufferReader) { reader =>
        result += ((reader.recordType, reader.hasMore))
      }

      (result == expected) === true
    }

    "read/write records with Long, Int, Short and Float" in {
      val buf = new DataBuffer
      val writer = new DataBufferWriter

      var recType = 0
      val expected = (0 until 500).map { id =>
        val datum = (recType, true, rand.nextLong, rand.nextInt, rand.nextInt.toShort, rand.nextFloat(), false)
        buf.alloc(writer, recType, 18)
        writer.putLong(datum._3)
        writer.putInt(datum._4)
        writer.putShort(datum._5)
        writer.putFloat(datum._6)
        recType = (recType + 1) % DataBuffer.MAX_RECTYPEID
        datum
      }
      buf.numPages must beGreaterThan(2)

      var result = new ArrayBuffer[(Int, Boolean, Long, Int, Short, Float, Boolean)]()
      buf.scan(new DataBufferReader) { reader =>
        result += ((reader.recordType, reader.hasMore, reader.nextLong, reader.nextInt, reader.nextShort, reader.nextFloat, reader.hasMore))
      }

      (result == expected) === true
    }

    "read/write records with a pair of Longs" in {
      val buf = new DataBuffer
      val writer = new DataBufferWriter

      var recType = 0
      val expected = (0 until 100).map { id =>
        val datum = (recType, true, rand.nextLong, rand.nextLong, false)
        buf.alloc(writer, recType, 16)
        writer.putLong(datum._3, datum._4)
        recType = (recType + 1) % DataBuffer.MAX_RECTYPEID
        datum
      }
      buf.numPages === 1

      var result = new ArrayBuffer[(Int, Boolean, Long, Long, Boolean)]()
      buf.scan(new DataBufferReader) { reader =>
        result += ((reader.recordType, reader.hasMore, reader.nextLong, reader.nextLong, reader.hasMore))
      }

      (result == expected) === true
    }

    "read/write records with Tagged Float" in {

      val buf = new DataBuffer
      val writer = new DataBufferWriter

      var recType = 0
      val expected = (0 until 100).map { id =>
        val tag = rand.nextInt().toByte
        val value = rand.nextFloat()
        val datum = (recType, DataBuffer.taggedFloatBits(tag, value), false, value)
        buf.alloc(writer, recType, 4)
        writer.putTaggedFloat(tag, value)
        recType = (recType + 1) % DataBuffer.MAX_RECTYPEID
        datum
      }
      buf.numPages === 1

      // nextTaggedFloatBits
      val result1 = new ArrayBuffer[(Int, Int, Boolean)]()
      buf.scan(new DataBufferReader) { reader =>
        result1 += ((reader.recordType, reader.nextTaggedFloatBits(), reader.hasMore))
      }

      (result1 == expected.map(d => (d._1, d._2, d._3))) === true

      (result1.map(_._2).map(DataBuffer.getTaggedFloatValue(_)) zip expected.map(_._4)).forall {
        case (f1, f2) => abs((f1 - f2) / f2) <= (1.0f / (0x7fff.toFloat))
      } === true
    }

    "read/write records with Tagged Float Array" in {

      val buf = new DataBuffer
      val writer = new DataBufferWriter

      var recType = 0
      val expected = (0 until 10).map { id =>
        val tag1 = rand.nextInt().toByte
        val tag2 = rand.nextInt().toByte
        val tag3 = rand.nextInt().toByte
        val value1 = rand.nextFloat()
        val value2 = rand.nextFloat()
        val value3 = rand.nextFloat()
        val taggedValues = Seq(DataBuffer.taggedFloatBits(tag1, value1), DataBuffer.taggedFloatBits(tag2, value2), DataBuffer.taggedFloatBits(tag3, value3), 0)
        val datum = (recType, taggedValues, false)
        buf.alloc(writer, recType, 4 * 3)
        writer.putTaggedFloatBits(taggedValues.toArray, 3)
        recType = (recType + 1) % DataBuffer.MAX_RECTYPEID
        datum
      }
      buf.numPages === 1

      // nextTaggedFloatBits
      val result1 = new ArrayBuffer[(Int, Seq[Int], Boolean)]()
      buf.scan(new DataBufferReader) { reader =>
        result1 += ((reader.recordType, Seq(reader.nextTaggedFloatBits(), reader.nextTaggedFloatBits(), reader.nextTaggedFloatBits(), 0), reader.hasMore))
      }

      result1 === expected
    }

    "read records with skipping fields" in {
      val buf = new DataBuffer
      val writer = new DataBufferWriter

      var recType = 0
      val expected = (0 until 100).map { id =>
        val tag = rand.nextInt.toByte
        val value = rand.nextFloat()
        val datum = (recType, rand.nextLong, rand.nextInt, rand.nextInt.toShort, rand.nextFloat(), DataBuffer.taggedFloatBits(tag, value), false)
        buf.alloc(writer, recType, 22)
        writer.putLong(datum._2)
        writer.putInt(datum._3)
        writer.putShort(datum._4)
        writer.putFloat(datum._5)
        writer.putTaggedFloat(tag, value)
        recType = (recType + 1) % DataBuffer.MAX_RECTYPEID
        datum
      }

      // skip Long
      var result = new ArrayBuffer[(Int, Long, Int, Short, Float, Int, Boolean)]()
      buf.scan(new DataBufferReader) { reader =>
        result += ((reader.recordType, { reader.skipLong; 0L }, reader.nextInt, reader.nextShort, reader.nextFloat, reader.nextTaggedFloatBits, reader.hasMore))
      }
      val expectedWithSkippingLong = expected.map {
        case (t, l, i, s, f, tf, b) => (t, 0L, i, s, f, tf, b)
      }
      (result == expectedWithSkippingLong) === true

      // skip Int
      result.clear()
      buf.scan(new DataBufferReader) { reader =>
        result += ((reader.recordType, reader.nextLong, { reader.skipInt; 0 }, reader.nextShort, reader.nextFloat, reader.nextTaggedFloatBits, reader.hasMore))
      }
      val expectedWithSkippingInt = expected.map {
        case (t, l, i, s, f, tf, b) => (t, l, 0, s, f, tf, b)
      }
      (result == expectedWithSkippingInt) === true

      // skip Short
      result.clear()
      buf.scan(new DataBufferReader) { reader =>
        result += ((reader.recordType, reader.nextLong, reader.nextInt, { reader.skipShort; 0.toShort }, reader.nextFloat, reader.nextTaggedFloatBits, reader.hasMore))
      }
      val expectedWithSkippingShort = expected.map {
        case (t, l, i, s, f, tf, b) => (t, l, i, 0.toShort, f, tf, b)
      }
      (result == expectedWithSkippingShort) === true

      // skip TaggedFloat
      result.clear()
      buf.scan(new DataBufferReader) { reader =>
        result += ((reader.recordType, reader.nextLong, reader.nextInt, { reader.skipShort; 0.toShort }, reader.nextFloat, { reader.skipTaggedFloat; 0 }, reader.hasMore))
      }
      val expectedWithSkippingTaggedFloat = expected.map {
        case (t, l, i, s, f, tf, b) => (t, l, i, 0.toShort, f, 0, b)
      }
      (result == expectedWithSkippingTaggedFloat) === true
    }
  }
}
