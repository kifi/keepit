package com.keepit.search.index

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import org.apache.lucene.index.CorruptIndexException
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Payload
import org.apache.lucene.index.Term
import org.apache.lucene.store.Directory

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
}

class Indexer[T](indexDirectory: Directory, indexWriterConfig: IndexWriterConfig) extends Logging {

  lazy val indexWriter = new IndexWriter(indexDirectory, indexWriterConfig)

  var readerContext: Option[(IndexReader, Array[Long])] = None
  
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
   
  def indexDocuments(indexables: Iterator[Indexable[T]], commitBatchSize: Int)(afterCommit: Seq[Indexable[T]]=>Unit) {
    doWithIndexWriter{ indexWriter =>
      indexables.grouped(commitBatchSize).foreach{ commitBatch =>
        commitBatch.foreach{ indexable =>
          indexWriter.updateDocument(indexable.idTerm, indexable.buildDocument)
        }
        indexWriter.commit()
        afterCommit(commitBatch)
      }
    }
  }
  
  def numDocs = indexWriter.numDocs()
  
  def initReaderContext {
    if (IndexReader.indexExists(indexDirectory)) refreshReaderContext
  }
  
  def refreshReaderContext {
    val reader = readerContext match {
      case Some((reader, _)) => 
        IndexReader.openIfChanged(reader) // this may return null
      case None =>
        if (IndexReader.indexExists(indexDirectory)) IndexReader.open(indexDirectory)
        else null
    }
    if (reader != null) readerContext = Some((reader, buildIdMapper(reader))) // lucene may return a null reader.
  }
  
  def buildIdMapper(reader: IndexReader) = {
    val maxDoc = reader.maxDoc();
    val idArray = new Array[Long](maxDoc); 
    val payloadBuffer = new Array[Byte](8)
    val tp = reader.termPositions(Indexer.idPayloadTerm);
    try {
      var idx = 0;
      while (tp.next()) {
        val doc = tp.doc();
        while (idx < doc) {
          idArray(idx) = Indexer.DELETED_ID; // fill the gap
          idx += 1
        }
        tp.nextPosition();
        tp.getPayload(payloadBuffer, 0)
        val id = bytesToLong(payloadBuffer)
        idArray(idx) = id
        idx += 1
      }
      while(idx < maxDoc) {
        idArray(idx) = Indexer.DELETED_ID; // fill the gap
        idx += 1
      }
    } finally {
      tp.close();
    }
    idArray
  }
  
  private def bytesToLong(bytes: Array[Byte]): Long = {
    ((bytes(0) & 0xFF).toLong) |
    ((bytes(1) & 0xFF).toLong <<  8) |
    ((bytes(2) & 0xFF).toLong << 16) |
    ((bytes(3) & 0xFF).toLong << 24) |
    ((bytes(4) & 0xFF).toLong << 32) |
    ((bytes(5) & 0xFF).toLong << 40) | 
    ((bytes(6) & 0xFF).toLong << 48) |
    ((bytes(7) & 0xFF).toLong << 56)
  }
}
