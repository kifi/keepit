package com.keepit.search.engine

import com.keepit.common.akka.MonitoredAwait
import com.keepit.model.LibraryVisibility
import com.keepit.search.graph.library.LibraryFields
import com.keepit.search.{ SearchFilter, SearchConfig, Searcher }
import com.keepit.search.article.ArticleVisibility
import com.keepit.search.engine.query.KWeight
import com.keepit.search.graph.keep.KeepFields
import com.keepit.search.index.WrappedSubReader
import com.keepit.search.query.{ RecencyScorer, RecencyQuery }
import com.keepit.search.util.LongArraySet
import com.keepit.search.util.join.{ DataBuffer, DataBufferWriter }
import org.apache.lucene.index.{ NumericDocValues, Term, AtomicReaderContext }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ MatchAllDocsQuery, Query, Weight, Scorer }
import org.apache.lucene.util.Bits.MatchAllBits
import org.apache.lucene.util.PriorityQueue
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration._

object ScoreVectorSource {
  val PUBLISHED = LibraryFields.toNumericCode(LibraryVisibility.PUBLISHED)
  val DISCOVERABLE = LibraryFields.toNumericCode(LibraryVisibility.DISCOVERABLE)
  val SECRET = LibraryFields.toNumericCode(LibraryVisibility.SECRET)
}

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

trait KeepRecencyEvaluator { self: ScoreVectorSourceLike =>

  protected val config: SearchConfig

  private[this] lazy val recencyQuery = {
    val recencyBoostStrength = config.asFloat("recencyBoost")
    val halfDecayMillis = config.asFloat("halfDecayHours") * (60.0f * 60.0f * 1000.0f) // hours to millis
    new RecencyQuery(new MatchAllDocsQuery(), KeepFields.createdAtField, recencyBoostStrength, halfDecayMillis)
  }

  private[this] lazy val recencyWeight: Weight = searcher.createWeight(recencyQuery)

  protected def getRecencyScorer(readerContext: AtomicReaderContext): RecencyScorer = {
    // use MatchAllBits to avoid delete check. this is safe because RecencyScorer is used passively.
    recencyWeight.scorer(readerContext, true, false, new MatchAllBits(readerContext.reader.maxDoc())).asInstanceOf[RecencyScorer]
  }

  @inline
  protected def getRecencyBoost(recencyScorer: RecencyScorer, docId: Int) = {
    if (recencyScorer.docID() < docId) {
      if (recencyScorer.advance(docId) == docId) recencyScorer.score() else 1.0f
    } else {
      if (recencyScorer.docID() == docId) recencyScorer.score() else 1.0f
    }
  }
}

