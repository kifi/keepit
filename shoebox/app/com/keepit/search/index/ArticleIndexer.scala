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
import org.apache.lucene.queryParser.QueryParser
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.store.Directory
import org.apache.lucene.store.MMapDirectory
import org.apache.lucene.util.Version
import java.io.File
import java.io.IOException

object ArticleIndexer {
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
  
  def run(): Int = {
    log.info("starting a new indexing round")
    try {
      val uris = CX.withConnection { implicit c =>
        NormalizedURI.getByState(SCRAPED, commitBatchSize * 3)
      }
      var cnt = 0
      indexDocuments(uris.iterator.map{ uri => buildIndexable(uri) }, commitBatchSize){ commitBatch =>
        commitBatch.foreach{ indexable =>
          CX.withConnection { implicit c =>
            NormalizedURI.get(indexable.id).withState(NormalizedURI.States.INDEXED).save
          }
        }
        cnt += commitBatch.size
      }
      cnt
    } catch {
      case ex: Throwable => 
        log.error("error in indexing run", ex) // log and eat the exception
        throw ex
    }
  }
  
  private val parser = new ArticleQueryParser

  def parse(queryText: String): Query = {
    parser.parse(queryText)
  }
  
  def search(queryString: String): Seq[Hit] = searcher.search(parse(queryString))
  
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
  
  class ArticleQueryParser extends QueryParser(Version.LUCENE_36, "b", indexWriterConfig.getAnalyzer()) {
    override def getFieldQuery(field: String, queryText: String, quoted: Boolean) = {
      val booleanQuery = new BooleanQuery
      booleanQuery.add(super.getFieldQuery("t", queryText, quoted), Occur.SHOULD)
      booleanQuery.add(super.getFieldQuery("c", queryText, quoted), Occur.SHOULD)
      booleanQuery
    }
  }
}
