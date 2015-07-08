package com.keepit.search.engine.library

import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.engine._
import com.keepit.search.engine.query.core.QueryProjector
import com.keepit.search.{ SearchFilter, SearchConfig }
import com.keepit.search.index.graph.keep.KeepFields
import com.keepit.search.index.{ Searcher, WrappedSubReader }
import com.keepit.search.util.join.{ DataBuffer, DataBufferWriter }
import org.apache.lucene.index.{ Term, AtomicReaderContext }
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
    filter: SearchFilter,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait,
    libraryQualityEvaluator: LibraryQualityEvaluator,
    explanation: Option[LibrarySearchExplanationBuilder]) extends ScoreVectorSourceLike with KeepRecencyEvaluator with VisibilityEvaluator {

  override protected def preprocess(query: Query): Query = QueryProjector.project(query, KeepFields.textSearchFields)

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = filter.idFilter

    val pq = createScorerQueue(scorers, coreSize)
    if (pq.size <= 0) return // no scorer

    val keepVisibilityEvaluator = getKeepVisibilityEvaluator(reader)
    val libraryIdDocValues = keepVisibilityEvaluator.libraryIdDocValues

    val recencyScorer = getRecencyScorer(readerContext)
    if (recencyScorer == null) log.warn("RecencyScorer is null")

    val idMapper = reader.getIdMapper
    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores = pq.createScoreArray // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val libId = libraryIdDocValues.get(docId)

      if (idFilter.findIndex(libId) < 0) { // use findIndex to avoid boxing
        val visibility = keepVisibilityEvaluator(docId)

        if (visibility != Visibility.RESTRICTED) {
          val recencyBoost = getRecencyBoost(recencyScorer, docId)
          val inverseLibraryFrequencyBoost = {
            val keepCount = libraryQualityEvaluator.estimateKeepCount(searcher, libId)
            libraryQualityEvaluator.getInverseLibraryFrequencyBoost(keepCount)
          }
          val boost = recencyBoost * inverseLibraryFrequencyBoost

          // get all scores
          val size = pq.getTaggedScores(taggedScores, boost)
          val keepId = idMapper.getId(docId)

          // write to the buffer
          output.alloc(writer, visibility | Visibility.HAS_SECONDARY_ID, 8 + 8 + size * 4) // libId (8 bytes), keepId (8 bytes) and taggedFloats (size * 4 bytes)
          writer.putLong(libId, keepId).putTaggedFloatBits(taggedScores, size)
          explanation.foreach(_.collectBufferScoreContribution(libId, keepId, visibility, taggedScores, size, weights.length))

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