trait VisibilityEvaluator { self: ScoreVectorSourceLike =>
  protected val userId: Long
  protected val friendIdsFuture: Future[Set[Long]]
  protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long])]
  protected val monitoredAwait: MonitoredAwait

  private[this] val published = ScoreVectorSource.PUBLISHED

  private[this] lazy val myFriendIds = LongArraySet.fromSet(monitoredAwait.result(friendIdsFuture, 5 seconds, s"getting friend ids"))

  private[this] lazy val (myOwnLibraryIds, memberLibraryIds, trustedLibraryIds) = {
    val (myLibIds, memberLibIds, trustedLibIds) = monitoredAwait.result(libraryIdsFuture, 5 seconds, s"getting library ids")
    (LongArraySet.fromSet(myLibIds), LongArraySet.fromSet(memberLibIds), LongArraySet.fromSet(trustedLibIds))
  }

  @inline
  protected def getKeepVisibility(docId: Int, libId: Long, userIdDocValues: NumericDocValues, visibilityDocValues: NumericDocValues): Int = {
    if (memberLibraryIds.findIndex(libId) >= 0) {
      if (myOwnLibraryIds.findIndex(libId) >= 0) {
        Visibility.OWNER // the keep is in my library (I may or may not have kept it)
      } else {
        if (userIdDocValues.get(docId) == userId) {
          Visibility.OWNER // the keep in a library I am a member of, and I kept it
        } else {
          Visibility.MEMBER // the keep is in a library I am a member of
        }
      }
    } else {
      if (visibilityDocValues.get(docId) == published) {
        if (myFriendIds.findIndex(userIdDocValues.get(docId)) >= 0) {
          Visibility.NETWORK // the keep is in a published library, and my friend kept it
        } else if (trustedLibraryIds.findIndex(libId) >= 0) {
          Visibility.OTHERS // the keep is in a published library, and it is in a trusted library
        } else {
          Visibility.RESTRICTED
        }
      } else {
        Visibility.RESTRICTED
      }
    }
  }

  @inline
  protected def getLibraryVisibility(docId: Int, libId: Long, visibilityDocValues: NumericDocValues): Int = {
    if (memberLibraryIds.findIndex(libId) >= 0) {
      if (myOwnLibraryIds.findIndex(libId) >= 0) {
        Visibility.OWNER // my own library
      } else {
        Visibility.MEMBER // a library I am a member of
      }
    } else {
      if (visibilityDocValues.get(docId) == published) {
        Visibility.OTHERS // a published library
      } else {
        Visibility.RESTRICTED
      }
    }
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
// Kifi Search (finding URIs through Keeps)
//  query Article index and Keep index, and aggregate the hits by Uri Id
//

class UriFromArticlesScoreVectorSource(protected val searcher: Searcher, filter: SearchFilter) extends ScoreVectorSourceLike {

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], output: DataBuffer): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = filter.idFilter

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
        // OTHERS may be promoted to NETWORK, MEMBER, or OWNER at the join stage according to the result from KeepIndex
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
    protected val userId: Long,
    protected val friendIdsFuture: Future[Set[Long]],
    protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long])],
    filter: SearchFilter,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait) extends ScoreVectorSourceLike with KeepRecencyEvaluator with VisibilityEvaluator {

  private[this] lazy val myFriendIds = LongArraySet.fromSet(monitoredAwait.result(friendIdsFuture, 5 seconds, s"getting friend ids"))

  private[this] lazy val (myOwnLibraryIds, memberLibraryIds, trustedLibraryIds) = {
    val (myLibIds, memberLibIds, trustedLibIds) = monitoredAwait.result(libraryIdsFuture, 5 seconds, s"getting library ids")
    (LongArraySet.fromSet(myLibIds), LongArraySet.fromSet(memberLibIds), LongArraySet.fromSet(trustedLibIds))
  }

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], output: DataBuffer): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = filter.idFilter

    val pq = createScorerQueue(scorers)
    if (pq.size <= 0) return // no scorer

    val uriIdDocValues = reader.getNumericDocValues(KeepFields.uriIdField)
    val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)
    val userIdDocValues = reader.getNumericDocValues(KeepFields.userIdField)
    val visibilityDocValues = reader.getNumericDocValues(KeepFields.visibilityField)

    val recencyScorer = getRecencyScorer(readerContext)

    val writer: DataBufferWriter = new DataBufferWriter

    // load all URIs in the network with no score (this supersedes the old URIGraphSearcher things)
    // this is necessary to categorize URIs correctly for boosting even when a query matches only in scraped data but not in personal meta data.
    loadURIsInNetwork(idFilter, reader, uriIdDocValues, writer, output)

    val taggedScores: Array[Int] = new Array[Int](pq.size) // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val uriId = uriIdDocValues.get(docId)
      val libId = libraryIdDocValues.get(docId)

      if (idFilter.findIndex(uriId) < 0) {
        val visibility = getKeepVisibility(docId, libId, userIdDocValues, visibilityDocValues)

        if (visibility != Visibility.RESTRICTED) {
          val boost = getRecencyBoost(recencyScorer, docId)

          // get all scores
          val size = pq.getTaggedScores(taggedScores, boost)
          docId = pq.top.doc // next doc

          // write to the buffer
          // write the libId. libId is expected for searchable keeps.
          output.alloc(writer, visibility | Visibility.HAS_SECONDARY_ID, 8 + 8 + size * 4) // id (8 bytes), libId (8 bytes) and taggedFloats (size * 4 bytes)
          writer.putLong(uriId).putLong(libId).putTaggedFloatBits(taggedScores, size)
        } else {
          docId = pq.skipCurrentDoc() // this keep is not searchable, skipping...
        }
      } else {
        docId = pq.skipCurrentDoc() // this keep is not searchable, skipping...
      }
    }
  }

  private def loadURIsInNetwork(idFilter: LongArraySet, reader: WrappedSubReader, uriIdDocValues: NumericDocValues, writer: DataBufferWriter, output: DataBuffer): Unit = {
    def load(libId: Long, visibility: Int): Unit = {
      val td = reader.termDocsEnum(new Term(KeepFields.libraryField, libId.toString))
      var docId = td.nextDoc()
      while (docId < NO_MORE_DOCS) {
        val uriId = uriIdDocValues.get(docId)

        if (idFilter.findIndex(uriId) < 0) { // use findIndex to avoid boxing
          // write to the buffer
          output.alloc(writer, visibility, 8) // id (8 bytes)
          writer.putLong(uriId)
        }
      }
    }

    myOwnLibraryIds.foreach { libId => load(libId, Visibility.OWNER) }

    // memberLibraryIds includes myOwnLibraryIds
    memberLibraryIds.foreach { libId => if (myOwnLibraryIds.findIndex(libId) < 0) load(libId, Visibility.MEMBER) }

    myFriendIds.foreach { friendId =>
      val td = reader.termDocsEnum(new Term(KeepFields.userDiscoverableField, friendId.toString))
      var docId = td.nextDoc()
      while (docId < NO_MORE_DOCS) {
        val uriId = uriIdDocValues.get(docId)

        if (idFilter.findIndex(uriId) < 0) { // use findIndex to avoid boxing
          // write to the buffer
          output.alloc(writer, Visibility.NETWORK, 8) // id (8 bytes)
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

class LibraryScoreVectorSource(
    protected val searcher: Searcher,
    protected val userId: Long,
    protected val friendIdsFuture: Future[Set[Long]],
    protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long])],
    filter: SearchFilter,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait) extends ScoreVectorSourceLike with VisibilityEvaluator {

  private[this] lazy val myFriendIds = LongArraySet.fromSet(monitoredAwait.result(friendIdsFuture, 5 seconds, s"getting friend ids"))

  private[this] lazy val (myOwnLibraryIds, memberLibraryIds, trustedLibraryIds) = {
    val (myLibIds, memberLibIds, trustedLibIds) = monitoredAwait.result(libraryIdsFuture, 5 seconds, s"getting library ids")
    (LongArraySet.fromSet(myLibIds), LongArraySet.fromSet(memberLibIds), LongArraySet.fromSet(trustedLibIds))
  }

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], output: DataBuffer): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = filter.idFilter

    val pq = createScorerQueue(scorers)
    if (pq.size <= 0) return // no scorer

    val visibilityDocValues = reader.getNumericDocValues(LibraryFields.visibilityField)

    val idMapper = reader.getIdMapper
    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores: Array[Int] = new Array[Int](pq.size) // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val libId = idMapper.getId(docId)

      if (idFilter.findIndex(libId) < 0) { // use findIndex to avoid boxing
        val visibility = getLibraryVisibility(docId, libId, visibilityDocValues)

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

class LibraryFromKeepsScoreVectorSource(
    protected val searcher: Searcher,
    protected val userId: Long,
    protected val friendIdsFuture: Future[Set[Long]],
    protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long])],
    filter: SearchFilter,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait) extends ScoreVectorSourceLike with KeepRecencyEvaluator with VisibilityEvaluator {

  private[this] lazy val myFriendIds = LongArraySet.fromSet(monitoredAwait.result(friendIdsFuture, 5 seconds, s"getting friend ids"))

  private[this] lazy val (myOwnLibraryIds, memberLibraryIds, trustedLibraryIds) = {
    val (myLibIds, memberLibIds, trustedLibIds) = monitoredAwait.result(libraryIdsFuture, 5 seconds, s"getting library ids")
    (LongArraySet.fromSet(myLibIds), LongArraySet.fromSet(memberLibIds), LongArraySet.fromSet(trustedLibIds))
  }

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], output: DataBuffer): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = filter.idFilter

    val pq = createScorerQueue(scorers)
    if (pq.size <= 0) return // no scorer

    val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)
    val visibilityDocValues = reader.getNumericDocValues(KeepFields.visibilityField)

    val recencyScorer = getRecencyScorer(readerContext)

    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores: Array[Int] = new Array[Int](pq.size) // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val libId = libraryIdDocValues.get(docId)

      if (idFilter.findIndex(libId) < 0) { // use findIndex to avoid boxing
        val visibility = getLibraryVisibility(docId, libId, visibilityDocValues)

        if (visibility != Visibility.RESTRICTED) {
          val boost = getRecencyBoost(recencyScorer, docId)

          // get all scores
          val size = pq.getTaggedScores(taggedScores, boost)
          docId = pq.top.doc // next doc

          // write to the buffer
          output.alloc(writer, visibility, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
          writer.putLong(libId).putTaggedFloatBits(taggedScores, size)
        } else {
          docId = pq.skipCurrentDoc() // skip this doc
        }
      } else {
        docId = pq.skipCurrentDoc() // skip this doc
      }
    }
  }
}
