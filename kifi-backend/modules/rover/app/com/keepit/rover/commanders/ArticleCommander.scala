package com.keepit.rover.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.cache._
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.AccessLog
import com.keepit.model.{ NormalizedURI }
import com.keepit.rover.article.{ ArticleKind, Article }
import com.keepit.rover.model._
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

  def getBestArticleFuturesByUris(uriIds: Set[Id[NormalizedURI]]): Map[Id[NormalizedURI], Future[Set[Article]]] = {
    getArticleInfosByUris(uriIds).mapValues { infos =>
      Future.sequence(infos.map(getBestArticle(_))).imap(_.flatten)
    }
  }

  def getBestArticlesByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[Article]]] = {
    val futureUriIdWithArticles = getBestArticleFuturesByUris(uriIds).map {
      case (uriId, futureArticles) =>
        futureArticles.imap(uriId -> _)
    }
    Future.sequence(futureUriIdWithArticles).imap(_.toMap)
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

  def getBestArticle(info: ArticleInfoHolder): Future[Option[info.A]] = {
    (info.getBestKey orElse info.getLatestKey).map(articleStore.get) getOrElse Future.successful(None)
  }

  def getBestArticle[A <: Article](uriId: Id[NormalizedURI])(implicit kind: ArticleKind[A]): Future[Option[A]] = {
    getArticleInfoByUriAndKind[A](uriId).map(getBestArticle(_).imap(_.map(_.asExpected[A]))) getOrElse Future.successful(None)
  }

  def getArticleInfoByUriAndKind[A <: Article](uriId: Id[NormalizedURI])(implicit kind: ArticleKind[A]): Option[RoverArticleInfo] = {
    db.readWrite { implicit session =>
      articleInfoRepo.getByUriAndKind(uriId, kind)
    }
  }

  def internArticleInfoByUri(uriId: Id[NormalizedURI], url: String, kinds: Set[ArticleKind[_ <: Article]]): Map[ArticleKind[_ <: Article], RoverArticleInfo] = {
    db.readWrite { implicit session =>
      articleInfoRepo.internByUri(uriId, url, kinds)
    }
  }

  def markAsFetching(ids: Id[RoverArticleInfo]*): Unit = {
    db.readWrite { implicit session =>
      articleInfoRepo.markAsFetching(ids: _*)
    }
  }

}

case class ArticleInfoUriKey(uriId: Id[NormalizedURI]) extends Key[Set[ArticleInfo]] {
  override val version = 1
  val namespace = "article_info_by_uri"
  def toKey(): String = uriId.id.toString
}

class ArticleInfoUriCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[ArticleInfoUriKey, Set[ArticleInfo]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
