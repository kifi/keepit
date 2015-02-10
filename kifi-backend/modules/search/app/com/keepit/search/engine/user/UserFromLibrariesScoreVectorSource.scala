package com.keepit.search.engine.user

import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.engine.{ DirectScoreContext, Visibility, VisibilityEvaluator, ScoreVectorSourceLike }
import com.keepit.search.engine.query.core.QueryProjector
import com.keepit.search.index.graph.keep.KeepFields
import com.keepit.search.index.graph.library.LibraryFields
import com.keepit.search.util.LongArraySet
import com.keepit.search.{ SearchFilter, SearchConfig }
import com.keepit.search.index.{ IdMapper, Searcher, WrappedSubReader }
import com.keepit.search.util.join.{ DataBuffer, DataBufferWriter }
import org.apache.lucene.index.{ Term, NumericDocValues, AtomicReaderContext }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ Query, Scorer }
import scala.concurrent.Future

class UserFromLibrariesScoreVectorSource(
    protected val searcher: Searcher,
    protected val userId: Long,
    protected val friendIdsFuture: Future[Set[Long]],
    protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    filter: SearchFilter,
    protected val config: SearchConfig,
    protected val monitoredAwait: MonitoredAwait,
    explanation: Option[UserSearchExplanationBuilder]) extends ScoreVectorSourceLike with VisibilityEvaluator {

  override protected def preprocess(query: Query): Query = QueryProjector.project(query, LibraryFields.strictTextSearchFields) // no prefix search

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    val idFilter = filter.idFilter

    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]

    val idMapper = reader.getIdMapper
    val ownerIdDocValues = reader.getNumericDocValues(LibraryFields.ownerIdField)

    val writer: DataBufferWriter = new DataBufferWriter

    // load all owners of libraries the user is a member of with no score.
    // this is necessary to categorize users correctly for boosting even against a match again their name or another of their keep / library
    loadMemberLibraryOwners(idFilter, reader, idMapper, ownerIdDocValues, writer, output)

    // execute the query
    val pq = createScorerQueue(scorers, coreSize)
    if (pq.size <= 0) return // no scorer

    val visibilityDocValues = reader.getNumericDocValues(LibraryFields.visibilityField)
    val libraryVisibilityEvaluator = getLibraryVisibilityEvaluator(ownerIdDocValues, visibilityDocValues)

    val taggedScores: Array[Int] = pq.createScoreArray // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val ownerId = ownerIdDocValues.get(docId)

      if (idFilter.findIndex(ownerId) < 0) { // use findIndex to avoid boxing
        val libId = idMapper.getId(docId)

        val visibility = libraryVisibilityEvaluator(docId, libId)

        if (visibility != Visibility.RESTRICTED) {
          // get all scores
          val size = pq.getTaggedScores(taggedScores)

          // write to the buffer
          output.alloc(writer, visibility | Visibility.HAS_SECONDARY_ID, 8 + 8 + size * 4) // ownerId (8 bytes), libId (8 bytes) and taggedFloats (size * 4 bytes)
          writer.putLong(ownerId, libId).putTaggedFloatBits(taggedScores, size)
          explanation.foreach(_.collectBufferScoreContribution(userId, libId, visibility, taggedScores, size, weights.length))

          docId = pq.top.doc // next doc
        } else {
          docId = pq.skipCurrentDoc() // skip this doc
        }
      } else {
        docId = pq.skipCurrentDoc() // skip this doc
      }
    }
  }

  private def loadMemberLibraryOwners(idFilter: LongArraySet, reader: WrappedSubReader, idMapper: IdMapper, ownerIdDocValues: NumericDocValues, writer: DataBufferWriter, output: DataBuffer): Unit = {
    memberLibraryIds.foreachLong { libId =>
      val docId = idMapper.getDocId(libId)
      if (docId >= 0) {
        val ownerId = ownerIdDocValues.get(docId)
        if (idFilter.findIndex(ownerId) < 0) { // use findIndex to avoid boxing
          // write to the buffer
          output.alloc(writer, Visibility.MEMBER, 8 + 8) // ownerId (8 bytes), libId (8 bytes)
          writer.putLong(ownerId, libId)
          explanation.foreach(_.collectBufferScoreContribution(ownerId, libId, Visibility.MEMBER, Array.empty[Int], 0, 0))
        }
      }
    }
  }
}
