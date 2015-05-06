package com.keepit.search.index

import java.io.IOException
import com.keepit.search.index.Indexer.CommitData
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index._
import org.apache.lucene.util.Version
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.search.Similarity
import org.apache.commons.io.FileUtils
import org.apache.lucene.store.IOContext
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.math._

object Indexer {
  val idFieldName = "_ID"
  val idValueFieldName = "_ID_VAL"

  val DELETED_ID = -1

  object CommitData {
    val committedAt = "COMMITTED_AT"
    val sequenceNumber = "SEQUENCE_NUMBER"
    val catchUpSeqNumForReindex = "CATCH_UP_SEQ_NUM_FOR_REINDEX"
  }

  case class CommitData[S](data: Map[String, String]) {
    def sequenceNumber: SequenceNumber[S] = SequenceNumber(data.getOrElse(CommitData.sequenceNumber, "-1").toLong)

    def catchUpSequenceNumber: SequenceNumber[S] = SequenceNumber(data.getOrElse(CommitData.catchUpSeqNumForReindex, "-1").toLong)

    def committedAt: Option[String] = data.get(CommitData.committedAt)
  }
}

trait IndexingEventHandler[T, S] {
  protected[this] var _successCount: Int = 0
  protected[this] var _failureCount: Int = 0

  def successCount = _successCount

  def failureCount = _failureCount

  def onStart(batch: Seq[Indexable[T, S]]): Unit = {}

  def onCommit(successful: Seq[Indexable[T, S]]): Unit = {}

  def onSuccess(indexable: Indexable[T, S]): Unit = {
    _successCount += 1
  }

  def onFailure(indexable: Indexable[T, S], e: Throwable): Unit = {
    _failureCount += 1
    throw e
  }
}

