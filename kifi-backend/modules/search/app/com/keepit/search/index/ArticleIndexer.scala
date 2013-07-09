package com.keepit.search.index

import com.keepit.common.db._
import com.keepit.common.healthcheck.Healthcheck.INTERNAL
import com.keepit.common.healthcheck.{HealthcheckError, HealthcheckPlugin}
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.keepit.search.Lang
import com.keepit.shoebox.ShoeboxServiceClient
import java.io.StringReader
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.Directory
import org.apache.lucene.util.Version
import com.keepit.search.SemanticVectorBuilder
import com.google.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._
import ArticleRecordSerializer._

object ArticleIndexer {
  private[this] val toBeDeletedStates = Set[State[NormalizedURI]](ACTIVE, INACTIVE, SCRAPE_WANTED, UNSCRAPABLE)
  def shouldDelete(uri: NormalizedURI): Boolean = toBeDeletedStates.contains(uri.state)
}

class ArticleIndexer @Inject() (
    indexDirectory: Directory,
    indexWriterConfig: IndexWriterConfig,
    articleStore: ArticleStore,
    healthcheckPlugin: HealthcheckPlugin,
    shoeboxClient: ShoeboxServiceClient)
  extends Indexer[NormalizedURI](indexDirectory, indexWriterConfig) {

  val commitBatchSize = 100
  val fetchSize = 20000

  override def onFailure(indexable: Indexable[NormalizedURI], e: Throwable) {
    healthcheckPlugin.addError(HealthcheckError(errorMessage = Some(e.toString), callType = INTERNAL))
    super.onFailure(indexable, e)
  }

  def run(): Int = run(commitBatchSize, fetchSize)

  def run(commitBatchSize: Int, fetchSize: Int): Int = {
    resetSequenceNumberIfReindex()

    log.info("starting a new indexing round")
    try {
      val uris = Await.result(shoeboxClient.getIndexable(sequenceNumber.value, fetchSize), 180 seconds)
      var cnt = successCount
      indexDocuments(uris.iterator.map(buildIndexable), commitBatchSize)
      successCount - cnt
    } catch {
      case ex: Throwable =>
        log.error("error in indexing run", ex)
        throw ex
    }
  }

  def buildIndexable(uriId: Id[NormalizedURI]): ArticleIndexable = {
    val uri = Await.result(shoeboxClient.getNormalizedURI(uriId), 30 seconds)
    buildIndexable(uri)
  }

  def buildIndexable(uri: NormalizedURI): ArticleIndexable = {
    new ArticleIndexable(
      id = uri.id.get,
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

    private def enrichedContent(article: Article): String = {
      val c = article.description match {
        case Some(desc) => desc + "\n\n" + article.content
        case None => article.content
      }
      article.media match {
        case Some(media) => c + "\n\n" + media
        case None => c
      }
    }

    private def getArticle(id: Id[NormalizedURI], maxRetry: Int, minSleepTime: Long): Option[Article] = {
      var sleepTime = minSleepTime
      var retry = maxRetry
      while (retry > 0) {
        try {
          return articleStore.get(id)
        } catch {
          case e: Throwable =>
        }
        log.info("failed to get article from ArticleStore. retry in {$sleepTime}ms")
        Thread.sleep(sleepTime)
        sleepTime *= 2 // exponential back off
        retry -= 0
      }
      articleStore.get(id)
    }

    override def buildDocument = {
      val doc = super.buildDocument
      getArticle(id = uri.id.get, maxRetry = 5, minSleepTime = 1000) match {
        case Some(article) =>
          val titleLang = article.titleLang.getOrElse(Lang("en"))
          val contentLang = article.contentLang.getOrElse(Lang("en"))
          doc.add(buildKeywordField("cl", contentLang.lang))
          doc.add(buildKeywordField("tl", titleLang.lang))

          val titleAnalyzer = DefaultAnalyzer.forIndexing(titleLang)
          val titleAnalyzerWithStemmer = DefaultAnalyzer.forIndexingWithStemmer(titleLang)
          val contentAnalyzer = DefaultAnalyzer.forIndexing(contentLang)
          val contentAnalyzerWithStemmer = DefaultAnalyzer.forIndexingWithStemmer(contentLang)

          val content = article.description match {
            case Some(desc) => desc + "\n\n" + article.content
            case None => article.content
          }

          doc.add(buildTextField("t", article.title, titleAnalyzer))
          doc.add(buildTextField("ts", article.title, titleAnalyzerWithStemmer))

          doc.add(buildTextField("c", content, contentAnalyzer))
          doc.add(buildTextField("cs", content, contentAnalyzerWithStemmer))

          val builder = new SemanticVectorBuilder(60)
          builder.load(titleAnalyzerWithStemmer.tokenStream("t", article.title))
          builder.load(contentAnalyzerWithStemmer.tokenStream("c", content))
          doc.add(buildDocSemanticVectorField("docSv", builder))
          doc.add(buildSemanticVectorField("sv", builder))

          // index domain name
          URI.parse(uri.url).toOption.flatMap(_.host) match {
            case Some(Host(domain @ _*)) =>
              doc.add(buildIteratorField("site", (1 to domain.size).iterator){ n => domain.take(n).reverse.mkString(".") })
              doc.add(buildIteratorField("site_keywords", (0 until domain.size).iterator)(domain))
            case _ =>
          }

          // media keyword field
          article.media.foreach{ media =>
            doc.add(buildTextField("media", media, DefaultAnalyzer.defaultAnalyzer))
          }

          // store title and url in the index
          val r = ArticleRecord(article.title, uri.url, article.id)
          doc.add(buildBinaryDocValuesField("rec", r))

          doc
        case None => doc
      }
    }
  }
}
