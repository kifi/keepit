package com.keepit.search.engine

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.logging.Logging
import com.keepit.search.engine.explain.DirectExplainContext
import com.keepit.search.engine.query.QueryProjector
import com.keepit.search.graph.library.LibraryFields
import com.keepit.search.query.IdSetFilter
import com.keepit.search.{ SearchConfig, SearchFilter, Searcher }
import com.keepit.search.graph.keep.KeepFields
import com.keepit.search.index.WrappedSubReader
import com.keepit.search.util.LongArraySet
import com.keepit.search.util.join.{ DataBufferReader, DataBuffer, DataBufferWriter }
import org.apache.lucene.index.{ AtomicReaderContext, Term }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ Scorer, Query }
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.Future

class UriFromLibraryScoreVectorSource(
    librarySearcher: Searcher,
    keepSearcher: Searcher,
    libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    filter: SearchFilter,
    config: SearchConfig,
    monitoredAwait: MonitoredAwait) extends ScoreVectorSource with Logging with DebugOption {

  private[this] val libraryNameBoost = config.asFloat("libraryNameBoost")

  private[this] lazy val (myOwnLibraryIds, memberLibraryIds, trustedLibraryIds, authorizedLibraryIds) = {
    val (myLibIds, memberLibIds, trustedLibIds, authorizedLibIds) = monitoredAwait.result(libraryIdsFuture, 5 seconds, s"getting library ids")

    require(myLibIds.forall { libId => memberLibIds.contains(libId) }) // sanity check

    (LongArraySet.fromSet(myLibIds), LongArraySet.fromSet(memberLibIds), LongArraySet.fromSet(trustedLibIds), LongArraySet.fromSet(authorizedLibIds))
  }

  private[this] var myOwnLibraryKeepCount = 0
  private[this] var memberLibraryKeepCount = 0
  private[this] var authorizedLibraryKeepCount = 0

  private[this] val libraryNameSource: LibraryNameSource = new LibraryNameSource(librarySearcher, libraryIdsFuture, monitoredAwait, libraryNameBoost)

  override def prepare(query: Query, matchWeightNormalizer: MatchWeightNormalizer): Unit = {
    libraryNameSource.prepare(query, matchWeightNormalizer)
  }

  override def execute(coreSize: Int, dataBuffer: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    execute(coreSize, dataBuffer)
  }

  override def explain(targetId: Long, coreSize: Int, dataBuffer: DataBuffer, directExplainContext: DirectExplainContext): Unit = {
    execute(coreSize, dataBuffer)
  }

  private def execute(coreSize: Int, output: DataBuffer): Unit = {
    var libsSeen = Set.empty[Long]
    val writer: DataBufferWriter = new DataBufferWriter

    if (libraryNameBoost > 0.0f) {
      val taggedScores = libraryNameSource.createScoreArray()

      if (taggedScores.length > 0) {
        val libBuf = new DataBuffer
        libraryNameSource.execute(coreSize, libBuf, null)

        libBuf.scan(new DataBufferReader) { reader =>
          val visibility = reader.recordType
          val libId = reader.nextLong
          libsSeen += libId

          var size = 0
          while (reader.hasMore) {
            taggedScores(size) = reader.nextTaggedFloatBits()
            size += 1
          }

          val lastTotal = output.size
          loadWithScore(libId, visibility, taggedScores, size, output, writer)
          if ((visibility & Visibility.OWNER) != 0) myOwnLibraryKeepCount += output.size - lastTotal
          else memberLibraryKeepCount += output.size - lastTotal
        }
      }
    }

    // load remaining URIs in the network with no score.
    // this is necessary to categorize URIs correctly for boosting even when a query matches only in scraped data but not in personal meta data.
    loadWithNoScore(LongArraySet.fromSet(libsSeen), output, writer)

    if ((debugFlags & DebugOption.Library.flag) != 0) {
      listLibraries()
      listLibraryKeepCounts()
    }
  }

  private def indexReaderContexts: Seq[AtomicReaderContext] = { keepSearcher.indexReader.getContext.leaves }

  private def loadWithScore(libId: Long, visibility: Int, taggedScores: Array[Int], size: Int, output: DataBuffer, writer: DataBufferWriter): Unit = {
    val v = visibility | Visibility.HAS_SECONDARY_ID | Visibility.LIB_NAME_MATCH

    indexReaderContexts.foreach { readerContext =>
      val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
      val idFilter = filter.idFilter

      val idMapper = reader.getIdMapper
      val uriIdDocValues = reader.getNumericDocValues(KeepFields.uriIdField)

      val td = reader.termDocsEnum(new Term(KeepFields.libraryField, libId.toString))
      if (td != null) {
        var docId = td.nextDoc()
        while (docId < NO_MORE_DOCS) {
          val uriId = uriIdDocValues.get(docId)

          if (idFilter.findIndex(uriId) < 0) { // use findIndex to avoid boxing
            val keepId = idMapper.getId(docId)

            // write to the buffer
            output.alloc(writer, v, 8 + 8 + 4 * size) // id (8 bytes), keepId (8 bytes), taggedScores (4 * size bytes)
            writer.putLong(uriId, keepId).putTaggedFloatBits(taggedScores, size)
          }
          docId = td.nextDoc()
        }
      }
    }
  }

  private def loadWithNoScore(libsSeen: LongArraySet, output: DataBuffer, writer: DataBufferWriter): Unit = {
    indexReaderContexts.foreach { readerContext =>
      val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
      val idFilter = filter.idFilter

      val idMapper = reader.getIdMapper
      val uriIdDocValues = reader.getNumericDocValues(KeepFields.uriIdField)

      def load(libId: Long, visibility: Int): Unit = {
        val v = visibility | Visibility.HAS_SECONDARY_ID
        val td = reader.termDocsEnum(new Term(KeepFields.libraryField, libId.toString))
        if (td != null) {
          var docId = td.nextDoc()
          while (docId < NO_MORE_DOCS) {
            val uriId = uriIdDocValues.get(docId)

            if (idFilter.findIndex(uriId) < 0) { // use findIndex to avoid boxing
              val keepId = idMapper.getId(docId)

              // write to the buffer
              output.alloc(writer, v, 8 + 8) // id (8 bytes), keepId (8 bytes)
              writer.putLong(uriId, keepId)
            }
            docId = td.nextDoc()
          }
        }
      }

      // load URIs from my own libraries
      var lastTotal = output.size
      myOwnLibraryIds.foreachLong { libId =>
        if (libsSeen.findIndex(libId) < 0) load(libId, Visibility.OWNER)
      }
      myOwnLibraryKeepCount += output.size - lastTotal

      // load URIs from libraries I am a member of
      // memberLibraryIds includes myOwnLibraryIds
      lastTotal = output.size
      memberLibraryIds.foreachLong { libId =>
        if (libsSeen.findIndex(libId) < 0 && myOwnLibraryIds.findIndex(libId) < 0) load(libId, Visibility.MEMBER)
      }
      memberLibraryKeepCount += output.size - lastTotal

      // load URIs from an authorized library as MEMBER
      lastTotal = output.size
      authorizedLibraryIds.foreachLong { libId =>
        if (libsSeen.findIndex(libId) < 0 && memberLibraryIds.findIndex(libId) < 0) load(libId, Visibility.MEMBER)
      }
      authorizedLibraryKeepCount += output.size - lastTotal
    }
  }

  protected def listLibraries(): Unit = {
    debugLog(s"""myLibs: ${myOwnLibraryIds.toSeq.sorted.mkString(",")}""")
    debugLog(s"""memberLibs: ${memberLibraryIds.toSeq.sorted.mkString(",")}""")
    debugLog(s"""trustedLibs: ${trustedLibraryIds.toSeq.sorted.mkString(",")}""")
    debugLog(s"""authorizedLibs: ${authorizedLibraryIds.toSeq.sorted.mkString(",")}""")
  }

  private def listLibraryKeepCounts(): Unit = {
    debugLog(s"""myOwnLibKeepCount: ${myOwnLibraryKeepCount}""")
    debugLog(s"""memberLibKeepCount: ${memberLibraryKeepCount}""")
    debugLog(s"""authorizedLibKeepCount: ${authorizedLibraryKeepCount}""")
  }

  private class LibraryNameSource(
      protected val searcher: Searcher,
      protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
      protected val monitoredAwait: MonitoredAwait,
      libraryNameBoost: Float) extends ScoreVectorSourceLike {

    private[this] lazy val libIdFilter = new IdSetFilter(memberLibraryIds)

    override protected def preprocess(query: Query): Query = QueryProjector.project(query, LibraryFields.nameSearchFields) // trim down to name fields

    protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
      val reader = readerContext.reader.asInstanceOf[WrappedSubReader]

      // execute the query
      val pq = createScorerQueue(scorers, coreSize)
      if (pq.size <= 0) return // no scorer

      val iterator = libIdFilter.getDocIdSet(readerContext, reader.getLiveDocs).iterator()

      val visibilityDocValues = reader.getNumericDocValues(LibraryFields.visibilityField)

      val idMapper = reader.getIdMapper
      val writer: DataBufferWriter = new DataBufferWriter

      val taggedScores: Array[Int] = pq.createScoreArray // tagged floats

      var docId = pq.top.doc
      while (docId < NO_MORE_DOCS) {
        if (iterator.docID < docId) iterator.advance(docId)

        if (iterator.docID == docId) {
          val libId = idMapper.getId(docId)

          val visibility = if (myOwnLibraryIds.contains(libId)) Visibility.OWNER else Visibility.MEMBER

          // get all scores
          val size = pq.getTaggedScores(taggedScores, libraryNameBoost)

          // write to the buffer
          output.alloc(writer, visibility, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
          writer.putLong(libId).putTaggedFloatBits(taggedScores, size)

          docId = pq.top.doc // next doc
        } else {
          docId = iterator.docID
          if (docId < NO_MORE_DOCS) docId = pq.advance(docId)
        }
      }
    }

    def createScoreArray(): Array[Int] = new Array[Int](weights.size)
  }
}
