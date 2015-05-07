package com.keepit.rover.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ NormalizedURI, IndexableUri }
import com.keepit.rover.article.{ ArticleKind, Article }
import com.keepit.rover.article.content.{ NormalizationInfoHolder, HttpInfoHolder }
import com.keepit.rover.manager.ArticleFetchingPolicy
import com.keepit.rover.model.{ RoverArticleInfo, ShoeboxArticleUpdates, ArticleInfo, ShoeboxArticleUpdate }
import com.keepit.rover.sensitivity.RoverSensitivityCommander
import com.keepit.rover.store.RoverArticleStore
import com.keepit.common.core._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.Failure

@Singleton
class RoverCommander @Inject() (
    articleCommander: ArticleCommander,
    fetchCommander: FetchCommander,
    sensitivityCommander: RoverSensitivityCommander,
    articleStore: RoverArticleStore,
    articlePolicy: ArticleFetchingPolicy,
    private implicit val executionContext: ExecutionContext,
    airbrake: AirbrakeNotifier) {

  def doMeAFavor(uri: IndexableUri): Future[Unit] = {
    val toBeInternedByPolicy = articlePolicy.toBeInterned(uri.url, uri.state)
    val interned = articleCommander.internByUri(uri.id.get, uri.url, toBeInternedByPolicy)
    val neverFetched = interned.collect { case (kind, info) if info.lastFetchedAt.isEmpty => (info.id.get -> info) }
    fetchCommander.fetchWithTopPriority(neverFetched.keySet).imap { results =>
      val failed = results.collect { case (infoId, Failure(error)) => neverFetched(infoId).articleKind -> error }
      if (failed.nonEmpty) {
        airbrake.notify(s"Failed to schedule top priority fetches for uri ${uri.id.get}: ${uri.url}\n${failed.mkString("\n")}")
      }
    }
  }

  def getShoeboxUpdates(seq: SequenceNumber[ArticleInfo], limit: Int): Future[Option[ShoeboxArticleUpdates]] = {
    val updatedInfos = articleCommander.getArticleInfosBySequenceNumber(seq, limit)
    if (updatedInfos.isEmpty) Future.successful(None)
    else {
      val futureUpdates = updatedInfos.flatMap { info =>
        info.getLatestKey.map { latestKey =>
          articleStore.get(latestKey).map { latestArticleOption =>
            latestArticleOption.map(toShoeboxArticleUpdate(info))
          }
        }
      }
      val maxSeq = updatedInfos.map(_.seq).max
      Future.sequence(futureUpdates).imap { updates =>
        Some(ShoeboxArticleUpdates(updates.flatten, maxSeq))
      }
    }
  }

  def getBestArticlesByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[Article]]] = {
    val futureUriIdWithArticles = articleCommander.getBestArticlesByUris(uriIds).map {
      case (uriId, futureArticles) =>
        futureArticles.imap(uriId -> _)
    }
    Future.sequence(futureUriIdWithArticles).imap(_.toMap)
  }

  def getOrElseFetchBestArticle[A <: Article](uri: IndexableUri)(implicit kind: ArticleKind[A]): Future[Option[A]] = {
    val info = articleCommander.internByUri(uri.id.get, uri.url, Set(kind))(kind)
    getOrElseFetchBestArticle(info).imap(_.map(_.asExpected[A]))
  }

  private def getOrElseFetchBestArticle(info: RoverArticleInfo): Future[Option[info.A]] = {
      articleCommander.getBestArticle(info) flatMap {
      case None if (info.lastFetchedAt.isEmpty) => {
        articleCommander.markAsFetching(info.id.get)
        fetchCommander.fetchAndPersist(info)
      }
      case fetchedArticleOpt: Option[info.A] => Future.successful(fetchedArticleOpt)
    }
  }

  private def toShoeboxArticleUpdate(articleInfo: ArticleInfo)(latestArticle: articleInfo.A) = {
    ShoeboxArticleUpdate(
      articleInfo.uriId,
      articleInfo.kind,
      latestArticle.url,
      latestArticle.content.destinationUrl,
      latestArticle.createdAt,
      latestArticle.content.title,
      sensitivityCommander.isSensitive(latestArticle),
      Some(latestArticle.content).collect { case httpContent: HttpInfoHolder => httpContent.http },
      Some(latestArticle.content).collect { case normalizationContent: NormalizationInfoHolder => normalizationContent.normalization }
    )
  }
}