abstract class Indexer[T, S, I <: Indexer[T, S, I]](
  indexDirectory: IndexDirectory,
  maxPrefixLength: Int = Int.MaxValue)
    extends IndexManager[S, I] with IndexingEventHandler[T, S] with Logging {

  val commitBatchSize = 1000

  protected def indexWriterConfig = new IndexWriterConfig(Version.LATEST, DefaultAnalyzer.defaultAnalyzer)

  private[this] var indexWriter: IndexWriter = null

  private[this] val indexWriterLock = new AnyRef

  private[this] def reopenWriter(): Unit = indexWriterLock.synchronized {
    if (indexWriter != null) indexWriter.close
    indexWriter = new IndexWriter(indexDirectory, indexWriterConfig)
    if (!DirectoryReader.indexExists(indexDirectory)) {
      val seedDoc = new Document()
      val idTerm = new Term(Indexer.idFieldName, (-1L).toString)
      seedDoc.add(new Field(Indexer.idFieldName, idTerm.text(), Indexable.keywordFieldType))
      indexWriter.updateDocument(idTerm, seedDoc)
      indexWriter.commit()
    }
  }

  reopenWriter() // open indexWriter

  protected var searcher: Searcher = indexWriterLock.synchronized {
    val s = Searcher(DirectoryReader.open(indexDirectory), maxPrefixLength)
    s.setSimilarity(Similarity()) // use our default similarity (not lucene's default similarity)
    s
  }

  def warmUpIndexDirectory(): Unit = indexDirectory.asFile().map(_.list().toSet).foreach { existingFileNames =>
    log.info(s"warming up an index directory [${indexDirectory.toString}]...")
    val startTime = System.currentTimeMillis
    val reader: DirectoryReader = searcher.indexReader.inner
    val buffer = new Array[Byte](1 << 16)

    reader.getIndexCommit().getFileNames().foreach { filename =>
      if (existingFileNames.contains(filename)) {
        var remaining = indexDirectory.fileLength(filename)
        val input = indexDirectory.openInput(filename, new IOContext)
        while (remaining > 0) {
          val amount = min(remaining, buffer.length)
          input.readBytes(buffer, 0, amount.toInt)
          remaining -= amount
        }
      }
    }
    val elapsed = System.currentTimeMillis - startTime
    log.info(s"warmed up an index directory [${indexDirectory.toString}], took ${elapsed}ms")
  }

  def getSearcher = searcher

  protected val updateLock = new AnyRef

  private[this] var _sequenceNumber = commitSequenceNumber

  def sequenceNumber = _sequenceNumber

  private[search] def sequenceNumber_=(n: SequenceNumber[S]) {
    _sequenceNumber = n
  }

  def catchUpSeqNumber_=(n: SequenceNumber[S]) { _catchUpSeqNumber = n }

  private[this] var _catchUpSeqNumber = commitSequenceNumber max commitCatchUpSequenceNumber

  def catchUpSeqNumber = _catchUpSeqNumber

  private[this] var resetSequenceNumber = false

  def reindex() { resetSequenceNumber = true }

  protected def resetSequenceNumberIfReindex() {
    if (resetSequenceNumber) {
      resetSequenceNumber = false
      sequenceNumber = SequenceNumber.MinValue[S]
    }
  }

  def doUpdate(name: String)(changedIndexables: => Iterator[Indexable[T, S]]): Int = {
    try {
      log.info(s"updating $name")
      val indexables = changedIndexables
      val cnt = successCount
      indexDocuments(indexables, commitBatchSize)
      successCount - cnt
    } catch {
      case ex: Throwable =>
        log.error(s"error in $name update", ex)
        throw ex
    }
  }

  def doWithIndexWriter(f: IndexWriter => Unit) = {
    try {
      indexWriterLock.synchronized { f(indexWriter) }
    } catch {
      case ioe: IOException =>
        log.error("indexing failed", ioe)
        throw ioe
      case cie: CorruptIndexException =>
        log.error("index corrupted", cie)
        throw cie
      case outOfMemory: OutOfMemoryError =>
        indexWriter.close // must close the indexWriter upon OutOfMemoryError
        throw outOfMemory
    }
  }

  def close(): Unit = {
    indexWriterLock.synchronized { indexWriter.close() }
  }

  def getIndexerFor(id: Long): Indexer[T, S, I] = this

  def indexInfos(name: String): Seq[IndexInfo] = {
    Seq(IndexInfo(
      name = name,
      numDocs = numDocs,
      sequenceNumber = commitSequenceNumber.value,
      committedAt = committedAt,
      indexSize = indexSize
    ))
  }

  def indexSize = indexDirectory.asFile().map(_.list().toSet).map { existingFileNames =>
    searcher.indexReader.inner.getIndexCommit().getFileNames().collect {
      case filename if existingFileNames.contains(filename) =>
        indexDirectory.fileLength(filename)
    }.sum
  }

  def indexDocuments(indexables: Iterator[Indexable[T, S]], commitBatchSize: Int, refresh: Boolean = true): Unit = {
    doWithIndexWriter { indexWriter =>
      var maxSequenceNumber = sequenceNumber
      indexables.grouped(commitBatchSize).foreach { indexableBatch =>
        var successful = new ArrayBuffer[Indexable[T, S]]
        onStart(indexableBatch)

        // create a map from id to its highest seqNum in the batch
        val idToSeqNum = indexableBatch.foldLeft(Map.empty[Id[T], SequenceNumber[S]]) { (m, indexable) =>
          m + (indexable.id -> indexable.sequenceNumber)
        }
        val commitBatch = indexableBatch.filter { indexable =>
          // ignore an indexable if its seqNum is old
          idToSeqNum.get(indexable.id) match {
            case Some(seqNum) => (seqNum == indexable.sequenceNumber)
            case None => false
          }
        }.map { indexable =>
          try {
            if (indexable.isDeleted) {
              indexWriter.deleteDocuments(indexable.idTerm)
            } else {
              val document = try {
                Some(indexable.buildDocument)
              } catch {
                case e: Throwable =>
                  onFailure(indexable, e)
                  None
              }
              document.foreach { doc => indexWriter.updateDocument(indexable.idTerm, doc) }
            }
            onSuccess(indexable)
            successful += indexable
            if (maxSequenceNumber < indexable.sequenceNumber)
              maxSequenceNumber = indexable.sequenceNumber
            log.debug("indexed id=%s seq=%s".format(indexable.id, indexable.sequenceNumber))
          } catch {
            case e: CorruptIndexException => {
              log.error("fatal indexing error", e)
              throw e
            }
            case e: OutOfMemoryError => {
              log.error("fatal indexing error", e)
              throw e
            }
            case e: IOException => {
              log.error("fatal indexing error", e)
              throw e
            }
            case e: Throwable =>
              val msg = s"failed to index document for id=${indexable.id}: ${e.getMessage()}"
              log.error(msg, e)
              onFailure(indexable, e)
          }
        }
        commit(maxSequenceNumber)
        onCommit(successful)
      }
    }
    if (refresh) refreshSearcher()
  }

  def deleteDocuments(term: Term, doCommit: Boolean) {
    indexWriter.deleteDocuments(term)
    if (doCommit) commit()
  }

  private def commit(seqNum: SequenceNumber[S] = this.sequenceNumber) {
    this.sequenceNumber = seqNum
    this.catchUpSeqNumber = SequenceNumber(catchUpSeqNumber.value max sequenceNumber.value)
    val commitData = Map(
      Indexer.CommitData.committedAt -> currentDateTime.toStandardTimeString,
      Indexer.CommitData.sequenceNumber -> sequenceNumber.toString,
      Indexer.CommitData.catchUpSeqNumForReindex -> catchUpSeqNumber.toString
    )
    indexWriter.setCommitData(commitData)
    indexWriter.commit()
    log.info(s"index committed: commitData=${commitData}")
  }

  private var _lastBackup: Long = System.currentTimeMillis
  override def lastBackup: Long = _lastBackup

  def backup(): Unit = {
    log.info("Index will be backed up on next commit")
    indexDirectory.scheduleBackup()
  }

  override def onCommit(successful: Seq[Indexable[T, S]]): Unit =
    try {
      val start = System.currentTimeMillis
      if (indexDirectory.doBackup()) {
        val end = System.currentTimeMillis
        _lastBackup = end
        indexDirectory.asFile().foreach { dir =>
          statsd.gauge(Seq("index", dir.getName, "size").mkString("."), FileUtils.sizeOfDirectory(dir))
        }
        log.info(s"Index directory has been backed up in ${(end - start) / 1000} seconds")
      }
    } catch {
      case e: Throwable => log.error("Index directory could not be backed up", e)
    }

  def deleteAllDocuments(refresh: Boolean = true) {
    if (DirectoryReader.indexExists(indexDirectory)) {
      doWithIndexWriter { indexWriter =>
        indexWriter.deleteAll()
        commit()
      }
    }
    if (refresh) refreshSearcher()
  }

  def commitData: CommitData[S] = {
    // Get the latest commit.
    // DirectoryReader.openIfChanged does not re-open the directory if only the commit data has been changed (as opposed to documents).
    // Using DirectoryReader.repo to make sure we have the latest commit data (in particular the latest sequence number, even if no document has been actually modified)
    val isSearcherDirectoryReaderCurrent = searcher.indexReader.inner.isCurrent
    val directoryReader = Option(DirectoryReader.openIfChanged(searcher.indexReader.inner)) getOrElse searcher.indexReader.inner
    val isDirectoryReaderCurrent = directoryReader.isCurrent
    log.info(s"Is current? Searcher: $isSearcherDirectoryReaderCurrent vs Re-opened: $isDirectoryReaderCurrent")
    val indexCommit = directoryReader.getIndexCommit
    val mutableMap = indexCommit.getUserData()
    CommitData(mutableMap.toMap)
  }

  def commitSequenceNumber: SequenceNumber[S] = commitData.sequenceNumber

  def commitCatchUpSequenceNumber: SequenceNumber[S] = commitData.catchUpSequenceNumber

  def committedAt: Option[String] = commitData.committedAt

  def numDocs: Int = (indexWriter.numDocs() - 1) // minus the seed doc

  def refreshSearcher(): Unit = {
    val s = Searcher.reopen(searcher)
    s.setSimilarity(Similarity()) // use our default similarity (not lucene's default similarity)
    searcher = s
  }

  def refreshWriter(): Unit = reopenWriter()
}

