package com.keepit.rover.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.cache._
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.{ Logging, AccessLog }
import com.keepit.model.{ NormalizedURI }
import com.keepit.rover.article.{ ArticleFetcherProvider, ArticleKind, Article }
import com.keepit.rover.manager.{ FetchTaskQueue, FetchTask }
import com.keepit.rover.model._
import com.keepit.rover.store.RoverArticleStore

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.Duration
import com.keepit.common.core._

import scala.util.{ Failure, Try }

@Singleton
class ArticleCommander @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    articleInfoCache: ArticleInfoUriCache,
    articleStore: RoverArticleStore,
    topPriorityQueue: FetchTaskQueue.TopPriority,
    articleFetcher: ArticleFetcherProvider,
    private implicit val executionContext: ExecutionContext) extends Logging {

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

  def add(tasks: Seq[FetchTask], queue: FetchTaskQueue): Future[Map[FetchTask, Try[Unit]]] = {
    db.readWrite { implicit session =>
      articleInfoRepo.markAsFetching(tasks.map(_.id): _*)
    }
    queue.add(tasks).map { maybeQueuedTasks =>
      val failedTasks = maybeQueuedTasks.collect { case (task, Failure(_)) => task }.toSeq
      if (failedTasks.nonEmpty) {
        db.readWrite { implicit session =>
          articleInfoRepo.unmarkAsFetching(failedTasks.map(_.id): _*)
        }
      }
      maybeQueuedTasks
    }
  }

  def getRipeForFetching(limit: Int, queuedForMoreThan: Duration) = {
    db.readOnlyMaster { implicit session =>
      articleInfoRepo.getRipeForFetching(limit, queuedForMoreThan)
    }
  }

  def fetchWithTopPriority(ids: Set[Id[RoverArticleInfo]]): Future[Map[Id[RoverArticleInfo], Try[Unit]]] = {
    val tasks = ids.map(FetchTask(_)).toSeq
    add(tasks, topPriorityQueue).imap { _.map { case (task, result) => (task.id -> result) } }
  }

  def fetchAndPersist(articleInfo: RoverArticleInfo): Future[Option[articleInfo.A]] = {
    articleFetcher.fetch(articleInfo.getFetchRequest).flatMap {
      case None => {
        log.info(s"No ${articleInfo.articleKind} fetched for uri ${articleInfo.uriId}: ${articleInfo.url}")
        Future.successful(None)
      }
      case Some(article) => {
        log.info(s"Persisting latest ${articleInfo.articleKind} for uri ${articleInfo.uriId}: ${articleInfo.url}")
        articleStore.add(articleInfo.uriId, articleInfo.latestVersion, article)(articleInfo.articleKind).imap { key =>
          log.info(s"Persisted latest ${articleInfo.articleKind} with version ${key.version} for uri ${articleInfo.uriId}: ${articleInfo.url}")
          Some((article, key.version))
        }
      }
    } andThen {
      case fetched =>
        fetched recover {
          case error: Exception =>
            log.error(s"Failed to fetch ${articleInfo.articleKind} for uri ${articleInfo.uriId}: ${articleInfo.url}", error)
        }
        db.readWrite { implicit session =>
          articleInfoRepo.updateAfterFetch(articleInfo.uriId, articleInfo.articleKind, fetched.map(_.map { case (_, version) => version }))
        }
    }
  } imap (_.map { case (article, _) => article })

}

case class ArticleInfoUriKey(uriId: Id[NormalizedURI]) extends Key[Set[ArticleInfo]] {
  override val version = 1
  val namespace = "article_info_by_uri"
  def toKey(): String = uriId.id.toString
}

class ArticleInfoUriCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[ArticleInfoUriKey, Set[ArticleInfo]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
