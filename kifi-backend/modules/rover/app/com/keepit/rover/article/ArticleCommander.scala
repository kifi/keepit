package com.keepit.rover.article

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.cache._
import com.keepit.common.core._
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ State, Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.model.{ NormalizedURIStates, NormalizedURI }
import com.keepit.rover.article.fetcher.ArticleFetcherProvider
import com.keepit.rover.article.policy.ArticleInfoPolicy
import com.keepit.rover.manager.{ FetchTask, FetchTaskQueue }
import com.keepit.rover.model._
import com.keepit.rover.store.RoverArticleStore

import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Try }

@Singleton
class ArticleCommander @Inject() (
    db: Database,
    articleInfoRepo: ArticleInfoRepo,
    articleInfoCache: ArticleInfoUriCache,
    articleStore: RoverArticleStore,
    topPriorityQueue: FetchTaskQueue.TopPriority,
    articleFetcher: ArticleFetcherProvider,
    articlePolicy: ArticleInfoPolicy,
    airbrake: AirbrakeNotifier,
    private implicit val executionContext: ExecutionContext) extends Logging {

  // Get ArticleInfos

  def getArticleInfosBySequenceNumber(seq: SequenceNumber[ArticleInfo], limit: Int): Seq[ArticleInfo] = {
    db.readOnlyMaster { implicit session =>
      articleInfoRepo.getBySequenceNumber(seq, limit).map(RoverArticleInfo.toArticleInfo)
    }
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

  // Get Articles of all kinds

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

  // Get Articles of a specific kind

  private def getBestArticleFutureByUris[A <: Article](uriIds: Set[Id[NormalizedURI]])(implicit kind: ArticleKind[A]): Map[Id[NormalizedURI], Future[Option[A]]] = {
    getArticleInfosByUris(uriIds).mapValues { infos =>
      infos.find(_.articleKind == kind).map(getBestArticle(_).imap(_.map(_.asExpected[A]))) getOrElse Future.successful(None)
    }
  }

  def getBestArticleByUris[A <: Article](uriIds: Set[Id[NormalizedURI]])(implicit kind: ArticleKind[A]): Future[Map[Id[NormalizedURI], Option[A]]] = {
    val futureUriIdWithArticles = getBestArticleFutureByUris[A](uriIds).map {
      case (uriId, futureArticleOption) =>
        futureArticleOption.imap(uriId -> _)
    }
    Future.sequence(futureUriIdWithArticles).imap(_.toMap)
  }

  def getOrElseFetchBestArticle[A <: Article](uriId: Id[NormalizedURI], url: String)(implicit kind: ArticleKind[A]): Future[Option[A]] = {
    val info = internArticleInfoByUri(uriId, url, Set(kind))(kind)
    getOrElseFetchBestArticle(info).imap(_.map(_.asExpected[A]))
  }

  private def getOrElseFetchBestArticle(info: RoverArticleInfo): Future[Option[info.A]] = {
    getBestArticle(info) flatMap {
      case None if (info.lastFetchedAt.isEmpty) => {
        markAsFetching(info.id.get)
        fetchAndPersist(info)
      }
      case fetchedArticleOpt => Future.successful(fetchedArticleOpt)
    }
  }

  private def internArticleInfoByUri(uriId: Id[NormalizedURI], url: String, kinds: Set[ArticleKind[_ <: Article]]): Map[ArticleKind[_ <: Article], RoverArticleInfo] = {
    // natural race condition with the regular ingestion, hence the 3 attempts
    db.readWrite(attempts = 3) { implicit session =>
      articleInfoRepo.internByUri(uriId, url, kinds)
    }
  }

  // ArticleStore helpers

  def getLatestArticle(info: ArticleInfoHolder): Future[Option[info.A]] = {
    info.getLatestKey.map(articleStore.get) getOrElse Future.successful(None)
  }

  private def getBestArticle(info: ArticleInfoHolder): Future[Option[info.A]] = {
    (info.getBestKey orElse info.getLatestKey).map(articleStore.get) getOrElse Future.successful(None)
  }

  // Fetch related functions

  def getRipeForFetching(limit: Int, queuedForMoreThan: Duration) = {
    db.readOnlyMaster { implicit session =>
      articleInfoRepo.getRipeForFetching(limit, queuedForMoreThan)
    }
  }

  def add(tasks: Seq[FetchTask], queue: FetchTaskQueue): Future[Map[FetchTask, Try[Unit]]] = {
    markAsFetching(tasks.map(_.id): _*)
    queue.add(tasks).map { maybeQueuedTasks =>
      val failedTasks = maybeQueuedTasks.collect { case (task, Failure(_)) => task }.toSeq
      if (failedTasks.nonEmpty) {
        unmarkAsFetching(failedTasks.map(_.id): _*)
      }
      maybeQueuedTasks
    }
  }

  private def markAsFetching(ids: Id[RoverArticleInfo]*): Unit = {
    db.readWrite { implicit session =>
      articleInfoRepo.markAsFetching(ids: _*)
    }
  }

  private def unmarkAsFetching(ids: Id[RoverArticleInfo]*): Unit = {
    db.readWrite { implicit session =>
      articleInfoRepo.unmarkAsFetching(ids: _*)
    }
  }

  def fetchAsap(uriId: Id[NormalizedURI], url: String): Future[Unit] = {
    val toBeInternedByPolicy = articlePolicy.toBeInterned(url, NormalizedURIStates.SCRAPED)
    val interned = internArticleInfoByUri(uriId, url, toBeInternedByPolicy)
    val neverFetched = interned.collect { case (kind, info) if info.lastFetchedAt.isEmpty => (info.id.get -> info) }
    if (neverFetched.isEmpty) Future.successful(())
    else {
      log.info(s"[fetchAsap] Never fetched before for uri ${uriId}: ${url} -> ${neverFetched.keySet.mkString(" | ")}")
      fetchWithTopPriority(neverFetched.keySet).imap { results =>
        val resultsByKind = results.collect { case (infoId, result) => neverFetched(infoId).articleKind -> result }
        log.info(s"[fetchAsap] Fetching with top priority for uri ${uriId}: ${url} -> ${resultsByKind.mkString(" | ")}")
        val failed = resultsByKind.collect { case (kind, Failure(error)) => kind -> error }
        if (failed.nonEmpty) {
          airbrake.notify(s"[fetchAsap] Failed to schedule top priority fetches for uri ${uriId}: ${url} -> ${failed.mkString(" | ")}")
        }
      }
    }
  }

  private def fetchWithTopPriority(ids: Set[Id[RoverArticleInfo]]): Future[Map[Id[RoverArticleInfo], Try[Unit]]] = {
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
