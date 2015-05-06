package com.keepit.rover

import com.google.inject.Inject
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json.TupleFormat
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ CallTimeouts, HttpClient }
import com.keepit.common.routes.Rover
import com.keepit.common.service.{ ServiceType, ServiceClient }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.model.{ NormalizedURI, IndexableUri }
import com.keepit.rover.article.Article
import com.keepit.rover.model.{ ShoeboxArticleUpdates, ArticleInfo }
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

trait RoverServiceClient extends ServiceClient {
  final val serviceType = ServiceType.ROVER
  def getShoeboxUpdates(seq: SequenceNumber[ArticleInfo], limit: Int): Future[Option[ShoeboxArticleUpdates]]
  def fetchAsap(uri: IndexableUri): Future[Unit]
  def getArticlesByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[Article]]]
  def getArticleInfosByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[ArticleInfo]]]
}

class RoverServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier,
    cacheProvider: RoverCacheProvider,
    private implicit val executionContext: ExecutionContext) extends RoverServiceClient with Logging {

  private val longTimeout = CallTimeouts(responseTimeout = Some(300000), maxWaitTime = Some(30000), maxJsonParseTime = Some(10000))

  def getShoeboxUpdates(seq: SequenceNumber[ArticleInfo], limit: Int): Future[Option[ShoeboxArticleUpdates]] = {
    call(Rover.internal.getShoeboxUpdates(seq, limit), callTimeouts = longTimeout).map { r => (r.json).asOpt[ShoeboxArticleUpdates] }
  }

  def fetchAsap(uri: IndexableUri): Future[Unit] = {
    val payload = Json.toJson(uri)
    call(Rover.internal.fetchAsap, payload, callTimeouts = longTimeout).map { _ => () }
  }

  def getArticlesByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[Article]]] = {
    if (uriIds.isEmpty) Future.successful(Map.empty)
    else {
      val payload = Json.toJson(uriIds)
      call(Rover.internal.getArticlesByUris, payload, callTimeouts = longTimeout).map { r =>
        implicit val reads = TupleFormat.tuple2Reads[Id[NormalizedURI], Set[Article]]
        (r.json).as[Seq[(Id[NormalizedURI], Set[Article])]].toMap
      }
    }
  }

  def getArticleInfosByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[ArticleInfo]]] = {
    if (uriIds.isEmpty) Future.successful(Map.empty)
    else {
      val payload = Json.toJson(uriIds)
      call(Rover.internal.getArticleInfosByUris, payload).map { r =>
        implicit val reads = TupleFormat.tuple2Reads[Id[NormalizedURI], Set[ArticleInfo]]
        (r.json).as[Seq[(Id[NormalizedURI], Set[ArticleInfo])]].toMap
      }
    }
  }
}

case class RoverCacheProvider @Inject() ()