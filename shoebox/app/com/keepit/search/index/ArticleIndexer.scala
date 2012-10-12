package com.keepit.search.index

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.search.Article
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURI.States._
import com.keepit.common.db.CX
import play.api.Play.current
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.Directory
import org.apache.lucene.store.MMapDirectory
import org.apache.lucene.util.Version
import scala.collection.mutable.{Map => MutableMap}
import java.io.File
import java.io.IOException

object ArticleIndexer {
  def apply(directoryPath: String, articleStore: MutableMap[Id[NormalizedURI], Article]): ArticleIndexer = {
    val dir = new File(directoryPath).getCanonicalFile()
    val indexDirectory: Directory = new MMapDirectory(dir)
    
    apply(indexDirectory, articleStore)
  }
  
  def apply(indexDirectory: Directory, articleStore: MutableMap[Id[NormalizedURI], Article]): ArticleIndexer = {
    val analyzer = new StandardAnalyzer(Version.LUCENE_36)
    analyzer.setMaxTokenLength(256)
    val config = new IndexWriterConfig(Version.LUCENE_36, analyzer)

    new ArticleIndexer(indexDirectory, config, articleStore)
  }
}

class ArticleIndexer(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig, articleStore: MutableMap[Id[NormalizedURI], Article])
  extends Indexer[NormalizedURI](indexDirectory, indexWriterConfig) {

  val commitBatchSize = 100
  
  def run = {
    try {
      val uris = CX.withConnection { implicit c =>
        NormalizedURI.getByState(SCRAPED)
      }
      indexDocuments(uris.iterator.map{ buildIndexable(_) }, commitBatchSize){ commitBatch =>
        commitBatch.foreach{ indexable =>
          CX.withConnection { implicit c =>
            indexable.asInstanceOf[ArticleIndexable].uri.withState(NormalizedURI.States.INDEXED).save
          }          
        }
      }
    } catch {
      case ex: Throwable => log.error("error in indexing run", ex) // log and eat the exception
    }
  }
  
  def buildIndexable(uri: NormalizedURI) = new ArticleIndexable(uri, articleStore)
  
  class ArticleIndexable(val uri: NormalizedURI, arcicleStore: MutableMap[Id[NormalizedURI], Article]) extends Indexable[NormalizedURI] {
    override val uid = uri.id.get
    override val idPayloadFieldName = "URI_ID"
    
    override def buildDocument = {
      val doc = super.buildDocument
      articleStore.get(uri.id.get) match {
        case Some(article) =>
          val title = buildTextField("t", article.title)
          val content = buildTextField("c", article.content)
          doc.add(title)
          doc.add(content)
          doc
        case None => doc
      }
    }
  } 
}
