package com.keepit.search.index

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.Healthcheck.INTERNAL
import com.keepit.common.healthcheck.{HealthcheckError, HealthcheckPlugin}
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.search.ArticleStore
import com.keepit.search.Lang
import java.io.StringReader
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.Directory
import org.apache.lucene.util.Version
import com.keepit.search.SemanticVectorBuilder
import com.google.inject.Inject

object ArticleIndexer {
  private[this] val toBeDeletedStates = Set[State[NormalizedURI]](ACTIVE, INACTIVE, SCRAPE_WANTED, UNSCRAPABLE)
  def shouldDelete(uri: NormalizedURI): Boolean = toBeDeletedStates.contains(uri.state)
}

class ArticleIndexer (
    indexDirectory: Directory,
    indexWriterConfig: IndexWriterConfig,
    articleStore: ArticleStore,
    db: Database,
    repo: NormalizedURIRepo,
    healthcheckPlugin: HealthcheckPlugin)
  extends Indexer[NormalizedURI](indexDirectory, indexWriterConfig) {

  val commitBatchSize = 100
  val fetchSize = 20000

  def run(): Int = run(commitBatchSize, fetchSize)

  def run(commitBatchSize: Int, fetchSize: Int): Int = {
    log.info("starting a new indexing round")
    try {
      val uris = db.readOnly { implicit s =>
        repo.getIndexable(sequenceNumber, fetchSize)
      }
      var cnt = 0
      indexDocuments(uris.iterator.map(buildIndexable), commitBatchSize){ commitBatch =>
        val (errors, successes) = commitBatch.partition(_._2.isDefined)
        errors.map(_._2.get).foreach { error =>
          healthcheckPlugin.addError(HealthcheckError(errorMessage = Some(error.msg), callType = INTERNAL))
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
    val uri = db.readOnly { implicit c => repo.get(id) }
    buildIndexable(uri)
  }

  def buildIndexable(uri: NormalizedURI): ArticleIndexable = {
    new ArticleIndexable(id = uri.id.get,
                         sequenceNumber = uri.seq,
                         isDeleted = ArticleIndexer.shouldDelete(uri),
                         uri = uri,
                         articleStore = articleStore)
  }

  class ArticleIndexable(
    override val id: Id[NormalizedURI],
    override val sequenceNumber: SequenceNumber,
    override val isDeleted: Boolean,
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
          val titleAnalyzerWithStemmer = DefaultAnalyzer.forIndexingWithStemmer(titleLang)
          val contentAnalyzer = DefaultAnalyzer.forIndexing(contentLang)
          val contentAnalyzerWithStemmer = DefaultAnalyzer.forIndexingWithStemmer(contentLang)

          val descriptionAndContent = article.description match {
            case Some(desc) => desc + "\n\n" + article.content
            case None => article.content
          }

          doc.add(buildTextField("t", article.title, titleAnalyzer))
          doc.add(buildTextField("ts", article.title, titleAnalyzerWithStemmer))

          doc.add(buildTextField("c", descriptionAndContent, contentAnalyzer))
          doc.add(buildTextField("cs", descriptionAndContent, contentAnalyzerWithStemmer))

          val builder = new SemanticVectorBuilder(60)
          builder.load(titleAnalyzerWithStemmer.tokenStream("t", article.title))
          builder.load(contentAnalyzerWithStemmer.tokenStream("c", descriptionAndContent))
          doc.add(buildDocSemanticVectorField("docSv", builder))
          doc.add(buildSemanticVectorField("sv", builder))

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
