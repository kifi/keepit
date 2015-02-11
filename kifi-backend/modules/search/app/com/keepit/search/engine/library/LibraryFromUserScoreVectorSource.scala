package com.keepit.search.engine.library

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.User
import com.keepit.search.engine._
import com.keepit.search.engine.query.core.QueryProjector
import com.keepit.search.index.graph.library.LibraryFields
import com.keepit.search.index.user.{ UserFields, UserIndexer }
import com.keepit.search.{ SearchConfig, SearchFilter }
import com.keepit.search.index.{ Searcher, WrappedSubReader }
import com.keepit.search.util.join.{ DataBufferReader, DataBuffer, DataBufferWriter }
import org.apache.lucene.index.{ AtomicReaderContext, Term }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ Scorer, Query }
import scala.collection.JavaConversions._
import scala.concurrent.Future

class LibraryFromUserScoreVectorSource(
    librarySearcher: Searcher,
    userSearcher: Searcher,
    val userId: Long,
    val friendIdsFuture: Future[Set[Long]],
    val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    filter: SearchFilter,
    config: SearchConfig,
    val monitoredAwait: MonitoredAwait,
    explanation: Option[LibrarySearchExplanationBuilder]) extends ScoreVectorSource with Logging with DebugOption with VisibilityEvaluator {

  private[this] val libraryOwnerBoost = config.asFloat("libraryOwnerBoost")
  private[this] val userSource: UserSource = new UserSource(userSearcher, libraryOwnerBoost)

  override def prepare(query: Query, matchWeightNormalizer: MatchWeightNormalizer): Unit = {
    userSource.prepare(query, matchWeightNormalizer)
  }

  override def execute(coreSize: Int, dataBuffer: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    execute(coreSize, dataBuffer)
  }

  private def execute(coreSize: Int, output: DataBuffer): Unit = {
    val writer: DataBufferWriter = new DataBufferWriter

    if (libraryOwnerBoost > 0.0f) {
      val taggedScores = userSource.createScoreArray()

      if (taggedScores.length > 0) {
        val userBuf = new DataBuffer
        userSource.execute(coreSize, userBuf, null)

        userBuf.scan(new DataBufferReader) { reader =>
          val userId = reader.nextLong

          var size = 0
          while (reader.hasMore) {
            taggedScores(size) = reader.nextTaggedFloatBits()
            size += 1
          }

          loadWithScore(userId, taggedScores, size, output, writer)
        }
      }
    }
  }

  private def indexReaderContexts: Seq[AtomicReaderContext] = { librarySearcher.indexReader.getContext.leaves }

  private def loadWithScore(userId: Long, taggedScores: Array[Int], size: Int, output: DataBuffer, writer: DataBufferWriter): Unit = {

    indexReaderContexts.foreach { readerContext =>
      val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
      val idFilter = filter.idFilter

      val visibilityDocValues = reader.getNumericDocValues(LibraryFields.visibilityField)
      val ownerIdDocValues = reader.getNumericDocValues(LibraryFields.ownerIdField)
      val libraryVisibilityEvaluator = getLibraryVisibilityEvaluator(ownerIdDocValues, visibilityDocValues)

      val idMapper = reader.getIdMapper

      val td = reader.termDocsEnum(new Term(LibraryFields.ownerField, userId.toString))
      if (td != null) {
        var docId = td.nextDoc()
        while (docId < NO_MORE_DOCS) {
          val libId = idMapper.getId(docId)
          lazy val visibility = libraryVisibilityEvaluator(docId, libId)

          if (idFilter.findIndex(libId) < 0 && visibility != Visibility.RESTRICTED) { // use findIndex to avoid boxing
            // write to the buffer
            output.alloc(writer, visibility, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
            writer.putLong(libId).putTaggedFloatBits(taggedScores, size)
            explanation.foreach(_.collectBufferScoreContribution(libId, -1, visibility, taggedScores, size, userSource.numberOfScorers))
          }
          docId = td.nextDoc()
        }
      }
    }
  }

  private class UserSource(protected val searcher: Searcher, libraryOwnerBoost: Float) extends ScoreVectorSourceLike {

    override protected def preprocess(query: Query): Query = QueryProjector.project(query, UserFields.nameSearchFields) // trim down to name fields

    protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
      val reader = readerContext.reader.asInstanceOf[WrappedSubReader]

      // execute the query
      val pq = createScorerQueue(scorers, coreSize)
      if (pq.size <= 0) return // no scorer

      val idMapper = reader.getIdMapper
      val writer: DataBufferWriter = new DataBufferWriter

      val taggedScores: Array[Int] = pq.createScoreArray // tagged floats

      var docId = pq.top.doc
      while (docId < NO_MORE_DOCS) {
        val userId = idMapper.getId(docId)

        // get all scores
        val size = pq.getTaggedScores(taggedScores, libraryOwnerBoost)

        // write to the buffer
        output.alloc(writer, 0, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
        writer.putLong(userId).putTaggedFloatBits(taggedScores, size)

        docId = pq.top.doc // next doc
      }
    }

    def createScoreArray(): Array[Int] = new Array[Int](weights.size)
    def numberOfScorers = weights.size
  }
}
