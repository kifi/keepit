package com.keepit.search.index

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.Healthcheck.INTERNAL
import com.keepit.common.healthcheck.{HealthcheckError, HealthcheckPlugin}
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.inject._
import com.keepit.model._
import com.keepit.search.ArticleStore
import com.keepit.search.Lang
import java.io.StringReader
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.Directory
import org.apache.lucene.util.Version
import play.api.Play.current

object ArticleIndexer {

  def apply(indexDirectory: Directory, articleStore: ArticleStore): ArticleIndexer = {
    val analyzer = DefaultAnalyzer.forIndexing
    val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)

    new ArticleIndexer(indexDirectory, config, articleStore)
  }
}

class ArticleIndexer(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig, articleStore: ArticleStore)
  extends Indexer[NormalizedURI](indexDirectory, indexWriterConfig) {

  val commitBatchSize = 100

  def run(): Int = run(commitBatchSize, commitBatchSize * 3)

  def run(commitBatchSize: Int, fetchSize: Int): Int = {
    log.info("starting a new indexing round")
    val db = inject[Database]
    val repo = inject[NormalizedURIRepo]
    try {
      val uris = db.readOnly { implicit s =>
        repo.getIndexable(sequenceNumber, fetchSize)
      }
      var cnt = 0
      indexDocuments(uris.iterator.map(buildIndexable), commitBatchSize){ commitBatch =>
        val (errors, successes) = commitBatch.partition(_._2.isDefined)
        errors.map(_._2.get).foreach { error =>
          inject[HealthcheckPlugin].addError(HealthcheckError(errorMessage = Some(error.msg), callType = INTERNAL))
        }
        cnt += successes.size
      }
      cnt
    } catch {
      case ex: Throwable =>
        log.error("error in indexing run", ex)
        throw ex
    }
  }

  def buildIndexable(id: Id[NormalizedURI]): ArticleIndexable = {
    val uri = inject[Database].readOnly { implicit c => inject[NormalizedURIRepo].get(id) }
    buildIndexable(uri)
  }

  def buildIndexable(uri: NormalizedURI): ArticleIndexable = {
    new ArticleIndexable(uri.id.get, uri.seq, uri, articleStore)
  }

  class ArticleIndexable(
    val id: Id[NormalizedURI],
    val sequenceNumber: SequenceNumber,
    val uri: NormalizedURI,
    articleStore: ArticleStore
  ) extends Indexable[NormalizedURI] {
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
          URI.parse(uri.url).toOption.flatMap(_.host) match {
            case Some(Host(domain @ _*)) =>
              doc.add(buildIteratorField("site", (1 to domain.size).iterator){ n => domain.take(n).reverse.mkString(".") })
              doc.add(buildIteratorField("site_keywords", (0 until domain.size).iterator)(domain))
            case _ =>
          }

          doc
        case None => doc
      }
    }
  }
}
