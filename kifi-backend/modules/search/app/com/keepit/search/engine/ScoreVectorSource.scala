package com.keepit.search.engine

import com.keepit.search.article.ArticleVisibility
import com.keepit.search.graph.keep.KeepFields
import com.keepit.search.index.WrappedSubReader
import com.keepit.search.util.LongArraySet
import com.keepit.search.util.join.{ DataBuffer, DataBufferWriter }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Scorer
import org.apache.lucene.util.PriorityQueue

trait ScoreVectorSource {
  def writeScoreVectorsTo(output: DataBuffer)

  protected def createScorerQueue(scorers: Array[Scorer]): TaggedScoreQueue = {
    val pq = new TaggedScoreQueue(scorers.length)
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
}

final class TaggedScorer(tag: Byte, scorer: Scorer) {
  def doc = scorer.docID()
  def next = scorer.nextDoc()
  def advance(docId: Int) = scorer.advance(docId)
  def taggedScore = DataBuffer.taggedFloatBits(tag, scorer.score)
}

final class TaggedScoreQueue(size: Int) extends PriorityQueue[TaggedScorer](size) {
  override def lessThan(a: TaggedScorer, b: TaggedScorer): Boolean = (a.doc < b.doc)

  def getTaggedScores(taggedScores: Array[Int]): Int = {
    var scorer = top()
    val docId = scorer.doc
    var size: Int = 0
    while (scorer.doc == docId) {
      taggedScores(size) = scorer.taggedScore
      size += 1
      scorer.next
      scorer = updateTop()
    }
    size
  }

  def skipCurrentDoc(): Int = {
    var scorer = top()
    val docId = scorer.doc
    while (scorer.doc <= docId) {
      scorer.next
      scorer = updateTop()
    }
    scorer.doc
  }
}

//
// Main Search (finding Keeps)
//  query Article index and Keep index and aggregate the hits by Uri Id
//

class ArticleScoreVectorSource(reader: WrappedSubReader, scorers: Array[Scorer], idFilter: LongArraySet) extends ScoreVectorSource {

  def writeScoreVectorsTo(output: DataBuffer): Unit = {
    val pq = createScorerQueue(scorers)
    if (pq.size <= 0) return // no scorer

    val articleVisibility = ArticleVisibility(reader)

    val idMapper = reader.getIdMapper
    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores: Array[Int] = new Array[Int](pq.size) // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val id = idMapper.getId(docId)

      if (idFilter.findIndex(id) < 0) { // use findIndex to avoid boxing
        // An article hit may or may not be visible according to the restriction
        // The visibility (0: restricted, 1: public, 2: member) is encoded as a record type
        val visibility = if (articleVisibility.isVisible(docId)) Visibility.PUBLIC else Visibility.RESTRICTED

        // get all scores
        val size = pq.getTaggedScores(taggedScores)

        // write to the buffer
        output.alloc(writer, visibility, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
        writer.putLong(id).putTaggedFloatBits(taggedScores, size)
        docId = pq.top.doc // next doc
      } else {
        docId = pq.skipCurrentDoc() // skip this doc
      }
    }
  }
}

class ArticleFromKeepsScoreVectorSource(reader: WrappedSubReader, scorers: Array[Scorer], libraryIds: LongArraySet, idFilter: LongArraySet) extends ScoreVectorSource {

  def writeScoreVectorsTo(output: DataBuffer): Unit = {
    val pq = createScorerQueue(scorers)
    if (pq.size <= 0) return // no scorer

    val uriIdDocValues = reader.getNumericDocValues(KeepFields.uriIdField)
    val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)

    val idMapper = reader.getIdMapper
    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores: Array[Int] = new Array[Int](pq.size) // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val id = idMapper.getId(docId)
      val libId = libraryIdDocValues.get(docId)

      if (libraryIds.findIndex(libId) >= 0 && idFilter.findIndex(id) < 0) { // use findIndex to avoid boxing
        // get all scores
        val size = pq.getTaggedScores(taggedScores)

        // write to the buffer
        output.alloc(writer, Visibility.MEMBER, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
        writer.putLong(id).putTaggedFloatBits(taggedScores, size)
        docId = pq.top.doc // next doc
      } else {
        docId = pq.skipCurrentDoc() // skip this doc
      }
    }
  }
}

//
// Library Search (finding Libraries)
//  query Library index and Keep index and aggregate the hits by Library Id
//

class LibraryScoreVectorSource(reader: WrappedSubReader, scorers: Array[Scorer], libraryIds: LongArraySet, idFilter: LongArraySet) extends ScoreVectorSource {

  def writeScoreVectorsTo(output: DataBuffer): Unit = {
    val pq = createScorerQueue(scorers)
    if (pq.size <= 0) return // no scorer

    // TODO: val libraryVisibility = LibraryVisibility(reader)

    val idMapper = reader.getIdMapper
    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores: Array[Int] = new Array[Int](pq.size) // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val libId = idMapper.getId(docId)

      if (idFilter.findIndex(libId) < 0) { // use findIndex to avoid boxing
        val visibility =
          if (libraryIds.findIndex(libId) >= 0) Visibility.MEMBER else Visibility.PUBLIC // TODO: if (libraryVisibility.isVisible(docId)) Visibility.PUBLIC else Visibility.RESTRICTED

        if (visibility != Visibility.RESTRICTED) {
          // get all scores
          val size = pq.getTaggedScores(taggedScores)

          // write to the buffer
          output.alloc(writer, visibility, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
          writer.putLong(libId).putTaggedFloatBits(taggedScores, size)
          docId = pq.top.doc // next doc
        } else {
          docId = pq.skipCurrentDoc() // skip this doc
        }
      } else {
        docId = pq.skipCurrentDoc() // skip this doc
      }
    }
  }
}

class LibraryFromKeepsScoreVectorSource(reader: WrappedSubReader, scorers: Array[Scorer], idFilter: LongArraySet) extends ScoreVectorSource {

  def writeScoreVectorsTo(output: DataBuffer): Unit = {
    val pq = createScorerQueue(scorers)
    if (pq.size <= 0) return // no scorer

    val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)

    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores: Array[Int] = new Array[Int](pq.size) // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val libId = libraryIdDocValues.get(docId)

      if (idFilter.findIndex(libId) < 0) { // use findIndex to avoid boxing
        // get all scores
        val size = pq.getTaggedScores(taggedScores)

        // write to the buffer
        // the visibility is restricted since we don't know it for sure
        output.alloc(writer, Visibility.RESTRICTED, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
        writer.putLong(libId).putTaggedFloatBits(taggedScores, size)
        docId = pq.top.doc // next doc
      } else {
        docId = pq.skipCurrentDoc() // skip this doc
      }
    }
  }
}
