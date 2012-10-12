package com.keepit.search.index

import com.keepit.common.logging.Logging
import org.apache.lucene.index.CorruptIndexException
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.Directory
import java.io.File
import java.io.IOException
import scala.collection.JavaConversions._

class Indexer[T](indexDirectory: Directory, indexWriterConfig: IndexWriterConfig) extends Logging {

  lazy val indexWriter = new IndexWriter(indexDirectory, indexWriterConfig)

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
    println("numdocs=" + numDocs)
  }
  
  def numDocs = indexWriter.numDocs()
}
