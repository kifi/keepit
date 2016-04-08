package com.keepit.search.engine.uri

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.logging.Logging
import com.keepit.search.engine._
import com.keepit.search.engine.query.core.QueryProjector
import com.keepit.search.index.graph.library.LibraryFields
import com.keepit.search.engine.query.IdSetFilter
import com.keepit.search.{ SearchConfig, SearchContext }
import com.keepit.search.index.graph.keep.KeepFields
import com.keepit.search.index.{ Searcher, WrappedSubReader }
import com.keepit.search.util.LongArraySet
import com.keepit.search.util.join.{ DataBufferReader, DataBuffer, DataBufferWriter }
import org.apache.lucene.index.{ AtomicReaderContext, Term }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ Scorer, Query }
import scala.collection.JavaConversions._
import scala.concurrent.Future

class UriFromLibraryScoreVectorSource(
    librarySearcher: Searcher,
    keepSearcher: Searcher,
    protected val userId: Long,
    protected val friendIdsFuture: Future[Set[Long]],
    protected val restrictedUserIdsFuture: Future[Set[Long]],
    protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    protected val orgIdsFuture: Future[Set[Long]],
    protected val context: SearchContext,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait,
    explanation: Option[UriSearchExplanationBuilder]) extends ScoreVectorSource with Logging with DebugOption with VisibilityEvaluator {

  private[this] val libraryNameBoost = config.asFloat("libraryNameBoost")

  private[this] var libraryKeepCount = 0

  private[this] val libraryNameSource: LibraryNameSource = new LibraryNameSource(librarySearcher, libraryNameBoost, context.disableFullTextSearch)

  override def prepare(query: Query, matchWeightNormalizer: MatchWeightNormalizer): Unit = {
    libraryNameSource.prepare(query, matchWeightNormalizer)
  }

  override def execute(coreSize: Int, dataBuffer: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    execute(coreSize, dataBuffer)
  }

  private def execute(coreSize: Int, output: DataBuffer): Unit = {
    var libsSeen = Set.empty[Long]
    val writer: DataBufferWriter = new DataBufferWriter

    if (libraryNameBoost > 0.0f) {
      val taggedScores = libraryNameSource.createScoreArray()

      if (taggedScores.length > 0) {
        val libBuf = new DataBuffer
        libraryNameSource.execute(coreSize, libBuf, null)

        libBuf.scan(new DataBufferReader) { reader =>
          val visibility = reader.recordType
          val libId = reader.nextLong
          libsSeen += libId

          var size = 0
          while (reader.hasMore) {
            taggedScores(size) = reader.nextTaggedFloatBits()
            size += 1
          }
          libraryKeepCount += loadWithScore(libId, visibility, taggedScores, size, output, writer)
        }
      }
    }

    if ((debugFlags & DebugOption.Library.flag) != 0) {
      listLibraries()
      listLibraryKeepCounts()
    }
  }

  private def indexReaderContexts: Seq[AtomicReaderContext] = { keepSearcher.indexReader.getContext.leaves }

  private def loadWithScore(libId: Long, visibility: Int, taggedScores: Array[Int], size: Int, output: DataBuffer, writer: DataBufferWriter): Int = {
    val initialOutputSize = output.size
    val v = visibility | Visibility.HAS_SECONDARY_ID | Visibility.LIB_NAME_MATCH
    indexReaderContexts.foreach { readerContext =>
      val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
      val idFilter = context.idFilter

      val keepVisibilityEvaluator = getKeepVisibilityEvaluator(reader)
      val idMapper = reader.getIdMapper
      val uriIdDocValues = reader.getNumericDocValues(KeepFields.uriIdField)

      val td = reader.termDocsEnum(new Term(KeepFields.libraryField, libId.toString))
      if (td != null) {
        var docId = td.nextDoc()
        while (docId < NO_MORE_DOCS) {
          val uriId = uriIdDocValues.get(docId)

          if (idFilter.findIndex(uriId) < 0 && keepVisibilityEvaluator.isRelevant(docId)) { // use findIndex to avoid boxing
            val keepId = idMapper.getId(docId)

            // write to the buffer
            output.alloc(writer, v, 8 + 8 + 4 * size) // id (8 bytes), keepId (8 bytes), taggedScores (4 * size bytes)
            writer.putLong(uriId, keepId).putTaggedFloatBits(taggedScores, size)
            explanation.foreach(_.collectBufferScoreContribution(uriId, keepId, visibility, taggedScores, size, libraryNameSource.numberOfScorers))
          }
          docId = td.nextDoc()
        }
      }
    }
    output.size - initialOutputSize
  }

  private def listLibraryKeepCounts(): Unit = {
    debugLog(s"""memberLibKeepCount: ${libraryKeepCount}""")
  }

  private class LibraryNameSource(protected val searcher: Searcher, libraryNameBoost: Float, disableFullTextSearch: Boolean) extends ScoreVectorSourceLike {

    private[this] lazy val libIdFilter = new IdSetFilter(memberLibraryIds)

    override protected def preprocess(query: Query): Query = {
      val searchFields = {
        if (disableFullTextSearch) Set.empty[String] // effectively not executing this source
        else LibraryFields.minimalSearchFields // no prefix search, trim down to name fields
      }
      QueryProjector.project(query, searchFields)
    }

    protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
      val reader = readerContext.reader.asInstanceOf[WrappedSubReader]

      // execute the query
      val pq = createScorerQueue(scorers, coreSize)
      if (pq.size <= 0) return // no scorer

      val iterator = libIdFilter.getDocIdSet(readerContext, reader.getLiveDocs).iterator()

      val idMapper = reader.getIdMapper
      val writer: DataBufferWriter = new DataBufferWriter

      val taggedScores: Array[Int] = pq.createScoreArray // tagged floats

      var docId = pq.top.doc
      while (docId < NO_MORE_DOCS) {
        if (iterator.docID < docId) iterator.advance(docId)

        if (iterator.docID == docId) {
          val libId = idMapper.getId(docId)

          val visibility = Visibility.OTHERS

          // get all scores
          val size = pq.getTaggedScores(taggedScores, libraryNameBoost)

          // write to the buffer
          output.alloc(writer, visibility, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
          writer.putLong(libId).putTaggedFloatBits(taggedScores, size)

          docId = pq.top.doc // next doc
        } else {
          docId = iterator.docID
          if (docId < NO_MORE_DOCS) docId = pq.advance(docId)
        }
      }
    }

    def createScoreArray(): Array[Int] = new Array[Int](weights.size)
    def numberOfScorers = weights.size
  }
}
