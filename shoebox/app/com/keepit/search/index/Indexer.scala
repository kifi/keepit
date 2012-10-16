package com.keepit.search.index

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
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
import scala.collection.JavaConversions._

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

abstract class Indexer[T](indexDirectory: Directory, indexWriterConfig: IndexWriterConfig) extends Logging {

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
    new Searcher(reader, ArrayIdMapper(reader))
  }
  
  def doWithIndexWriter(f: IndexWriter=>Unit) = {
    try {
      f(indexWriter)
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
   
  def indexDocuments(indexables: Iterator[Indexable[T]], commitBatchSize: Int)(afterCommit: Seq[Indexable[T]]=>Unit): Unit = {
    doWithIndexWriter{ indexWriter =>
      indexables.grouped(commitBatchSize).foreach{ commitBatch =>
        commitBatch.foreach{ indexable =>
          val document = try {
            Some(indexable.buildDocument)
          } catch {
            case e => 
              log.error("failed to build document for uri %s".format(indexable.id), e)
              None
          } 
          document map { doc => 
            indexWriter.updateDocument(indexable.idTerm, doc)
            log.info("indexed id=%s".format(indexable.id))
          }
        }
        indexWriter.commit(Map(Indexer.CommitData.committedAt -> currentDateTime.toStandardTimeString))
        log.info("index commited")
        afterCommit(commitBatch)
      }
    }
    refreshSearcher()
  }
  
  def commitData: Map[String, String] = {
    // get the latest commit
    val indexReader = Option(IndexReader.openIfChanged(searcher.indexReader)).getOrElse(searcher.indexReader)
    var indexCommit = indexReader.getIndexCommit()
    var mutableMap = indexCommit.getUserData()
    log.info("commit data =" + mutableMap)
    Map() ++ mutableMap
  }
  
  def parse(queryText: String): Query
  
  def numDocs = (indexWriter.numDocs() - 1) // minus the seed doc
  
  def refreshSearcher() {
    val reader = IndexReader.openIfChanged(searcher.indexReader) // this may return null
    if (reader != null) searcher = new Searcher(reader, ArrayIdMapper(reader))
  }
}
