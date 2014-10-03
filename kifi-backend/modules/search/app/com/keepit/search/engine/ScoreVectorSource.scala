package com.keepit.search.engine

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.logging.Logging
import com.keepit.search.graph.library.LibraryFields
import com.keepit.search.{ SearchFilter, SearchConfig, Searcher }
import com.keepit.search.article.ArticleVisibility
import com.keepit.search.engine.query.KWeight
import com.keepit.search.graph.keep.KeepFields
import com.keepit.search.index.{ IdMapper, WrappedSubReader }
import com.keepit.search.util.LongArraySet
import com.keepit.search.util.join.{ BloomFilter, DataBuffer, DataBufferWriter }
import org.apache.lucene.index.{ NumericDocValues, Term, AtomicReaderContext }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ Query, Weight, Scorer }
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

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

  protected def createScorerQueue(scorers: Array[Scorer], coreSize: Int): TaggedScorerQueue = TaggedScorerQueue(scorers, coreSize)
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
        val visibility = if (articleVisibility.isVisible(docId)) Visibility.OTHERS else Visibility.RESTRICTED

        if (bloomFilter(uriId)) {
          // get all scores and write to the buffer
          val size = pq.getTaggedScores(taggedScores)
          output.alloc(writer, visibility, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
          writer.putLong(uriId).putTaggedFloatBits(taggedScores, size)

          docId = pq.top.doc // next doc
        } else {
          if (visibility != Visibility.RESTRICTED) {
            // this uriId is not in the buffer
            // it is safe to bypass the buffering and joining (assuming all score vector sources other than this are executed already)
            // write directly to the collector through directScoreContext
            directScoreContext.put(uriId, visibility)

            docId = pq.top.doc // next doc
          } else {
            docId = pq.skipCurrentDoc() // skip this doc
          }
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
    val keepVisibilityEvaluator = getKeepVisibilityEvaluator(userIdDocValues, visibilityDocValues)
    val recencyScorer = getRecencyScorer(readerContext)

    val taggedScores: Array[Int] = pq.createScoreArray // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val uriId = uriIdDocValues.get(docId)
      val libId = libraryIdDocValues.get(docId)

      if (idFilter.findIndex(uriId) < 0) {
        val visibility = keepVisibilityEvaluator(docId, libId)

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
    val libraryVisibilityEvaluator = getLibraryVisibilityEvaluator(visibilityDocValues)

    val idMapper = reader.getIdMapper
    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores: Array[Int] = pq.createScoreArray // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val libId = idMapper.getId(docId)

      if (idFilter.findIndex(libId) < 0) { // use findIndex to avoid boxing
        val visibility = libraryVisibilityEvaluator(docId, libId)

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
    val libraryVisibilityEvaluator = getLibraryVisibilityEvaluator(visibilityDocValues)

    val recencyScorer = getRecencyScorer(readerContext)

    val idMapper = reader.getIdMapper
    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores = pq.createScoreArray // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val libId = libraryIdDocValues.get(docId)

      if (idFilter.findIndex(libId) < 0) { // use findIndex to avoid boxing
        val visibility = libraryVisibilityEvaluator(docId, libId)

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
