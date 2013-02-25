package com.keepit.search.index

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.search.Lang
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index.CorruptIndexException
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Payload
import org.apache.lucene.index.Term
import org.apache.lucene.store.Directory
import org.apache.lucene.search.Query
import org.apache.lucene.util.Version
import java.io.File
import java.io.IOException
import java.lang.OutOfMemoryError
import scala.collection.JavaConversions._
import scala.math._

case class IndexError(msg: String)

object Indexer {
  val idFieldName = "_ID"
  val idFieldTerm = new Term(idFieldName, "")
  val idPayloadFieldName = "_UD_PAYLOAD"
  val idPayloadTermText = "ID"
  val idPayloadTerm = new Term(idPayloadFieldName, idPayloadTermText)

  val DELETED_ID = -1

  object CommitData {
    val committedAt = "COMMITTED_AT"
  }
}

abstract class Indexer[T](indexDirectory: Directory, indexWriterConfig: IndexWriterConfig, fieldDecoders: Map[String, FieldDecoder]) extends Logging {
  def this(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig) = this(indexDirectory, indexWriterConfig, Map.empty[String, FieldDecoder])
  lazy val indexWriter = new IndexWriter(indexDirectory, indexWriterConfig)

  protected var searcher: Searcher = {
    if (!IndexReader.indexExists(indexDirectory)) {
      val seedDoc = new Document()
      val idTerm = Indexer.idFieldTerm.createTerm((-1L).toString)
      seedDoc.add(new Field(Indexer.idFieldName, idTerm.text(), Field.Store.NO, Field.Index.NOT_ANALYZED_NO_NORMS, Field.TermVector.NO))
      indexWriter.updateDocument(idTerm, seedDoc)
      indexWriter.commit()
    }
    val reader = IndexReader.open(indexDirectory)
    Searcher(reader)
  }

  def getSearcher = searcher

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
      indexables.grouped(commitBatchSize).foreach{ indexableBatch =>
        val commitBatch = indexableBatch.map{ indexable =>
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
                indexWriter.updateDocument(indexable.idTerm, doc)
                log.info("indexed id=%s".format(indexable.id))
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
        indexWriter.commit(Map(Indexer.CommitData.committedAt -> currentDateTime.toStandardTimeString))
        log.info("index commited")
        afterCommit(commitBatch)
      }
    }
    if (refresh) refreshSearcher()
  }

  def deleteAllDocuments(refresh: Boolean = true) {
    if (IndexReader.indexExists(indexDirectory)) {
      doWithIndexWriter{ indexWriter =>
        indexWriter.deleteAll()
        indexWriter.commit(Map(Indexer.CommitData.committedAt -> currentDateTime.toStandardTimeString))
      }
    }
    if (refresh) refreshSearcher()
  }

  def commitData: Map[String, String] = {
    // get the latest commit
    val indexReader = Option(IndexReader.openIfChanged(searcher.indexReader.inner)).getOrElse(searcher.indexReader.inner)
    var indexCommit = indexReader.getIndexCommit()
    var mutableMap = indexCommit.getUserData()
    log.info("commit data =" + mutableMap)
    Map() ++ mutableMap
  }

  def numDocs = (indexWriter.numDocs() - 1) // minus the seed doc

  def refreshSearcher() {
    searcher = Searcher.reopen(searcher)
  }

  def buildIndexable(data: T): Indexable[T]
  def buildIndexable(id: Id[T]): Indexable[T]

  def getFieldDecoder(fieldName: String) = {
    fieldDecoders.get(fieldName) match {
      case Some(decoder) => decoder
      case _ => fieldName match {
        case Indexer.idPayloadFieldName => DocUtil.IdPayloadFieldDecoder
        case _ => DocUtil.TextFieldDecoder
      }
    }
  }
}

