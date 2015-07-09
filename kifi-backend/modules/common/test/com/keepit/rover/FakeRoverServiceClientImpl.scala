package com.keepit.rover

import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.time.Clock
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.HttpClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.{ ArticleKind, Article }
import com.keepit.rover.document.utils.Signature
import com.keepit.rover.model._
import com.keepit.common.core._
import org.joda.time.DateTime

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import com.keepit.common.time._

class FakeRoverServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier,
    clock: Clock) extends RoverServiceClient {

  private val articlesByUri = mutable.Map[Id[NormalizedURI], Set[Article]]().withDefaultValue(Set.empty)
  def setArticlesForUri(uriId: Id[NormalizedURI], articles: Set[Article]) = articlesByUri += (uriId -> articles)

  private val articleSummariesByUri = mutable.Map[Id[NormalizedURI], RoverUriSummary]()
  def setSummaryforUri(uriId: Id[NormalizedURI], summary: RoverUriSummary) = articleSummariesByUri += (uriId -> summary)
  def setDescriptionForUri(uriId: Id[NormalizedURI], description: String) = {
    val summary = articleSummariesByUri.getOrElse(uriId, RoverUriSummary.empty)
    setSummaryforUri(uriId, summary.copy(article = summary.article.copy(description = Some(description))))
  }

  val articlesByUrl = mutable.Map[String, Set[Article]]().withDefaultValue(Set.empty)
  def setArticlesForUrl(url: String, articles: Set[Article]) = articlesByUrl += (url -> articles)

  private val signatureByUrlAndKind = mutable.Map[(String, ArticleKind[_ <: Article]), (Signature, DateTime)]()
  def setSignatureForUrl[A <: Article](url: String, signature: Signature)(implicit kind: ArticleKind[A]) = signatureByUrlAndKind += ((url, kind) -> (signature, currentDateTime))

  def getShoeboxUpdates(seq: SequenceNumber[ArticleInfo], limit: Int): Future[Option[ShoeboxArticleUpdates]] = Future.successful(None)
  def fetchAsap(uriId: Id[NormalizedURI], url: String, refresh: Boolean): Future[Unit] = Future.successful(())
  def getBestArticlesByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[Article]]] = Future.successful(uriIds.map(uriId => uriId -> articlesByUri(uriId)).toMap)
  def getArticleInfosByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[ArticleInfo]]] = Future.successful(uriIds.map(_ -> Set.empty[ArticleInfo]).toMap)
  def getImagesByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], BasicImages]] = Future.successful(Map.empty)
  def getArticleSummaryByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], RoverArticleSummary]] = getUriSummaryByUris(uriIds).imap(_.mapValues(_.article))
  def getUriSummaryByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], RoverUriSummary]] = Future.successful {
    uriIds.map(uriId => uriId -> articleSummariesByUri.get(uriId)).toMap.collect { case (uriId, Some(article)) => uriId -> article }
  }
  def getOrElseFetchUriSummary(uriId: Id[NormalizedURI], url: String): Future[Option[RoverUriSummary]] = Future.successful(articleSummariesByUri.get(uriId))
  def getOrElseFetchRecentArticle[A <: Article](url: String, recency: Duration)(implicit kind: ArticleKind[A]): Future[Option[A]] = Future.successful {
    articlesByUrl(url).collectFirst {
      case article if (article.kind == kind) && (article.createdAt isAfter clock.now().minusSeconds(recency.toSeconds.toInt)) =>
        article.asExpected[A]
    }
  }
  def getOrElseComputeRecentContentSignature[A <: Article](url: String, recency: Duration)(implicit kind: ArticleKind[A]): Future[Option[Signature]] = Future.successful {
    signatureByUrlAndKind.get((url, kind)).collect {
      case (signature, createdAt) if createdAt isAfter clock.now().minusSeconds(recency.toSeconds.toInt) => signature
    }
  }

  def getPornDetectorModel(): Future[Map[String, Float]] = Future.successful(Map.empty)
  def detectPorn(query: String): Future[Map[String, Float]] = Future.successful(Map.empty)
  def whitelist(words: String): Future[String] = Future.successful("")

  def getAllProxies(): Future[Seq[HttpProxy]] = Future.successful(List())
  def saveProxy(proxy: HttpProxy): Future[HttpProxy] = Future.successful(HttpProxy(alias = "", host = "", port = 0, scheme = ProxyScheme.Http, username = None, password = None))

  def getAllUrlRules(): Future[Seq[UrlRule]] = Future.successful(List())
  def saveUrlRule(urlRule: UrlRule): Future[UrlRule] = Future.successful(UrlRule(pattern = "", proxy = None))
}
