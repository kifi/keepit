package com.keepit.search.engine

import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.engine.explain.TargetedScorer
import com.keepit.search.engine.query.core.QueryProjector
import com.keepit.search.{ SearchFilter, SearchConfig, Searcher }
import com.keepit.search.index.graph.keep.KeepFields
import com.keepit.search.index.WrappedSubReader
import com.keepit.search.util.join.{ DataBuffer, DataBufferWriter }
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ Query, Scorer }
import scala.concurrent.Future

class LibraryFromKeepsScoreVectorSource(
    protected val searcher: Searcher,
    protected val userId: Long,
    protected val friendIdsFuture: Future[Set[Long]],
    protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    filter: SearchFilter,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait) extends ScoreVectorSourceLike with KeepRecencyEvaluator with VisibilityEvaluator {

  override protected def idResolver(readerContext: AtomicReaderContext): TargetedScorer.Resolver = {
    docValueBasedIdResolver(readerContext, KeepFields.libraryIdField)
  }

  override protected def preprocess(query: Query): Query = QueryProjector.project(query, KeepFields.textSearchFields)

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = filter.idFilter

    val pq = createScorerQueue(scorers, coreSize)
    if (pq.size <= 0) return // no scorer

    val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)
    val visibilityDocValues = reader.getNumericDocValues(KeepFields.visibilityField)
    val libraryVisibilityEvaluator = getLibraryVisibilityEvaluator(visibilityDocValues)

    val recencyScorer = getRecencyScorer(readerContext)
    if (recencyScorer == null) log.warn("RecencyScorer is null")

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
          writer.putLong(libId, keepId).putTaggedFloatBits(taggedScores, size)

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
