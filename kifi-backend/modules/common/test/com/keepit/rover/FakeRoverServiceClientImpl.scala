package com.keepit.rover

import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.net.HttpClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ NormalizedURI, IndexableUri }
import com.keepit.rover.article.Article
import com.keepit.rover.model.{ ShoeboxArticleUpdates, ArticleInfo }

import scala.concurrent.Future

class FakeRoverServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier) extends RoverServiceClient {

  def getShoeboxUpdates(seq: SequenceNumber[ArticleInfo], limit: Int): Future[Option[ShoeboxArticleUpdates]] = Future.successful(None)
  def fetchAsap(uri: IndexableUri): Future[Unit] = Future.successful(())
  def getArticlesByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[Article]]] = Future.successful(uriIds.map(_ -> Set.empty[Article]).toMap)
  def getArticleInfosByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[ArticleInfo]]] = Future.successful(uriIds.map(_ -> Set.empty[ArticleInfo]).toMap)
}
