package com.keepit.search.engine

import com.keepit.search.engine.result.ResultCollector
import com.keepit.search.util.join.{ DataBufferReader, DataBufferWriter, DataBuffer }
import org.specs2.mutable.Specification

import scala.util.Random

class ScoreContextTest extends Specification {
  private[this] val rnd = new Random()

  private[this] val collector = new ResultCollector[ScoreContext] {
    var result = Map[Long, Float]()
    var visibility = Map[Long, Int]()

    def clear(): Unit = {
      result = Map[Long, Float]()
      visibility = Map[Long, Int]()
    }

    override def collect(ctx: ScoreContext): Unit = {
      val id = ctx.id
      val score = ctx.score

      if (result.contains(id)) throw new Exception("duplicate ids")

      if (score > 0.0f) result += id -> score
      visibility += id -> ctx.visibility
    }
  }

  def computeScore(buf: DataBuffer, reader: DataBufferReader, ctx: ScoreContext): Unit = {
    buf.scan(reader) { reader =>
      val id = reader.nextLong()
      if (ctx.id != id) ctx.set(id)
      ctx.join(reader)
    }
    ctx.flush
  }

  "ScoreContext" should {
    "compute max of scores" in {
      val scores = rnd.shuffle(Seq[Float](3.0f, 2.0f, 1.0f))

      val buf = new DataBuffer
      val writer = new DataBufferWriter
      val reader = new DataBufferReader

      val numTerms = 5
      val allIdx = rnd.shuffle((0 until numTerms).toIndexedSeq)
      val idx1 = allIdx.head
      val idx2 = allIdx.tail.head

      val weights = new Array[Float](numTerms)
      allIdx.foreach { i => weights(i) = 1.0f / numTerms.toFloat }

      scores.foreach { scr =>
        buf.alloc(writer, Visibility.OTHERS, 12)
        writer.putLong(123L)
        writer.putTaggedFloat(idx1.toByte, scr)
      }

      computeScore(buf, reader, new ScoreContext(MaxExpr(idx1), numTerms, weights, collector))

      collector.result === Map(123L -> 3.0f)

      collector.clear()
      computeScore(buf, reader, new ScoreContext(MaxExpr(idx2), numTerms, weights, collector))

      collector.result === Map()
    }
  }

  "compute sum of scores" in {
    val scores = rnd.shuffle(Seq[Float](3.0f, 2.0f, 1.0f))

    val buf = new DataBuffer
    val writer = new DataBufferWriter
    val reader = new DataBufferReader

    val numTerms = 5
    val allIdx = rnd.shuffle((0 until numTerms).toIndexedSeq)
    val idx1 = allIdx.head
    val idx2 = allIdx.tail.head

    val weights = new Array[Float](numTerms)
    allIdx.foreach { i => weights(i) = 1.0f / numTerms.toFloat }

    scores.foreach { scr =>
      buf.alloc(writer, Visibility.OTHERS, 12)
      writer.putLong(123L)
      writer.putTaggedFloat(idx1.toByte, scr)
    }

    computeScore(buf, reader, new ScoreContext(SumExpr(idx1), numTerms, weights, collector))

    collector.result === Map(123L -> 6.0f)

    collector.clear()
    computeScore(buf, reader, new ScoreContext(SumExpr(idx2), numTerms, weights, collector))

    collector.result === Map()
  }

  "compute visibilities" in {
    val visibilities = rnd.shuffle(Seq[Int](Visibility.RESTRICTED, Visibility.OTHERS, Visibility.NETWORK, Visibility.MEMBER, Visibility.OWNER, Visibility.HAS_SECONDARY_ID, Visibility.HAS_TERTIARY_ID))

    val buf = new DataBuffer
    val writer = new DataBufferWriter
    val reader = new DataBufferReader

    val numTerms = 5
    val allIdx = rnd.shuffle((0 until numTerms).toIndexedSeq)
    val idx1 = allIdx.head
    val idx2 = allIdx.tail.head

    val weights = new Array[Float](numTerms)
    allIdx.foreach { i => weights(i) = 1.0f / numTerms.toFloat }

    visibilities.foreach { visibility =>
      buf.alloc(writer, visibility, 12)
      writer.putLong(123L)
      writer.putTaggedFloat(idx1.toByte, 1.0f)
    }

    val ctx = new ScoreContext(SumExpr(idx1), numTerms, weights, collector)

    var expectedVisibility = Visibility.RESTRICTED

    var i = 0
    buf.scan(reader) { reader =>
      val id = reader.nextLong()
      if (ctx.id != id) ctx.set(id)
      ctx.join(reader)
      expectedVisibility |= visibilities(i)
      i += 1

      ctx.visibility === expectedVisibility
    }
    ctx.flush

    1 === 1
  }
}
