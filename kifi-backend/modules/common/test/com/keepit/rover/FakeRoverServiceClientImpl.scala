package com.keepit.rover

import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.store.ImageSize
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.HttpClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.Article
import com.keepit.rover.model.{ RoverUriSummary, ShoeboxArticleUpdates, ArticleInfo }

import scala.collection.mutable
import scala.concurrent.Future

class FakeRoverServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier) extends RoverServiceClient {

  private val articlesByUri = mutable.Map[Id[NormalizedURI], Set[Article]]().withDefaultValue(Set.empty)
  def setArticlesForUri(uriId: Id[NormalizedURI], articles: Set[Article]) = articlesByUri += (uriId -> articles)

  def getShoeboxUpdates(seq: SequenceNumber[ArticleInfo], limit: Int): Future[Option[ShoeboxArticleUpdates]] = Future.successful(None)
  def fetchAsap(uriId: Id[NormalizedURI], url: String): Future[Unit] = Future.successful(())
  def getBestArticlesByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[Article]]] = Future.successful(uriIds.map(uriId => uriId -> articlesByUri(uriId)).toMap)
  def getArticleInfosByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[ArticleInfo]]] = Future.successful(uriIds.map(_ -> Set.empty[ArticleInfo]).toMap)
  def getUriSummaryByUris(uriIds: Set[Id[NormalizedURI]], idealSize: ImageSize, strictAspectRatio: Boolean = false): Future[Map[Id[NormalizedURI], RoverUriSummary]] = Future.successful(Map.empty)
  def getOrElseFetchUriSummary(uriId: Id[NormalizedURI], url: String, idealSize: ImageSize, strictAspectRatio: Boolean = false): Future[Option[RoverUriSummary]] = Future.successful(None)
}
