package com.keepit.search.engine.uri

import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.engine._
import com.keepit.search.engine.query.core.QueryProjector
import com.keepit.search.{ SearchContext, SearchConfig }
import com.keepit.search.index.graph.keep.KeepFields
import com.keepit.search.index.{ IdMapper, Searcher, WrappedSubReader }
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
    protected val orgIdsFuture: Future[Set[Long]],
    protected val context: SearchContext,
    recencyOnly: Boolean,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait,
    explanation: Option[UriSearchExplanationBuilder]) extends ScoreVectorSourceLike with KeepRecencyEvaluator with VisibilityEvaluator {

  override protected def preprocess(query: Query): Query = {
    val searchFields = KeepFields.minimalSearchFields ++ KeepFields.prefixSearchFields ++ (if (context.disableFullTextSearch) Set.empty else KeepFields.fullTextSearchFields)
    QueryProjector.project(query, searchFields)
  }

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = context.idFilter

    val keepVisibilityEvaluator = getKeepVisibilityEvaluator(reader)
    val idMapper = reader.getIdMapper
    val uriIdDocValues = reader.getNumericDocValues(KeepFields.uriIdField)

    val writer: DataBufferWriter = new DataBufferWriter

    // load all discoverable URIs in the network with no score.
    // this is necessary to categorize URIs correctly for boosting even when a query matches only in scraped data but not in personal meta data.
    loadDiscoverableURIs(keepVisibilityEvaluator, idFilter, reader, idMapper, uriIdDocValues, writer, output)

    // execute the query
    val pq = createScorerQueue(scorers, coreSize)
    if (pq.size <= 0) return // no scorer

    val recencyScorer = if (recencyOnly) getSlowDecayingRecencyScorer(readerContext) else getRecencyScorer(readerContext)
    if (recencyScorer == null) log.warn("RecencyScorer is null")

    val taggedScores: Array[Int] = pq.createScoreArray // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val visibility = keepVisibilityEvaluator(docId)

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

  private def loadDiscoverableURIs(keepVisibilityEvaluator: KeepVisibilityEvaluator, idFilter: LongArraySet, reader: WrappedSubReader, idMapper: IdMapper, uriIdDocValues: NumericDocValues, writer: DataBufferWriter, output: DataBuffer): Unit = {
    def loadWithNoScore(term: Term, visibility: Int): Int = {
      val v = visibility | Visibility.HAS_SECONDARY_ID
      val td = reader.termDocsEnum(term)
      val initialOutputSize = output.size
      if (td != null) {
        var docId = td.nextDoc()
        while (docId < NO_MORE_DOCS) {
          val uriId = uriIdDocValues.get(docId)

          if (idFilter.findIndex(uriId) < 0 && keepVisibilityEvaluator.isRelevant(docId)) { // use findIndex to avoid boxing
            val keepId = idMapper.getId(docId)
            // write to the buffer
            output.alloc(writer, v, 8 + 8) // uriId (8 bytes), keepId (8 bytes)
            writer.putLong(uriId, keepId)
            explanation.foreach(_.collectBufferScoreContribution(uriId, keepId, visibility, Array.empty[Int], 0, 0))
          }
          docId = td.nextDoc()
        }
      }
      output.size - initialOutputSize
    }

    loadWithNoScore(new Term(KeepFields.ownerField, userId.toString), Visibility.OWNER)
    loadWithNoScore(new Term(KeepFields.userField, userId.toString), Visibility.MEMBER)
    myLibraryIds.foreachLong { libId =>
      loadWithNoScore(new Term(KeepFields.libraryField, libId.toString), Visibility.MEMBER)
    }

    myFriendIds.foreachLong { friendId =>
      loadWithNoScore(new Term(KeepFields.userDiscoverableField, friendId.toString), Visibility.NETWORK)
    }

    myOrgIds.foreachLong { friendId =>
      loadWithNoScore(new Term(KeepFields.userDiscoverableField, friendId.toString), Visibility.NETWORK)
    }
  }
}
