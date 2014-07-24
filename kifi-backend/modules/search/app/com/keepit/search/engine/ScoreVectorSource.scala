package com.keepit.search.engine

import com.keepit.search.index.IdMapper
import com.keepit.search.util.join.{ DataBuffer, DataBufferWriter }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Scorer
import org.apache.lucene.util.PriorityQueue

trait ScoreVectorSource {
  val sourceId: Int
  def score(output: DataBuffer)
}

class ScoreVectorSourceImpl(override val sourceId: Int, scorers: Array[Scorer], idMapper: IdMapper) extends ScoreVectorSource {

  private[this] val pq = {
    val pq = new PriorityQueue[TaggedScorer](scorers.length) {
      override def lessThan(a: TaggedScorer, b: TaggedScorer): Boolean = (a.doc < b.doc)
    }
    var i = 0
    while (i < scorers.length) {
      val sc = scorers(i)
      if (sc != null && sc.nextDoc() < NO_MORE_DOCS) {
        pq.insertWithOverflow(new TaggedScorer(i.toByte, sc))
      }
      i += 1
    }
    pq
  }

  final class TaggedScorer(tag: Byte, scorer: Scorer) {
    def doc = scorer.docID()
    def next = scorer.nextDoc()
    def taggedScore = DataBuffer.taggedFloatBits(tag, scorer.score)
  }

  def score(output: DataBuffer): Unit = {
    val src = sourceId
    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores: Array[Int] = new Array[Int](pq.size) // tagged floats
    var size: Int = 0

    var top = pq.top
    var docId = top.doc
    while (docId < NO_MORE_DOCS) {
      taggedScores(size) = top.taggedScore
      size += 1
      top.next
      top = pq.updateTop()
      if (top.doc != docId) {
        output.alloc(writer, src, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
        writer.putLong(idMapper.getId(docId))
        var i = 0
        while (i < size) { // using while for performance
          writer.putTaggedFloatBits(taggedScores(i))
          i += 1
        }
        size = 0
        docId = top.doc
      }
    }
  }
}
