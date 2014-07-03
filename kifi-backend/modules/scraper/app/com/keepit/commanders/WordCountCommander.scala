package com.keepit.commanders

import scala.concurrent.Future
import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.db.Id
import com.keepit.model.{NormalizedURI, NormalizedURIWordCountCache, NormalizedURIWordCountKey}
import com.keepit.scraper.ScraperServiceClient
import com.keepit.search.ArticleStore
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.commanders.TimeToReadCommander
import com.keepit.scraper.ScrapeProcessor

@ImplementedBy(classOf[WordCountCommanderImpl])
trait WordCountCommander {
  def getWordCount(id: Id[NormalizedURI], url: Option[String]): Future[Int]
  def getReadTimeMinutes(id: Id[NormalizedURI], url: Option[String]): Future[Option[Int]]
}

class WordCountCommanderImpl @Inject()(
  articleStore: ArticleStore,
  wordCountCache: NormalizedURIWordCountCache,
  scrapeProcessor: ScrapeProcessor
) extends WordCountCommander {

  implicit def toWordCountKey(id: Id[NormalizedURI]): NormalizedURIWordCountKey = NormalizedURIWordCountKey(id)

  private def wordCount(content: String): Int = content.split(" ").filter(!_.isEmpty()).size

  private def getFromCache(id: Id[NormalizedURI]): Option[Int] = {
    wordCountCache.get(id)
  }

  private def getFromArticleStore(id: Id[NormalizedURI]): Option[Int] = {
    articleStore.get(id).map{ article =>
      val wc = wordCount(article.content)
      wordCountCache.set(id, wc)
      wc
    }
  }

  private def getFromScraper(id: Id[NormalizedURI], url: String): Future[Int] = {
    scrapeProcessor.fetchBasicArticle(url, None, None).map{ articleOpt =>
      val wc = articleOpt match {
        case None => -1
        case Some(basicArticle) => wordCount(basicArticle.content)
      }
      wordCountCache.set(id, wc)
      wc
    }
  }

  def getWordCount(id: Id[NormalizedURI], url: Option[String]): Future[Int] = {
    val wcOpt = getFromCache(id) orElse getFromArticleStore(id)
    wcOpt match {
      case Some(wc) => Future.successful(wc)
      case None => if(url.isDefined) getFromScraper(id, url.get) else Future.successful(0)
    }
  }

  def getReadTimeMinutes(id: Id[NormalizedURI], url: Option[String]): Future[Option[Int]] = {
    getWordCount(id, url) map TimeToReadCommander.wordCountToReadTimeMinutes
  }
}
