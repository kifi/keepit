package com.keepit.rover.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.cache._
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.AccessLog
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.Article
import com.keepit.rover.model.{ RoverArticleInfo, ArticleInfoRepo, ArticleInfo }
import com.keepit.rover.store.RoverArticleStore

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.Duration
import com.keepit.common.core._

@Singleton
class ArticleCommander @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    articleInfoCache: ArticleInfoUriCache,
    articleStore: RoverArticleStore,
    private implicit val executionContext: ExecutionContext) {

  def getArticleInfosBySequenceNumber(seq: SequenceNumber[ArticleInfo], limit: Int): Seq[ArticleInfo] = {
    db.readOnlyMaster { implicit session =>
      articleInfoRepo.getBySequenceNumber(seq, limit).map(RoverArticleInfo.toArticleInfo)
    }
  }

  def getArticlesByUris(uriIds: Set[Id[NormalizedURI]]): Map[Id[NormalizedURI], Future[Set[Article]]] = {
    getArticleInfosByUris(uriIds).mapValues(getBestArticles)
  }

  def getArticleInfosByUris(uriIds: Set[Id[NormalizedURI]]): Map[Id[NormalizedURI], Set[ArticleInfo]] = {
    val keys = uriIds.map(ArticleInfoUriKey.apply)
    db.readOnlyMaster { implicit session =>
      articleInfoCache.bulkGetOrElse(keys) { missingKeys =>
        articleInfoRepo.getByUris(missingKeys.map(_.uriId)).map {
          case (uriId, infos) =>
            ArticleInfoUriKey(uriId) -> infos.map(RoverArticleInfo.toArticleInfo)
        }
      }
    }.map { case (key, infos) => key.uriId -> infos }
  }

  private def getBestArticles(infos: Set[ArticleInfo]): Future[Set[Article]] = {
    val futureArticles: Set[Future[Option[Article]]] = infos.map { info =>
      (info.getBestKey orElse info.getLatestKey) match {
        case None => Future.successful(None)
        case Some(articleKey) => articleStore.get(articleKey)
      }
    }
    Future.sequence(futureArticles).imap(_.flatten)
  }
}

case class ArticleInfoUriKey(uriId: Id[NormalizedURI]) extends Key[Set[ArticleInfo]] {
  override val version = 1
  val namespace = "article_info_by_uri"
  def toKey(): String = uriId.id.toString
}

class ArticleInfoUriCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[ArticleInfoUriKey, Set[ArticleInfo]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
