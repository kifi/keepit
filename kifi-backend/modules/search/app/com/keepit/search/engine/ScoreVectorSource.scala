package com.keepit.search.engine

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.logging.Logging
import com.keepit.search.graph.library.LibraryFields
import com.keepit.search.{ SearchFilter, SearchConfig, Searcher }
import com.keepit.search.article.ArticleVisibility
import com.keepit.search.engine.query.KWeight
import com.keepit.search.graph.keep.KeepFields
import com.keepit.search.index.{ IdMapper, WrappedSubReader }
import com.keepit.search.query.{ RecencyScorer, RecencyQuery }
import com.keepit.search.util.LongArraySet
import com.keepit.search.util.join.{ BloomFilter, DataBuffer, DataBufferWriter }
import org.apache.lucene.index.{ NumericDocValues, Term, AtomicReaderContext }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ MatchAllDocsQuery, Query, Weight, Scorer }
import org.apache.lucene.util.Bits.MatchAllBits
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration._

trait ScoreVectorSource {
  def weights: IndexedSeq[(Weight, Float)]
  def prepare(query: Query): Unit
  def execute(coreSize: Int, dataBuffer: DataBuffer, directScoreContext: DirectScoreContext): Unit
}

trait ScoreVectorSourceLike extends ScoreVectorSource with Logging with DebugOption {
  val weights: ArrayBuffer[(Weight, Float)] = new ArrayBuffer[(Weight, Float)]

  def prepare(query: Query): Unit = {
    weights.clear()
    val weight = searcher.createWeight(query)
    if (weight != null) {
      weight.asInstanceOf[KWeight].getWeights(weights)
    }
  }

  def execute(coreSize: Int, dataBuffer: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    val scorers = new Array[Scorer](weights.size)
    indexReaderContexts.foreach { readerContext =>
      var i = 0
      while (i < scorers.length) {
        scorers(i) = weights(i)._1.scorer(readerContext, true, false, readerContext.reader.getLiveDocs)
        i += 1
      }
      writeScoreVectors(readerContext, scorers, coreSize, dataBuffer, directScoreContext)
    }
  }

  protected val searcher: Searcher

  protected def indexReaderContexts: Seq[AtomicReaderContext] = { searcher.indexReader.getContext.leaves }

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext)

  protected def createScorerQueue(scorers: Array[Scorer], coreSize: Int): TaggedScorerQueue = {
    val pq = new TaggedScorerQueue(coreSize)
    var i = 0
    while (i < scorers.length) {
      val sc = scorers(i)
      if (sc != null && sc.nextDoc() < NO_MORE_DOCS) {
        val taggedScorer = new TaggedScorer(i, sc)
        if (i < coreSize) {
          pq.insertWithOverflow(taggedScorer)
        } else {
          pq.addDependentScorer(taggedScorer)
        }
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
    val scorer = recencyWeight.scorer(readerContext, true, false, new MatchAllBits(readerContext.reader.maxDoc())).asInstanceOf[RecencyScorer]
    if (scorer == null) log.warn("RecencyScorer is null")
    scorer
  }

  @inline
  protected def getRecencyBoost(recencyScorer: RecencyScorer, docId: Int) = {
    if (recencyScorer != null) {
      if (recencyScorer.docID() < docId) {
        if (recencyScorer.advance(docId) == docId) recencyScorer.score() else 1.0f
      } else {
        if (recencyScorer.docID() == docId) recencyScorer.score() else 1.0f
      }
    } else 1.0f
  }
}

trait VisibilityEvaluator { self: ScoreVectorSourceLike =>

  protected val userId: Long
  protected val friendIdsFuture: Future[Set[Long]]
  protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])]
  protected val monitoredAwait: MonitoredAwait

  lazy val myFriendIds = LongArraySet.fromSet(monitoredAwait.result(friendIdsFuture, 5 seconds, s"getting friend ids"))

  lazy val (myOwnLibraryIds, memberLibraryIds, trustedLibraryIds, authorizedLibraryIds) = {
    val (myLibIds, memberLibIds, trustedLibIds, authorizedLibIds) = monitoredAwait.result(libraryIdsFuture, 5 seconds, s"getting library ids")

    require(myLibIds.forall { libId => memberLibIds.contains(libId) }) // sanity check

    (LongArraySet.fromSet(myLibIds), LongArraySet.fromSet(memberLibIds), LongArraySet.fromSet(trustedLibIds), LongArraySet.fromSet(authorizedLibIds))
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
    } else if (authorizedLibraryIds.findIndex(libId) >= 0) {
      Visibility.MEMBER // the keep is in an authorized library
    } else {
      if (visibilityDocValues.get(docId) == LibraryFields.Visibility.PUBLISHED) {
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
    } else if (authorizedLibraryIds.findIndex(libId) >= 0) {
      Visibility.MEMBER // the keep is in an authorized library
    } else {
      if (visibilityDocValues.get(docId) == LibraryFields.Visibility.PUBLISHED) {
        Visibility.OTHERS // a published library
      } else {
        Visibility.RESTRICTED
      }
    }
  }

  protected def listLibraries(): Unit = {
    debugLog(s"""myLibs: ${myOwnLibraryIds.toSeq.sorted.mkString(",")}""")
    debugLog(s"""memberLibs: ${memberLibraryIds.toSeq.sorted.mkString(",")}""")
    debugLog(s"""trustedLibs: ${trustedLibraryIds.toSeq.sorted.mkString(",")}""")
    debugLog(s"""authorizedLibs: ${authorizedLibraryIds.toSeq.sorted.mkString(",")}""")
  }
}

