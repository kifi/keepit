package com.keepit.search.index

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
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

  protected var searcher: Option[Searcher] = None
  initSearcher
  
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
          indexWriter.updateDocument(indexable.idTerm, indexable.buildDocument)
          log.info("indexed id=%s".format(indexable.id))
        }
        indexWriter.commit(Map(Indexer.CommitData.committedAt -> currentDateTime.toStandardTimeString))
        log.info("index commited")
        afterCommit(commitBatch)
      }
    }
    refreshSearcher
  }
  
  def commitData: Map[String, String] = {
    searcher match {
      case Some(searcher) =>
        // get the latest commit
        val indexReader = Option(IndexReader.openIfChanged(searcher.indexReader)).getOrElse(searcher.indexReader)
        var indexCommit = indexReader.getIndexCommit()
        var mutableMap = indexCommit.getUserData()
        log.info("commit data =" + mutableMap)
        Map() ++ mutableMap
      case None =>
        log.info("searcher is not instantiated")
        Map.empty
    }
  }
  
  def parse(queryString: String): Query
  
  def numDocs = indexWriter.numDocs()
  
  def initSearcher {
    if (IndexReader.indexExists(indexDirectory)) refreshSearcher
  }
  
  def refreshSearcher {
    val reader = searcher match {
      case Some(searcher) => 
        IndexReader.openIfChanged(searcher.indexReader) // this may return null
      case None =>
        if (IndexReader.indexExists(indexDirectory)) IndexReader.open(indexDirectory)
        else null
    }
    if (reader != null) searcher = Some(new Searcher(reader, ArrayIdMapper(reader))) // lucene may return a null reader.
  }
}
