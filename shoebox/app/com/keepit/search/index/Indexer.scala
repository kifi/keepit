package com.keepit.search.index

import scala.collection.JavaConversions._
import java.io.IOException
import java.lang.OutOfMemoryError
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.CorruptIndexException
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.store.Directory
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.common.logging.Logging
import com.keepit.common.time._

case class IndexError(msg: String)

object Indexer {
  val idFieldName = "_ID"
  val idValueFieldName = "_ID_VAL"

  val DELETED_ID = -1

  object CommitData {
    val committedAt = "COMMITTED_AT"
    val sequenceNumber = "SEQUENCE_NUMBER"
  }
}

abstract class Indexer[T](indexDirectory: Directory, indexWriterConfig: IndexWriterConfig, fieldDecoders: Map[String, FieldDecoder]) extends Logging {
  def this(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig) = this(indexDirectory, indexWriterConfig, Map.empty[String, FieldDecoder])
  lazy val indexWriter = new IndexWriter(indexDirectory, indexWriterConfig)

  protected var searcher: Searcher = {
    if (!DirectoryReader.indexExists(indexDirectory)) {
      val seedDoc = new Document()
      val idTerm = new Term(Indexer.idFieldName, (-1L).toString)
      seedDoc.add(new Field(Indexer.idFieldName, idTerm.text(), Indexable.keywordFieldType))
      indexWriter.updateDocument(idTerm, seedDoc)
      indexWriter.commit()
    }
    val reader = DirectoryReader.open(indexDirectory)
    Searcher(reader)
  }

  def getSearcher = searcher

  private var _sequenceNumber =
    SequenceNumber(commitData.getOrElse(Indexer.CommitData.sequenceNumber, "-1").toLong)

  def sequenceNumber = _sequenceNumber

  private[search] def sequenceNumber_=(n: SequenceNumber) {
    _sequenceNumber = n
  }

  def doWithIndexWriter(f: IndexWriter=>Unit) = {
    try {
      indexWriter.synchronized{ f(indexWriter) }
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

  def indexDocuments(indexables: Iterator[Indexable[T]], commitBatchSize: Int, refresh: Boolean = true)(afterCommit: Seq[(Indexable[T], Option[IndexError])]=>Unit): Unit = {
    doWithIndexWriter{ indexWriter =>
      var maxSequenceNumber = sequenceNumber
      indexables.grouped(commitBatchSize).foreach{ indexableBatch =>
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
          val document = try {
            Left(indexable.buildDocument)
          } catch {
            case e: Throwable =>
              val msg = "failed to build document for id=%s".format(indexable.id)
              log.error(msg, e)
              Right(IndexError(msg))
          }
          val error = document match {
            case Left(doc) =>
              try {
                if (indexable.isDeleted) {
                  indexWriter.deleteDocuments(indexable.idTerm)
                } else {
                  indexWriter.updateDocument(indexable.idTerm, doc)
                }
                if (maxSequenceNumber < indexable.sequenceNumber)
                  maxSequenceNumber = indexable.sequenceNumber
                log.debug("indexed id=%s seq=%s".format(indexable.id, indexable.sequenceNumber))
                None
              } catch {
                case e: CorruptIndexException => throw e  // fatal
                case e: OutOfMemoryError => throw e       // fatal
                case e: IOException => throw e            // fatal
                case e: Throwable =>
                  val msg = "failed to index document for id=%s".format(indexable.id)
                  log.error(msg, e)
                  Some(IndexError(msg))
              }
            case Right(error) => Some(error)
          }
          (indexable, error)
        }
        commit(maxSequenceNumber)
        afterCommit(commitBatch)
      }
    }
    if (refresh) refreshSearcher()
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

  def numDocs = (indexWriter.numDocs() - 1) // minus the seed doc

  def refreshSearcher() {
    searcher = Searcher.reopen(searcher)
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

