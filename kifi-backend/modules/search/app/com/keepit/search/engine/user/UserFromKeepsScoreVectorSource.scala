package com.keepit.search.engine.user

import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.engine._
import com.keepit.search.engine.query.core.QueryProjector
import com.keepit.search.{ SearchContext, SearchConfig }
import com.keepit.search.index.graph.library.LibraryIndexable
import com.keepit.search.index.graph.keep.KeepFields
import com.keepit.search.index.{ Searcher, WrappedSubReader }
import com.keepit.search.util.join.{ DataBuffer, DataBufferWriter }
import org.apache.lucene.index.{ NumericDocValues, BinaryDocValues, AtomicReaderContext }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ Query, Scorer }
import scala.concurrent.Future

class UserFromKeepsScoreVectorSource(
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
    explanation: Option[UserSearchExplanationBuilder]) extends ScoreVectorSourceLike with KeepRecencyEvaluator with VisibilityEvaluator {

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

    val ownerIdDocValues: NumericDocValues = reader.getNumericDocValues(KeepFields.ownerIdField)

    val recencyScorer = getRecencyScorer(readerContext)
    if (recencyScorer == null) log.warn("RecencyScorer is null")

    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores = pq.createScoreArray // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val keeperId = ownerIdDocValues.get(docId)

      if (keeperId > 0 && idFilter.findIndex(keeperId) < 0) { // use findIndex to avoid boxing
        val visibility = keepVisibilityEvaluator(docId) // todo(Léo): the keep visibility should probably not end up in the buffer

        if (visibility != Visibility.RESTRICTED) {
          val recencyBoost = getRecencyBoost(recencyScorer, docId)
          val boost = recencyBoost

          // get all scores
          val size = pq.getTaggedScores(taggedScores, boost)

          // write to the buffer
          output.alloc(writer, visibility, 8 + 8 + size * 4) // keeperId (8 bytes) and taggedFloats (size * 4 bytes)
          writer.putLong(keeperId).putTaggedFloatBits(taggedScores, size)
          explanation.foreach(_.collectBufferScoreContribution(keeperId, -1, visibility, taggedScores, size, weights.length))

          docId = pq.top.doc // next doc
        } else {
          docId = pq.skipCurrentDoc() // this keep is not searchable, skipping...
        }
      } else {
        docId = pq.skipCurrentDoc() // this keep owner is irrelevant anyway, skipping...
      }
    }
  }
}
