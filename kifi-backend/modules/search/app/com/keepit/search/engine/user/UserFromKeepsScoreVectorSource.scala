package com.keepit.search.engine.user

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

class UserFromKeepsScoreVectorSource(
    protected val searcher: Searcher,
    protected val userId: Long,
    protected val friendIdsFuture: Future[Set[Long]],
    protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    filter: SearchFilter,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait,
    libraryQualityEvaluator: LibraryQualityEvaluator,
    explanation: Option[UserSearchExplanationBuilder]) extends ScoreVectorSourceLike with KeepRecencyEvaluator with VisibilityEvaluator {

  override protected def preprocess(query: Query): Query = QueryProjector.project(query, KeepFields.textSearchFields)

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = filter.idFilter

    val pq = createScorerQueue(scorers, coreSize)
    if (pq.size <= 0) return // no scorer

    val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)
    val userIdDocValues = reader.getNumericDocValues(KeepFields.userIdField)
    val visibilityDocValues = reader.getNumericDocValues(KeepFields.visibilityField)
    val keepVisibilityEvaluator = getKeepVisibilityEvaluator(userIdDocValues, visibilityDocValues)

    val recencyScorer = getRecencyScorer(readerContext)
    if (recencyScorer == null) log.warn("RecencyScorer is null")

    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores = pq.createScoreArray // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val libId = libraryIdDocValues.get(docId)

      if (idFilter.findIndex(libId) < 0) { // use findIndex to avoid boxing
        val visibility = keepVisibilityEvaluator(docId, libId)

        if (visibility != Visibility.RESTRICTED) {
          val recencyBoost = getRecencyBoost(recencyScorer, docId)
          val inverseLibraryFrequencyBoost = {
            val keepCount = libraryQualityEvaluator.estimateKeepCount(searcher, libId)
            libraryQualityEvaluator.getInverseLibraryFrequencyBoost(keepCount)
          }
          val boost = recencyBoost * inverseLibraryFrequencyBoost

          // get all scores
          val size = pq.getTaggedScores(taggedScores, boost)
          val keeperId = userIdDocValues.get(docId)

          // write to the buffer
          output.alloc(writer, visibility | Visibility.HAS_SECONDARY_ID, 8 + 8 + size * 4) // keeperId (8 bytes), libId (8 bytes) and taggedFloats (size * 4 bytes)
          writer.putLong(keeperId, libId).putTaggedFloatBits(taggedScores, size)
          explanation.foreach(_.collectBufferScoreContribution(keeperId, libId, visibility, taggedScores, size, weights.length))

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
