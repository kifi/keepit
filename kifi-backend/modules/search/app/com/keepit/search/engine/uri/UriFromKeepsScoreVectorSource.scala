package com.keepit.search.engine.uri

import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.engine._
import com.keepit.search.engine.query.core.QueryProjector
import com.keepit.search.{ SearchFilter, SearchConfig }
import com.keepit.search.index.graph.keep.KeepFields
import com.keepit.search.index.{ Searcher, IdMapper, WrappedSubReader }
import com.keepit.search.util.LongArraySet
import com.keepit.search.util.join.{ DataBuffer, DataBufferWriter }
import org.apache.lucene.index.{ NumericDocValues, Term, AtomicReaderContext }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ Query, Scorer }
import scala.concurrent.Future

class UriFromKeepsScoreVectorSource(
    protected val searcher: Searcher,
    protected val userId: Long,
    protected val friendIdsFuture: Future[Set[Long]],
    protected val restrictedUserIdsFuture: Future[Set[Long]],
    protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    filter: SearchFilter,
    recencyOnly: Boolean,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait,
    explanation: Option[UriSearchExplanationBuilder]) extends ScoreVectorSourceLike with KeepRecencyEvaluator with VisibilityEvaluator {

  private[this] var discoverableKeepCount = 0

  override protected def preprocess(query: Query): Query = QueryProjector.project(query, KeepFields.textSearchFields)

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = filter.idFilter

    val idMapper = reader.getIdMapper
    val uriIdDocValues = reader.getNumericDocValues(KeepFields.uriIdField)

    val writer: DataBufferWriter = new DataBufferWriter

    // load all discoverable URIs in the network with no score.
    // this is necessary to categorize URIs correctly for boosting even when a query matches only in scraped data but not in personal meta data.
    loadDiscoverableURIs(idFilter, reader, idMapper, uriIdDocValues, writer, output)

    // execute the query
    val pq = createScorerQueue(scorers, coreSize)
    if (pq.size <= 0) return // no scorer

    val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)
    val userIdDocValues = reader.getNumericDocValues(KeepFields.userIdField)
    val visibilityDocValues = reader.getNumericDocValues(KeepFields.visibilityField)
    val keepVisibilityEvaluator = getKeepVisibilityEvaluator(userIdDocValues, visibilityDocValues)
    val recencyScorer = if (recencyOnly) getSlowDecayingRecencyScorer(readerContext) else getRecencyScorer(readerContext)
    if (recencyScorer == null) log.warn("RecencyScorer is null")

    val taggedScores: Array[Int] = pq.createScoreArray // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val libId = libraryIdDocValues.get(docId)
      val visibility = keepVisibilityEvaluator(docId, libId)

      if (visibility != Visibility.RESTRICTED) {
        val uriId = uriIdDocValues.get(docId)

        if (idFilter.findIndex(uriId) < 0) {

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
          writer.putLong(uriId, keepId).putTaggedFloatBits(taggedScores, size)
          explanation.foreach(_.collectBufferScoreContribution(uriId, keepId, visibility, taggedScores, size, weights.length))

          docId = pq.top.doc // next doc
        } else {
          docId = pq.skipCurrentDoc() // this keep is not searchable, skipping...
        }
      } else {
        docId = pq.skipCurrentDoc() // this keep is not searchable, skipping...
      }
    }
  }

  private def loadDiscoverableURIs(idFilter: LongArraySet, reader: WrappedSubReader, idMapper: IdMapper, uriIdDocValues: NumericDocValues, writer: DataBufferWriter, output: DataBuffer): Unit = {
    val lastTotal = output.size
    myFriendIds.foreachLong { friendId =>
      val td = reader.termDocsEnum(new Term(KeepFields.userDiscoverableField, friendId.toString))
      if (td != null) {
        var docId = td.nextDoc()
        while (docId < NO_MORE_DOCS) {
          val uriId = uriIdDocValues.get(docId)

          if (idFilter.findIndex(uriId) < 0) { // use findIndex to avoid boxing
            // write to the buffer
            output.alloc(writer, Visibility.NETWORK, 8) // id (8 bytes)
            writer.putLong(uriId)
            explanation.foreach(_.collectBufferScoreContribution(uriId, -1, Visibility.NETWORK, Array.empty[Int], 0, 0))
          }
          docId = td.nextDoc()
        }
      }
    }
    discoverableKeepCount += output.size - lastTotal
  }
}