//
// Kifi Search (finding URIs through Keeps)
//  query Article index and Keep index, and aggregate the hits by Uri Id
//

class UriFromArticlesScoreVectorSource(protected val searcher: Searcher, filter: SearchFilter) extends ScoreVectorSourceLike {

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = filter.idFilter

    val pq = createScorerQueue(scorers, coreSize)
    if (pq.size <= 0) return // no scorer

    directScoreContext.setScorerQueue(pq)

    val bloomFilter = if ((debugFlags & DebugOption.NoDirectPath.flag) != 0) {
      BloomFilter.full // this disables the direct path.
    } else {
      BloomFilter(output) // a bloom filter which test if a uri id is in the buffer
    }

    val articleVisibility = ArticleVisibility(reader)

    val idMapper = reader.getIdMapper
    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores = pq.createScoreArray // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val uriId = idMapper.getId(docId)

      if (idFilter.findIndex(uriId) < 0) { // use findIndex to avoid boxing
        // An article hit may or may not be visible according to the restriction
        if (articleVisibility.isVisible(docId)) {

          if (bloomFilter(uriId)) {
            // get all scores and write to the buffer
            val size = pq.getTaggedScores(taggedScores)
            output.alloc(writer, Visibility.OTHERS, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
            writer.putLong(uriId).putTaggedFloatBits(taggedScores, size)
          } else {
            // this uriId is not in the buffer
            // it is safe to bypass the buffering and joining (assuming all score vector sources other than this are executed already)
            // write directly to the collector through directScoreContext
            directScoreContext.set(uriId)
            directScoreContext.setVisibility(Visibility.OTHERS)
            directScoreContext.flush()
          }
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

class UriFromKeepsScoreVectorSource(
    protected val searcher: Searcher,
    protected val userId: Long,
    protected val friendIdsFuture: Future[Set[Long]],
    protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    filter: SearchFilter,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait) extends ScoreVectorSourceLike with KeepRecencyEvaluator with VisibilityEvaluator {

  private[this] var myOwnLibraryKeepCount = 0
  private[this] var memberLibraryKeepCount = 0
  private[this] var trustedLibraryKeepCount = 0
  private[this] var authorizedLibraryKeepCount = 0
  private[this] var discoverableKeepCount = 0

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = filter.idFilter

    val idMapper = reader.getIdMapper
    val uriIdDocValues = reader.getNumericDocValues(KeepFields.uriIdField)

    val writer: DataBufferWriter = new DataBufferWriter

    // load all URIs in the network with no score (this supersedes the old URIGraphSearcher things)
    // this is necessary to categorize URIs correctly for boosting even when a query matches only in scraped data but not in personal meta data.
    loadURIsInNetwork(idFilter, reader, idMapper, uriIdDocValues, writer, output)

    // execute the query
    val pq = createScorerQueue(scorers, coreSize)
    if (pq.size <= 0) return // no scorer

    val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)
    val userIdDocValues = reader.getNumericDocValues(KeepFields.userIdField)
    val visibilityDocValues = reader.getNumericDocValues(KeepFields.visibilityField)
    val recencyScorer = getRecencyScorer(readerContext)

    val taggedScores: Array[Int] = pq.createScoreArray // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val uriId = uriIdDocValues.get(docId)
      val libId = libraryIdDocValues.get(docId)

      if (idFilter.findIndex(uriId) < 0) {
        val visibility = getKeepVisibility(docId, libId, userIdDocValues, visibilityDocValues)

        if (visibility != Visibility.RESTRICTED) {
          val boost = {
            if ((visibility & Visibility.OWNER) != 0) getRecencyBoost(recencyScorer, docId) + 0.2f // recency boost [1.0, recencyBoost]
            else if ((visibility & Visibility.MEMBER) != 0) 1.1f
            else 1.0f
          }

          // get all scores
          val size = pq.getTaggedScores(taggedScores, boost)
          val keepId = idMapper.getId(docId)

          // write to the buffer
          output.alloc(writer, visibility | Visibility.HAS_SECONDARY_ID, 8 + 8 + size * 4) // id (8 bytes), keepId (8 bytes) and taggedFloats (size * 4 bytes)
          writer.putLong(uriId).putLong(keepId).putTaggedFloatBits(taggedScores, size)

          docId = pq.top.doc // next doc
        } else {
          docId = pq.skipCurrentDoc() // this keep is not searchable, skipping...
        }
      } else {
        docId = pq.skipCurrentDoc() // this keep is not searchable, skipping...
      }
    }
  }

  private def loadURIsInNetwork(idFilter: LongArraySet, reader: WrappedSubReader, idMapper: IdMapper, uriIdDocValues: NumericDocValues, writer: DataBufferWriter, output: DataBuffer): Unit = {
    def load(libId: Long, visibility: Int): Int = {
      var count = 0
      val td = reader.termDocsEnum(new Term(KeepFields.libraryField, libId.toString))
      if (td != null) {
        var docId = td.nextDoc()
        while (docId < NO_MORE_DOCS) {
          val uriId = uriIdDocValues.get(docId)
          val keepId = idMapper.getId(docId)

          if (idFilter.findIndex(uriId) < 0) { // use findIndex to avoid boxing
            // write to the buffer
            output.alloc(writer, visibility | Visibility.HAS_SECONDARY_ID, 8 + 8) // id (8 bytes), keepId (8 bytes)
            writer.putLong(uriId).putLong(keepId)
            count += 1
          }
          docId = td.nextDoc()
        }
      }
      count
    }

    myOwnLibraryKeepCount += myOwnLibraryIds.foldLeft(0) { (count, libId) => count + load(libId, Visibility.OWNER) }

    // memberLibraryIds includes myOwnLibraryIds
    memberLibraryKeepCount += memberLibraryIds.foldLeft(0) { (count, libId) => count + (if (myOwnLibraryIds.findIndex(libId) < 0) load(libId, Visibility.MEMBER) else 0) }

    // load URIs from an authorized library as MEMBER
    authorizedLibraryKeepCount += authorizedLibraryIds.foldLeft(0) { (count, libId) => count + (if (memberLibraryIds.findIndex(libId) < 0) load(libId, Visibility.MEMBER) else 0) }

    discoverableKeepCount += myFriendIds.foldLeft(0) { (count, friendId) =>
      val td = reader.termDocsEnum(new Term(KeepFields.userDiscoverableField, friendId.toString))
      var cnt = 0
      if (td != null) {
        var docId = td.nextDoc()
        while (docId < NO_MORE_DOCS) {
          val uriId = uriIdDocValues.get(docId)

          if (idFilter.findIndex(uriId) < 0) { // use findIndex to avoid boxing
            // write to the buffer
            output.alloc(writer, Visibility.NETWORK, 8) // id (8 bytes)
            writer.putLong(uriId)
            cnt += 1
          }
          docId = td.nextDoc()
        }
      }
      count + cnt
    }

    if ((debugFlags & DebugOption.Library.flag) != 0) {
      listLibraries()
      listLibraryKeepCounts()
    }
  }

  private def listLibraryKeepCounts(): Unit = {
    debugLog(s"""myOwnLibKeepCount: ${myOwnLibraryKeepCount}""")
    debugLog(s"""memberLibKeepCount: ${memberLibraryKeepCount}""")
    debugLog(s"""trustedLibKeepCount: ${trustedLibraryKeepCount}""")
    debugLog(s"""authorizedLibKeepCount: ${authorizedLibraryKeepCount}""")
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
    protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    filter: SearchFilter,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait) extends ScoreVectorSourceLike with VisibilityEvaluator {

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = filter.idFilter

    // execute the query
    val pq = createScorerQueue(scorers, coreSize)
    if (pq.size <= 0) return // no scorer

    val visibilityDocValues = reader.getNumericDocValues(LibraryFields.visibilityField)

    val idMapper = reader.getIdMapper
    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores: Array[Int] = pq.createScoreArray // tagged floats

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
    protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    filter: SearchFilter,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait) extends ScoreVectorSourceLike with KeepRecencyEvaluator with VisibilityEvaluator {

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = filter.idFilter

    val pq = createScorerQueue(scorers, coreSize)
    if (pq.size <= 0) return // no scorer

    val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)
    val visibilityDocValues = reader.getNumericDocValues(KeepFields.visibilityField)

    val recencyScorer = getRecencyScorer(readerContext)

    val idMapper = reader.getIdMapper
    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores = pq.createScoreArray // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val libId = libraryIdDocValues.get(docId)

      if (idFilter.findIndex(libId) < 0) { // use findIndex to avoid boxing
        val visibility = getLibraryVisibility(docId, libId, visibilityDocValues)

        if (visibility != Visibility.RESTRICTED) {
          val boost = getRecencyBoost(recencyScorer, docId)

          // get all scores
          val size = pq.getTaggedScores(taggedScores, boost)
          val keepId = idMapper.getId(docId)

          // write to the buffer
          output.alloc(writer, visibility | Visibility.HAS_SECONDARY_ID, 8 + 8 + size * 4) // libId (8 bytes), keepId (8 bytes) and taggedFloats (size * 4 bytes)
          writer.putLong(libId).putLong(keepId).putTaggedFloatBits(taggedScores, size)

          docId = pq.top.doc // next doc
        } else {
          docId = pq.skipCurrentDoc() // this keep is not searchable, skipping...
        }
      } else {
        docId = pq.skipCurrentDoc() // this keep is not searchable, skipping...
      }
    }
  }
}
