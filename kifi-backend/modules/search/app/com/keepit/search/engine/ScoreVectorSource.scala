package com.keepit.search.engine

import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.{ SearchConfig, Searcher }
import com.keepit.search.article.ArticleVisibility
import com.keepit.search.engine.query.KWeight
import com.keepit.search.graph.keep.KeepFields
import com.keepit.search.index.WrappedSubReader
import com.keepit.search.query.RecencyQuery
import com.keepit.search.util.LongArraySet
import com.keepit.search.util.join.{ DataBuffer, DataBufferWriter }
import org.apache.lucene.index.{ NumericDocValues, Term, AtomicReaderContext }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ MatchAllDocsQuery, Query, Weight, Scorer }
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
    indexReaderContexts.foreach { readerContext =>
      var i = 0
      while (i < scorers.length) {
        scorers(i) = weights(i)._1.scorer(readerContext, true, false, readerContext.reader.getLiveDocs)
        i += 1
      }
      writeScoreVectors(readerContext, scorers, dataBuffer)
    }
  }

  protected val searcher: Searcher

  protected def indexReaderContexts: Seq[AtomicReaderContext] = { searcher.indexReader.getContext.leaves }

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], output: DataBuffer)

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
  def taggedScore(boost: Float) = DataBuffer.taggedFloatBits(tag, scorer.score * boost)
}

final class TaggedScoreQueue(size: Int) extends PriorityQueue[TaggedScorer](size) {
  override def lessThan(a: TaggedScorer, b: TaggedScorer): Boolean = (a.doc < b.doc)

  def getTaggedScores(taggedScores: Array[Int], boost: Float = 1.0f): Int = {
    var scorer = top()
    val docId = scorer.doc
    var size: Int = 0
    while (scorer.doc == docId) {
      taggedScores(size) = scorer.taggedScore(boost)
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

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], output: DataBuffer): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]

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

class UriFromKeepsScoreVectorSource(
    protected val searcher: Searcher,
    libraryIdsFuture: Future[(Seq[Long], Seq[Long], Seq[Long])],
    idFilter: LongArraySet,
    config: SearchConfig,
    monitoredAwait: MonitoredAwait) extends ScoreVectorSourceLike {

  private[this] val recencyQuery = {
    val recencyBoostStrength = config.asFloat("recencyBoost")
    val halfDecayMillis = config.asFloat("halfDecayHours") * (60.0f * 60.0f * 1000.0f) // hours to millis
    new RecencyQuery(new MatchAllDocsQuery(), KeepFields.createdAtField, recencyBoostStrength, halfDecayMillis)
  }

  private[this] var recencyWeight: Weight = null

  private[this] lazy val (mySecretLibIds, myLibIds, friendsLibIds) = monitoredAwait.result(libraryIdsFuture, 5 seconds, s"getting libraryIds")

  private def getDiscoverableLibraries() = {
    val arr = new Array[Long](mySecretLibIds.size + myLibIds.size + friendsLibIds.size)
    var i = 0
    mySecretLibIds.foreach { id => arr(i) = id; i += 1 }
    myLibIds.foreach { id => arr(i) = id; i += 1 }
    friendsLibIds.foreach { id => arr(i) = id; i += 1 }
    LongArraySet.from(arr)
  }

  private def getRecencyScorer(readerContext: AtomicReaderContext): Scorer = {
    if (recencyWeight == null) null else recencyWeight.scorer(readerContext, true, false, readerContext.reader.getLiveDocs)
  }

  override def execute(weights: IndexedSeq[(Weight, Float)], dataBuffer: DataBuffer): Unit = {
    recencyWeight = searcher.createWeight(recencyQuery)
    super.execute(weights, dataBuffer)
  }

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], output: DataBuffer): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]

    val pq = createScorerQueue(scorers)
    if (pq.size <= 0) return // no scorer

    val uriIdDocValues = reader.getNumericDocValues(KeepFields.uriIdField)
    val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)

    val writer: DataBufferWriter = new DataBufferWriter

    // load all discoverable URIs with no score (this supersedes the old URIGraphSearcher things)
    getUrisWithVisibilities(Visibility.SECRET, mySecretLibIds, idFilter, reader, uriIdDocValues, writer, output)
    getUrisWithVisibilities(Visibility.MEMBER, myLibIds, idFilter, reader, uriIdDocValues, writer, output)
    getUrisWithVisibilities(Visibility.NETWORK, friendsLibIds, idFilter, reader, uriIdDocValues, writer, output)

    val discoverableLibraries = getDiscoverableLibraries()

    val recencyScorer = getRecencyScorer(readerContext)

    val taggedScores: Array[Int] = new Array[Int](pq.size) // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val uriId = uriIdDocValues.get(docId)
      val libId = libraryIdDocValues.get(docId)

      if (idFilter.findIndex(uriId) < 0 && discoverableLibraries.findIndex(libId) >= 0) { // use findIndex to avoid boxing
        // recency boost
        val boost = if (recencyScorer != null) {
          if (recencyScorer.docID() < docId) {
            if (recencyScorer.advance(docId) == docId) recencyScorer.score() else 1.0f
          } else {
            if (recencyScorer.docID() == docId) recencyScorer.score() else 1.0f
          }
        } else 1.0f

        // get all scores
        val size = pq.getTaggedScores(taggedScores, boost)

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

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], output: DataBuffer): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]

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

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], output: DataBuffer): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]

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
