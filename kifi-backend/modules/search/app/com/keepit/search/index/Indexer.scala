package com.keepit.search.index

import java.io.IOException
import java.lang.OutOfMemoryError
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index.CorruptIndexException
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import play.modules.statsd.api.Statsd
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
  }
}

trait IndexingEventHandler[T] {
  protected[this] var _successCount: Int = 0
  protected[this] var _failureCount: Int = 0

  def successCount = _successCount

  def failureCount = _failureCount

  def onStart(batch: Seq[Indexable[T]]): Unit = {}

  def onCommit(successful: Seq[Indexable[T]]): Unit = {}

  def onSuccess(indexable: Indexable[T]): Unit = {
    _successCount += 1
  }

  def onFailure(indexable: Indexable[T], e: Throwable): Unit = {
    _failureCount += 1
    throw e
  }
}

abstract class Indexer[T](
    indexDirectory: IndexDirectory,
    indexWriterConfig: IndexWriterConfig,
    fieldDecoders: Map[String, FieldDecoder])
  extends IndexingEventHandler[T] with Logging {

  def this(indexDirectory: IndexDirectory, indexWriterConfig: IndexWriterConfig) = this(indexDirectory, indexWriterConfig, Map.empty[String, FieldDecoder])

  val commitBatchSize = 1000

  lazy val indexWriter = new IndexWriter(indexDirectory, indexWriterConfig)
  private[this] val indexWriterLock = new AnyRef

  val indexWarmer: Option[IndexWarmer] = None

  protected var searcher: Searcher = {
    if (!DirectoryReader.indexExists(indexDirectory)) {
      val seedDoc = new Document()
      val idTerm = new Term(Indexer.idFieldName, (-1L).toString)
      seedDoc.add(new Field(Indexer.idFieldName, idTerm.text(), Indexable.keywordFieldType))
      indexWriter.updateDocument(idTerm, seedDoc)
      indexWriter.commit()
    }
    val reader = getReader()
    Searcher(reader)
  }

  private[this] def getReader(): DirectoryReader = {
    log.info(s"warming up an index directory [${indexDirectory.toString}]...")
    val startTime = System.currentTimeMillis

    val reader = DirectoryReader.open(indexDirectory)

    val buffer = new Array[Byte](1<<16)
    reader.getIndexCommit().getFileNames().foreach{ filename =>
      if (indexDirectory.fileExists(filename)) {
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

    reader
  }

  def getSearcher = searcher

  protected val updateLock = new AnyRef

  private[this] var _sequenceNumber = SequenceNumber(initialSequenceNumberValue)
  def initialSequenceNumberValue: Long = (commitData.getOrElse(Indexer.CommitData.sequenceNumber, "-1").toLong)

  def sequenceNumber = _sequenceNumber

  private[search] def sequenceNumber_=(n: SequenceNumber) {
    _sequenceNumber = n
  }

  private[this] var resetSequenceNumber = false
  def reindex() { resetSequenceNumber = true }
  protected def resetSequenceNumberIfReindex() {
    if (resetSequenceNumber) {
      resetSequenceNumber = false
      sequenceNumber = SequenceNumber.MinValue
    }
  }

  def doUpdate(name: String)(changedIndexables: => Iterator[Indexable[T]]): Int = {
    try {
      log.info(s"updating $name")
      val indexables = changedIndexables
      val cnt = successCount
      indexDocuments(indexables, commitBatchSize)
      successCount - cnt
    } catch { case ex: Throwable =>
      log.error(s"error in $name update", ex)
      throw ex
    }
  }

  def doWithIndexWriter(f: IndexWriter=>Unit) = {
    try {
      indexWriterLock.synchronized{ f(indexWriter) }
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
    indexWriter.close()
  }

  def indexDocuments(indexables: Iterator[Indexable[T]], commitBatchSize: Int, refresh: Boolean = true): Unit = {
    doWithIndexWriter{ indexWriter =>
      var maxSequenceNumber = sequenceNumber
      indexables.grouped(commitBatchSize).foreach{ indexableBatch =>
        var successful = new ArrayBuffer[Indexable[T]]
        onStart(indexableBatch)

        // create a map from id to its highest seqNum in the batch
        val idToSeqNum = indexableBatch.foldLeft(Map.empty[Id[T], SequenceNumber]){ (m, indexable) =>
          m + (indexable.id -> indexable.sequenceNumber)
        }
        val commitBatch = indexableBatch.filter{ indexable =>
          // ignore an indexable if its seqNum is old
          idToSeqNum.get(indexable.id) match {
            case Some(seqNum) => (seqNum == indexable.sequenceNumber)
            case None => false
          }
        }.map{ indexable =>
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
              document.foreach{ doc => indexWriter.updateDocument(indexable.idTerm, doc) }
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

  private def commit(seqNum: SequenceNumber = this.sequenceNumber) {
    this.sequenceNumber = seqNum
    indexWriter.setCommitData(Map(
          Indexer.CommitData.committedAt -> currentDateTime.toStandardTimeString,
          Indexer.CommitData.sequenceNumber -> sequenceNumber.toString
    ))
    indexWriter.commit()
    log.info(s"index committed seqNum=${seqNum}")
  }

  def backup(): Unit = {
    log.info("Index will be backed up on next commit")
    indexDirectory.scheduleBackup()
  }

  override def onCommit(successful: Seq[Indexable[T]]): Unit =
    try {
      val start = currentDateTime.getMillis
      if (indexDirectory.doBackup()) {
        val end = currentDateTime.getMillis
        Statsd.gauge(Seq("index", indexDirectory.getDirectory().getName, "size").mkString("."), FileUtils.sizeOfDirectory(indexDirectory.getDirectory()))
        log.info(s"Index directory has been backed up in ${ (end - start) / 1000} seconds")
      }
    } catch {
      case e: Throwable => log.error("Index directory could not be backed up", e)
    }

  def deleteAllDocuments(refresh: Boolean = true) {
    if (DirectoryReader.indexExists(indexDirectory)) {
      doWithIndexWriter{ indexWriter =>
        indexWriter.deleteAll()
        commit()
      }
    }
    if (refresh) refreshSearcher()
  }

  def commitData: Map[String, String] = {
    // get the latest commit
    val indexReader = Option(DirectoryReader.openIfChanged(searcher.indexReader.inner)).getOrElse(searcher.indexReader.inner)
    val indexCommit = indexReader.getIndexCommit()
    val mutableMap = indexCommit.getUserData()
    log.info("commit data =" + mutableMap)
    Map() ++ mutableMap
  }

  def commitSequenceNumber: Option[SequenceNumber] = {
    commitData.get(Indexer.CommitData.sequenceNumber).map(v => SequenceNumber(v.toLong))
  }

  def committedAt: Option[String] = {
    commitData.get(Indexer.CommitData.committedAt)
  }

  def numDocs = (indexWriter.numDocs() - 1) // minus the seed doc

  def refreshSearcher() {
    searcher = Searcher.reopen(searcher, indexWarmer)
  }

  def getFieldDecoder(fieldName: String) = {
    fieldDecoders.get(fieldName) match {
      case Some(decoder) => decoder
      case _ => fieldName match {
        case Indexer.idValueFieldName => DocUtil.IdValueFieldDecoder
        case _ => DocUtil.TextFieldDecoder
      }
    }
  }
}

