package com.keepit.eliza.commanders

import scala.concurrent.Future

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.db.Id
import com.keepit.model.{NormalizedURI, NormalizedURIWordCountCache, NormalizedURIWordCountKey}
import com.keepit.scraper.ScraperServiceClient
import com.keepit.search.ArticleStore

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.logging.Logging

@ImplementedBy(classOf[WordCountCommanderImpl])
trait WordCountCommander {
  def getWordCount(id: Id[NormalizedURI], url: String): Future[Int]
  def getReadTimeMinutes(id: Id[NormalizedURI], url: String): Future[Int]
}

class WordCountCommanderImpl @Inject()(
  //articleStore: ArticleStore,
  scraperClient: ScraperServiceClient,
  wordCountCache: NormalizedURIWordCountCache
) extends WordCountCommander with Logging {

  private val WORDS_PER_MINUTE = 250
  private val READ_TIMES = Seq(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 30, 60)

  implicit def toWordCountKey(id: Id[NormalizedURI]): NormalizedURIWordCountKey = NormalizedURIWordCountKey(id)

  private def wordCount(content: String): Int = content.split(" ").filter(!_.isEmpty()).size

  private def getFromCache(id: Id[NormalizedURI]): Option[Int] = {
    wordCountCache.get(id)
  }

  /*private def getFromArticleStore(id: Id[NormalizedURI]): Option[Int] = {
    articleStore.get(id).map{ article =>
      val wc = wordCount(article.content)
      wordCountCache.set(id, wc)
      wc
    }
  }*/

  private def getFromScraper(id: Id[NormalizedURI], url: String): Future[Int] = {
    scraperClient.getBasicArticle(url, proxy = None, extractor = None).map{ articleOpt =>
      val wc = articleOpt match {
        case None => -1
        case Some(basicArticle) => wordCount(basicArticle.content)
      }
      wordCountCache.set(id, wc)
      wc
    }
  }

  def getWordCount(id: Id[NormalizedURI], url: String): Future[Int] = {
    val wcOpt = getFromCache(id) //orElse getFromArticleStore(id)
    val fut = wcOpt match {
      case Some(wc) => Future.successful(wc)
      case None => getFromScraper(id, url)
    }
    fut map { wc =>
      log.info(s"Word count for article $id is $wc (url: $url)")
      wc
    }
  }

  def getReadTimeMinutes(id: Id[NormalizedURI], url: String): Future[Int] = {
    getWordCount(id, url) map { estimate =>
      val minutes = estimate / WORDS_PER_MINUTE
      (READ_TIMES map { t => (t, Math.abs(t - minutes)) } min)._1
    }
  }
}
