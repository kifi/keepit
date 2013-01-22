package com.keepit.search.index

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.keepit.search.Lang
import com.keepit.search.SearchConfig
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.common.db.CX
import play.api.Play.current
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.Directory
import org.apache.lucene.store.MMapDirectory
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.util.Version
import java.io.File
import java.io.IOException
import java.io.StringReader
import scala.math._

object ArticleIndexer {

  def apply(indexDirectory: Directory, articleStore: ArticleStore): ArticleIndexer = {
    val analyzer = DefaultAnalyzer.forIndexing
    val config = new IndexWriterConfig(Version.LUCENE_36, analyzer)

    new ArticleIndexer(indexDirectory, config, articleStore)
  }
}

class ArticleIndexer(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig, articleStore: ArticleStore)
  extends Indexer[NormalizedURI](indexDirectory, indexWriterConfig) {

  val commitBatchSize = 100

  def run(): Int = run(commitBatchSize, commitBatchSize * 3)

  def run(commitBatchSize: Int, fetchSize: Int): Int = {
    log.info("starting a new indexing round")
    try {
      val uris = CX.withConnection { implicit c =>
        val uris = NormalizedURICxRepo.getByState(SCRAPE_FAILED, fetchSize)
        if (uris.size < fetchSize) {
          val combo = uris ++ NormalizedURICxRepo.getByState(SCRAPED, fetchSize - uris.size)
          if (uris.size < fetchSize) combo ++ NormalizedURICxRepo.getByState(UNSCRAPABLE, fetchSize - uris.size)
          else combo
        }
        else uris
      }
      var cnt = 0
      indexDocuments(uris.iterator.map{ uri => buildIndexable(uri) }, commitBatchSize){ commitBatch =>
        commitBatch.foreach{ case (indexable, indexError)  =>
          CX.withConnection { implicit c =>
            val articleIndexable = indexable.asInstanceOf[ArticleIndexable]
            val state = indexError match {
              case Some(error) =>
                findNextState(articleIndexable.uri.state -> Set(INDEX_FAILED, FALLBACK_FAILED, UNSCRAPE_FALLBACK_FAILED))
              case None =>
                cnt += 1
                findNextState(articleIndexable.uri.state -> Set(INDEXED, FALLBACKED, UNSCRAPE_FALLBACK))
            }
            NormalizedURICxRepo.get(indexable.id).withState(state).save
          }
        }
      }
      cnt
    } catch {
      case ex: Throwable =>
        log.error("error in indexing run", ex)
        throw ex
    }
  }

  def buildIndexable(id: Id[NormalizedURI]) = {
    val uri = CX.withConnection{ implicit c => NormalizedURICxRepo.get(id) }
    buildIndexable(uri)
  }

  def buildIndexable(uri: NormalizedURI) = {
    new ArticleIndexable(uri.id.get, uri, articleStore)
  }

  class ArticleIndexable(override val id: Id[NormalizedURI], val uri: NormalizedURI, articleStore: ArticleStore) extends Indexable[NormalizedURI] {
    implicit def toReader(text: String) = new StringReader(text)

    override def buildDocument = {
      val doc = super.buildDocument
      articleStore.get(uri.id.get) match {
        case Some(article) =>
          val titleLang = article.titleLang.getOrElse(Lang("en"))
          val contentLang = article.contentLang.getOrElse(Lang("en"))
          doc.add(buildKeywordField("cl", contentLang.lang))
          doc.add(buildKeywordField("tl", titleLang.lang))

          val titleAnalyzer = DefaultAnalyzer.forIndexing(titleLang)
          val contentAnalyzer = DefaultAnalyzer.forIndexing(contentLang)
          val title = buildTextField("t", article.title, titleAnalyzer)
          val content = buildTextField("c", article.content, contentAnalyzer)
          doc.add(title)
          doc.add(content)

          DefaultAnalyzer.forIndexingWithStemmer(titleLang).foreach{ analyzer =>
            doc.add(buildTextField("ts", article.title, analyzer))
          }
          DefaultAnalyzer.forIndexingWithStemmer(contentLang).foreach{ analyzer =>
            doc.add(buildTextField("cs", article.content, analyzer))
          }
          doc.add(buildSemanticVectorField("sv", titleAnalyzer.tokenStream("t", article.title), contentAnalyzer.tokenStream("c", article.content)))

          // index domain name
          URI.parse(uri.url) match {
            case Some(uri) =>
              uri.host match {
                case Some(Host(domain @ _*)) =>
                  doc.add(buildIteratorField("site", (1 to domain.size).iterator){ n => domain.take(n).reverse.mkString(".") })
                case _ =>
              }
            case _ =>
          }

          doc
        case None => doc
      }
    }
  }
}
