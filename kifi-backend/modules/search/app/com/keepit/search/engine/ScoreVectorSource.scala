package com.keepit.search.engine

import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.Searcher
import com.keepit.search.article.ArticleVisibility
import com.keepit.search.engine.query.KWeight
import com.keepit.search.graph.keep.KeepFields
import com.keepit.search.index.WrappedSubReader
import com.keepit.search.util.LongArraySet
import com.keepit.search.util.join.{ DataBuffer, DataBufferWriter }
import org.apache.lucene.index.{ NumericDocValues, Term, AtomicReaderContext }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ Query, Weight, Scorer }
import org.apache.lucene.util.PriorityQueue
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration._

trait ScoreVectorSource {
  def createWeights(query: Query): IndexedSeq[(Weight, Float)]
  def execute(weights: IndexedSeq[(Weight, Float)], dataBuffer: DataBuffer): Unit
}

trait ScoreVectorSourceLike extends ScoreVectorSource {
  def createWeights(query: Query): IndexedSeq[(Weight, Float)] = {
    val weights = new ArrayBuffer[(Weight, Float)]
    val weight = searcher.createWeight(query)
    if (weight != null) {
      weight.asInstanceOf[KWeight].getWeights(weights)
    }
    weights
  }

  def execute(weights: IndexedSeq[(Weight, Float)], dataBuffer: DataBuffer): Unit = {
    val scorers = new Array[Scorer](weights.size)
    indexReaderContexts.foreach { subReaderContext =>
      val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
      var i = 0
      while (i < scorers.length) {
        scorers(i) = weights(i)._1.scorer(subReaderContext, true, false, subReader.getLiveDocs)
        i += 1
      }
      writeScoreVectors(subReader, scorers, dataBuffer)
    }
  }

  protected val searcher: Searcher

  protected def indexReaderContexts: Seq[AtomicReaderContext] = { searcher.indexReader.getContext.leaves }

  protected def writeScoreVectors(reader: WrappedSubReader, scorers: Array[Scorer], output: DataBuffer)

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
//  query Article index, Keep index, and Collection index and aggregate the hits by Uri Id
//

class UriFromArticlesScoreVectorSource(protected val searcher: Searcher, idFilter: LongArraySet) extends ScoreVectorSourceLike {

  protected def writeScoreVectors(reader: WrappedSubReader, scorers: Array[Scorer], output: DataBuffer): Unit = {
    val pq = createScorerQueue(scorers)
    if (pq.size <= 0) return // no scorer

    val articleVisibility = ArticleVisibility(reader)

    val idMapper = reader.getIdMapper
    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores: Array[Int] = new Array[Int](pq.size) // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val uriId = idMapper.getId(docId)

      if (idFilter.findIndex(uriId) < 0) { // use findIndex to avoid boxing
        // An article hit may or may not be visible according to the restriction
        // The visibility (0: restricted, 1: unknown, 2: network, 3: member) is encoded as a record type
        val visibility = if (articleVisibility.isVisible(docId)) Visibility.OTHERS else Visibility.RESTRICTED

        // get all scores
        val size = pq.getTaggedScores(taggedScores)

        // write to the buffer
        output.alloc(writer, visibility, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
        writer.putLong(uriId).putTaggedFloatBits(taggedScores, size)
        docId = pq.top.doc // next doc
      } else {
        docId = pq.skipCurrentDoc() // skip this doc
      }
    }
  }
}

class UriFromKeepsScoreVectorSource(protected val searcher: Searcher, libraryIdsFuture: Future[(Seq[Long], Seq[Long], Seq[Long])], idFilter: LongArraySet, monitoredAwait: MonitoredAwait) extends ScoreVectorSourceLike {

  private[this] lazy val (mySecretLibIds, myLibIds, friendsLibIds) = monitoredAwait.result(libraryIdsFuture, 5 seconds, s"getting libraryIds")

  private def getDiscoverableLibraries() = {
    val arr = new Array[Long](mySecretLibIds.size + myLibIds.size + friendsLibIds.size)
    var i = 0
    mySecretLibIds.foreach { id => arr(i) = id; i += 1 }
    myLibIds.foreach { id => arr(i) = id; i += 1 }
    friendsLibIds.foreach { id => arr(i) = id; i += 1 }
    LongArraySet.from(arr)
  }

  protected def writeScoreVectors(reader: WrappedSubReader, scorers: Array[Scorer], output: DataBuffer): Unit = {
    val pq = createScorerQueue(scorers)
    if (pq.size <= 0) return // no scorer

    val uriIdDocValues = reader.getNumericDocValues(KeepFields.uriIdField)
    val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)

    val writer: DataBufferWriter = new DataBufferWriter

    getUrisWithVisibilities(Visibility.SECRET, mySecretLibIds, idFilter, reader, uriIdDocValues, writer, output)
    getUrisWithVisibilities(Visibility.MEMBER, myLibIds, idFilter, reader, uriIdDocValues, writer, output)
    getUrisWithVisibilities(Visibility.NETWORK, friendsLibIds, idFilter, reader, uriIdDocValues, writer, output)

    val discoverableLibraries = getDiscoverableLibraries()

    val taggedScores: Array[Int] = new Array[Int](pq.size) // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val uriId = uriIdDocValues.get(docId)
      val libId = libraryIdDocValues.get(docId)

      if (idFilter.findIndex(uriId) < 0 && discoverableLibraries.findIndex(libId) >= 0) { // use findIndex to avoid boxing
        // get all scores
        val size = pq.getTaggedScores(taggedScores)

        // write to the buffer
        output.alloc(writer, Visibility.NETWORK, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
        writer.putLong(uriId).putTaggedFloatBits(taggedScores, size)
        docId = pq.top.doc // next doc
      } else {
        docId = pq.skipCurrentDoc() // skip this doc
      }
    }
  }

  private def getUrisWithVisibilities(visibility: Int, libIds: Seq[Long], idFilter: LongArraySet, reader: WrappedSubReader, uriIdDocValues: NumericDocValues, writer: DataBufferWriter, output: DataBuffer): Unit = {
    var i = 0
    libIds.foreach { libId =>
      val td = reader.termDocsEnum(new Term(KeepFields.libraryField, libId.toString))
      var docId = td.nextDoc()
      while (docId < NO_MORE_DOCS) {
        val uriId = uriIdDocValues.get(docId)

        if (idFilter.findIndex(uriId) < 0) { // use findIndex to avoid boxing
          // write to the buffer
          output.alloc(writer, visibility, 8) // id only (8 bytes)
          writer.putLong(uriId)
        }
      }
    }
  }
}

//
// Library Search (finding Libraries)
//  query Library index and Keep index and aggregate the hits by Library Id
//

class LibraryScoreVectorSource(protected val searcher: Searcher, libraryIds: LongArraySet, idFilter: LongArraySet) extends ScoreVectorSourceLike {

  protected def writeScoreVectors(reader: WrappedSubReader, scorers: Array[Scorer], output: DataBuffer): Unit = {
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
          if (libraryIds.findIndex(libId) >= 0) Visibility.MEMBER else Visibility.OTHERS // TODO: if (libraryVisibility.isVisible(docId)) Visibility.PUBLIC else Visibility.RESTRICTED

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

class LibraryFromKeepsScoreVectorSource(protected val searcher: Searcher, idFilter: LongArraySet) extends ScoreVectorSourceLike {

  protected def writeScoreVectors(reader: WrappedSubReader, scorers: Array[Scorer], output: DataBuffer): Unit = {
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
