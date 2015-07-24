package com.keepit.search.engine.library

import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.engine.{ DirectScoreContext, Visibility, VisibilityEvaluator, ScoreVectorSourceLike }
import com.keepit.search.engine.query.core.QueryProjector
import com.keepit.search.index.graph.keep.KeepFields
import com.keepit.search.index.graph.library.LibraryFields
import com.keepit.search.{ SearchContext, SearchConfig }
import com.keepit.search.index.{ Searcher, WrappedSubReader }
import com.keepit.search.util.join.{ DataBuffer, DataBufferWriter }
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ Query, Scorer }
import scala.concurrent.Future

class LibraryScoreVectorSource(
    protected val searcher: Searcher,
    protected val userId: Long,
    protected val friendIdsFuture: Future[Set[Long]],
    protected val restrictedUserIdsFuture: Future[Set[Long]],
    protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    protected val orgIdsFuture: Future[Set[Long]],
    protected val context: SearchContext,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait,
    explanation: Option[LibrarySearchExplanationBuilder]) extends ScoreVectorSourceLike with VisibilityEvaluator {

  private[this] val librarySourceBoost = config.asFloat("librarySourceBoost")

  override protected def preprocess(query: Query): Query = {
    val searchFields = LibraryFields.minimalSearchFields ++ LibraryFields.prefixSearchFields ++ (if (context.disableFullTextSearch) Set.empty else LibraryFields.fullTextSearchFields)
    QueryProjector.project(query, searchFields)
  }

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = context.idFilter

    // execute the query
    val pq = createScorerQueue(scorers, coreSize)
    if (pq.size <= 0) return // no scorer

    val libraryVisibilityEvaluator = getLibraryVisibilityEvaluator(reader)

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
          val size = pq.getTaggedScores(taggedScores, librarySourceBoost)

          // write to the buffer
          output.alloc(writer, visibility, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
          writer.putLong(libId).putTaggedFloatBits(taggedScores, size)
          explanation.foreach(_.collectBufferScoreContribution(libId, -1, visibility, taggedScores, size, weights.length))

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
