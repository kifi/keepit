package com.keepit.search.engine.library

import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.engine._
import com.keepit.search.engine.query.core.QueryProjector
import com.keepit.search.{ SearchContext, SearchConfig }
import com.keepit.search.index.graph.library.LibraryIndexable
import com.keepit.search.index.graph.keep.KeepFields
import com.keepit.search.index.{ LongBufferUtil, DocUtil, Searcher, WrappedSubReader }
import com.keepit.search.util.join.{ DataBuffer, DataBufferWriter }
import org.apache.lucene.index.{ BinaryDocValues, AtomicReaderContext }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ Query, Scorer }
import scala.concurrent.Future

class LibraryFromKeepsScoreVectorSource(
    protected val searcher: Searcher,
    protected val userId: Long,
    protected val friendIdsFuture: Future[Set[Long]],
    protected val restrictedUserIdsFuture: Future[Set[Long]],
    protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    protected val orgIdsFuture: Future[Set[Long]],
    protected val context: SearchContext,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait,
    librarySearcher: Searcher,
    libraryQualityEvaluator: LibraryQualityEvaluator,
    explanation: Option[LibrarySearchExplanationBuilder]) extends ScoreVectorSourceLike with KeepRecencyEvaluator with VisibilityEvaluator {

  override protected def preprocess(query: Query): Query = {
    val searchFields = {
      if (context.disableFullTextSearch) Set.empty[String] // effectively not executing this source
      else (KeepFields.minimalSearchFields ++ KeepFields.fullTextSearchFields) // no prefix search
    }
    QueryProjector.project(query, searchFields)
  }

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = context.idFilter

    val pq = createScorerQueue(scorers, coreSize)
    if (pq.size <= 0) return // no scorer

    val keepVisibilityEvaluator = getKeepVisibilityEvaluator(reader)
    val libraryIdsDocValues: BinaryDocValues = reader.getBinaryDocValues(KeepFields.libraryIdsField)

    val recencyScorer = getRecencyScorer(readerContext)
    if (recencyScorer == null) log.warn("RecencyScorer is null")

    val idMapper = reader.getIdMapper
    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores = pq.createScoreArray // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val visibility = keepVisibilityEvaluator(docId) // todo(Léo): the keep visibility should probably not end up in the buffer
      val libIdsRef = libraryIdsDocValues.get(docId)
      if (libIdsRef.length > 0) {
        if (visibility != Visibility.RESTRICTED) {
          val keepId = idMapper.getId(docId)
          val recencyBoost = getRecencyBoost(recencyScorer, docId)
          val boost = recencyBoost

          // get all scores
          val size = pq.getTaggedScores(taggedScores, boost)

          // write to the buffer
          val libIds = DocUtil.toLongBuffer(libIdsRef)
          LongBufferUtil.foreach(libIds) { libId =>
            if (idFilter.findIndex(libId) < 0) { // use findIndex to avoid boxing
              output.alloc(writer, visibility | Visibility.HAS_SECONDARY_ID, 8 + 8 + size * 4) // libId (8 bytes), keepId (8 bytes) and taggedFloats (size * 4 bytes)
              writer.putLong(libId, keepId).putTaggedFloatBits(taggedScores, size)
              explanation.foreach(_.collectBufferScoreContribution(libId, keepId, visibility, taggedScores, size, weights.length))
            }
          }
          docId = pq.top.doc // next doc
        } else {
          docId = pq.skipCurrentDoc() // this keep is not searchable, skipping...
        }
      } else {
        docId = pq.skipCurrentDoc() // this keep nas no libraries anyway, skipping...
      }
    }
  }
}
