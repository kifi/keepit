package com.keepit.search.util.join

import org.specs2.mutable._
import scala.util.Random
import scala.collection.mutable

class HashJoinTest extends Specification {

  class TstJoiner(result: mutable.HashMap[Long, (Option[Int], Option[Int], Option[Int])]) extends Joiner {
    private var v1: Option[Int] = None
    private var v2: Option[Int] = None
    private var v3: Option[Int] = None

    def join(reader: DataBufferReader): Unit = {
      reader.recordType match {
        case 1 => v1 = Some(reader.nextInt)
        case 2 => v2 = Some(reader.nextInt)
        case 3 => v3 = Some(reader.nextInt)
        case unknown => new IllegalStateException(s"unknown record type: $unknown")
      }
    }

    def flush(): Unit = {
      result += (id -> (v1, v2, v3))
    }

    def clean(): Unit = {
      v1 = None
      v2 = None
      v3 = None
    }
  }

  val rand = new Random

  "HashJoin" should {
    "successfully join data sources over id" in {
      val buf = new DataBuffer
      val writer = new DataBufferWriter

      val table1 = (0 until 60).map(i => (i * 2).toLong -> (rand.nextInt)).toMap
      val table2 = (0 until 40).map(i => (i * 3).toLong -> (rand.nextInt)).toMap
      val table3 = (0 until 24).map(i => (i * 5).toLong -> (rand.nextInt)).toMap

      table1.foreach {
        case (id, value) =>
          buf.alloc(writer, 1, 12)
          writer.putLong(id)
          writer.putInt(value)
      }
      table2.foreach {
        case (id, value) =>
          buf.alloc(writer, 2, 12)
          writer.putLong(id)
          writer.putInt(value)
      }
      table3.foreach {
        case (id, value) =>
          buf.alloc(writer, 3, 12)
          writer.putLong(id)
          writer.putInt(value)
      }

      val expected = (table1.keySet ++ table2.keySet ++ table3.keySet).map { id =>
        id -> (table1.get(id), table2.get(id), table3.get(id))
      }.toMap

      val result = mutable.HashMap.empty[Long, (Option[Int], Option[Int], Option[Int])]
      val join = new HashJoin(buf, 20, new TstJoiner(result))

      join.execute()

      result === expected
    }
  }
}
