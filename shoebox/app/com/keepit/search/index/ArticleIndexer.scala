package com.keepit.search.index

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.search.Article
import com.keepit.search.ArticleStore
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
import java.io.File
import java.io.IOException

object ArticleIndexer {
  def apply(directoryPath: String, articleStore: ArticleStore): ArticleIndexer = {
    val dir = new File(directoryPath).getCanonicalFile()
    val indexDirectory: Directory = new MMapDirectory(dir)
    
    apply(indexDirectory, articleStore)
  }
  
  def apply(indexDirectory: Directory, articleStore: ArticleStore): ArticleIndexer = {
    val analyzer = new StandardAnalyzer(Version.LUCENE_36)
    analyzer.setMaxTokenLength(256)
    val config = new IndexWriterConfig(Version.LUCENE_36, analyzer)

    new ArticleIndexer(indexDirectory, config, articleStore)
  }
}

class ArticleIndexer(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig, articleStore: ArticleStore)
  extends Indexer[NormalizedURI](indexDirectory, indexWriterConfig) {

  val commitBatchSize = 100
  
  def run = {
    try {
      val uris = CX.withConnection { implicit c =>
        NormalizedURI.getByState(SCRAPED)
      }
      indexDocuments(uris.iterator.map{ uri => buildIndexable(uri) }, commitBatchSize){ commitBatch =>
        commitBatch.foreach{ indexable =>
          CX.withConnection { implicit c =>
            NormalizedURI.get(indexable.id).withState(NormalizedURI.States.INDEXED).save
          }
        }
      }
    } catch {
      case ex: Throwable => log.error("error in indexing run", ex) // log and eat the exception
    }
  }
  
  def buildIndexable(uri: NormalizedURI) = {
    new ArticleIndexable(uri.id.get, uri, articleStore)
  }
  
  class ArticleIndexable(override val id: Id[NormalizedURI], uri: NormalizedURI, articleStore: ArticleStore) extends Indexable[NormalizedURI] {
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
