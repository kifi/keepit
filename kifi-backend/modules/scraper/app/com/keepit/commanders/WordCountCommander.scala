package com.keepit.commanders

import scala.concurrent.Future
import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.db.Id
import com.keepit.model.{ NormalizedURI, NormalizedURIWordCountCache, NormalizedURIWordCountKey }
import com.keepit.scraper.ScraperServiceClient
import com.keepit.search.ArticleStore
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.scraper.ScrapeProcessor
import com.keepit.common.logging.Logging
import com.keepit.model.URISummaryCache
import com.keepit.model.URISummaryKey

@ImplementedBy(classOf[WordCountCommanderImpl])
trait WordCountCommander {
  def getWordCount(id: Id[NormalizedURI], url: String): Future[Int]
  def getReadTimeMinutes(id: Id[NormalizedURI], url: String): Future[Option[Int]]
}

class WordCountCommanderImpl @Inject() (
    articleStore: ArticleStore,
    wordCountCache: NormalizedURIWordCountCache,
    uriSummaryCache: URISummaryCache,
    scrapeProcessor: ScrapeProcessor) extends WordCountCommander with Logging {

  implicit def toWordCountKey(id: Id[NormalizedURI]): NormalizedURIWordCountKey = NormalizedURIWordCountKey(id)
  implicit def toURISummaryKey(id: Id[NormalizedURI]): URISummaryKey = URISummaryKey(id)

  private def wordCount(content: String): Int = content.split(" ").count(!_.isEmpty())

  private def updateCache(id: Id[NormalizedURI], wc: Int): Unit = {
    wordCountCache.set(id, wc)
    uriSummaryCache.get(id) match {
      case Some(summary) => uriSummaryCache.set(id, summary.copy(wordCount = Some(wc)))
      case None =>
    }
  }

  private def getFromCache(id: Id[NormalizedURI]): Option[Int] = {
    wordCountCache.get(id)
  }

  private def getFromArticleStore(id: Id[NormalizedURI]): Option[Int] = {
    articleStore.syncGet(id).map { article =>
      val wc = wordCount(article.content)
      updateCache(id, wc)
      log.info(s"get from article store. set word count cache for ${id.id}: $wc")
      wc
    }
  }

  private def getFromScraper(id: Id[NormalizedURI], url: String): Future[Int] = {
    scrapeProcessor.fetchBasicArticle(url, None, None).map { articleOpt =>
      val wc = articleOpt match {
        case None => -1
        case Some(basicArticle) => wordCount(basicArticle.content)
      }
      log.info(s"called scraper on the fly. set word count cache for ${id.id}: $wc")
      updateCache(id, wc)
      wc
    }
  }

  def getWordCount(id: Id[NormalizedURI], url: String): Future[Int] = {
    val wcOpt = getFromCache(id) orElse getFromArticleStore(id)
    wcOpt match {
      case Some(wc) => Future.successful(wc)
      case None => getFromScraper(id, url)
    }
  }

  def getReadTimeMinutes(id: Id[NormalizedURI], url: String): Future[Option[Int]] = {
    getWordCount(id, url) map TimeToReadCommander.wordCountToReadTimeMinutes
  }
}
