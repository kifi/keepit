package com.keepit.rover

import com.google.inject.Inject
import com.keepit.common.db.{ State, Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json.TupleFormat
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ CallTimeouts, HttpClient }
import com.keepit.common.routes.Rover
import com.keepit.common.service.{ ServiceType, ServiceClient }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.model.{ NormalizedURI }
import com.keepit.rover.article.{ ArticleKind, Article }
import com.keepit.rover.model._
import play.api.libs.json.Json
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }

trait RoverServiceClient extends ServiceClient {
  final val serviceType = ServiceType.ROVER
  def getShoeboxUpdates(seq: SequenceNumber[ArticleInfo], limit: Int): Future[Option[ShoeboxArticleUpdates]]
  def fetchAsap(uriId: Id[NormalizedURI], url: String, state: State[NormalizedURI]): Future[Unit]
  def getBestArticlesByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[Article]]]
  def getArticleInfosByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[ArticleInfo]]]
  def getUriSummaryByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], RoverUriSummary]]
  def getOrElseFetchUriSummary(uriId: Id[NormalizedURI], url: String): Future[Option[RoverUriSummary]] // slow, prefer getUriSummaryByUris
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

  def fetchAsap(uriId: Id[NormalizedURI], url: String, state: State[NormalizedURI]): Future[Unit] = {
    val payload = Json.obj(
      "uriId" -> uriId,
      "url" -> url,
      "state" -> state
    )
    call(Rover.internal.fetchAsap, payload, callTimeouts = longTimeout).map { _ => () }
  }

  def getBestArticlesByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], Set[Article]]] = {
    if (uriIds.isEmpty) Future.successful(Map.empty)
    else {
      val payload = Json.toJson(uriIds)
      call(Rover.internal.getBestArticlesByUris, payload, callTimeouts = longTimeout).map { r =>
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

  def getUriSummaryByUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], RoverUriSummary]] = {
    val contentSummaryProvider = RoverUriSummary.defaultProvider
    val futureArticleSummaryByUriId = getBestArticleSummaryByUris(uriIds)(contentSummaryProvider)
    val futureImagesByUriId = getImagesByUris(uriIds)(contentSummaryProvider)
    for {
      articleSummaryByUriId <- futureArticleSummaryByUriId
      imagesByUriId <- futureImagesByUriId
    } yield {
      articleSummaryByUriId.collect {
        case (uriId, Some(articleSummary)) =>
          uriId -> RoverUriSummary(articleSummary, imagesByUriId.getOrElse(uriId, Set.empty))
      }
    }
  }

  private def getBestArticleSummaryByUris[A <: Article](uriIds: Set[Id[NormalizedURI]])(implicit kind: ArticleKind[A]): Future[Map[Id[NormalizedURI], Option[RoverArticleSummary]]] = {
    if (uriIds.isEmpty) Future.successful(Map.empty)
    else {
      import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
      val keys = uriIds.map(RoverArticleSummaryKey(_, kind))
      cacheProvider.articleSummaryCache.bulkGetOrElseFutureOpt(keys) { missingKeys =>
        val missingUriIds = missingKeys.map(_.uriId)
        val payload = Json.obj(
          "uriIds" -> missingUriIds,
          "kind" -> kind
        )
        call(Rover.internal.getBestArticleSummaryByUris, payload).map { r =>
          implicit val reads = TupleFormat.tuple2Reads[Id[NormalizedURI], RoverArticleSummary]
          val missingSummariesByUriId = (r.json).as[Seq[(Id[NormalizedURI], RoverArticleSummary)]].toMap
          missingKeys.map { key => key -> missingSummariesByUriId.get(key.uriId) }.toMap
        }
      } imap {
        _.map { case (key, articleSummaryOpt) => key.uriId -> articleSummaryOpt }
      }
    }
  }

  private def getImagesByUris[A <: Article](uriIds: Set[Id[NormalizedURI]])(implicit kind: ArticleKind[A]): Future[Map[Id[NormalizedURI], Set[RoverImage]]] = {
    if (uriIds.isEmpty) Future.successful(Map.empty)
    else {
      import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
      val keys = uriIds.map(RoverArticleImagesKey(_, kind))
      cacheProvider.articleImagesCache.bulkGetOrElseFuture(keys) { missingKeys =>
        val missingUriIds = missingKeys.map(_.uriId)
        val payload = Json.obj(
          "uriIds" -> missingUriIds,
          "kind" -> kind
        )
        call(Rover.internal.getImagesByUris, payload).map { r =>
          implicit val reads = TupleFormat.tuple2Reads[Id[NormalizedURI], Set[RoverImage]]
          val missingImagesByUriId = (r.json).as[Seq[(Id[NormalizedURI], Set[RoverImage])]].toMap
          missingKeys.map { key => key -> missingImagesByUriId.getOrElse(key.uriId, Set.empty) }.toMap
        }
      } imap {
        _.collect { case (key, images) => key.uriId -> images }
      }
    }
  }

  def getOrElseFetchUriSummary(uriId: Id[NormalizedURI], url: String): Future[Option[RoverUriSummary]] = {
    val contentSummaryProvider = RoverUriSummary.defaultProvider
    getOrElseFetchArticleSummaryAndImages(uriId, url)(contentSummaryProvider).imap(_.map {
      case (summary, images) => RoverUriSummary(summary, images)
    })
  }

  // Do not use the cache for this one (Rover takes care of it, needs more information, limits race conditions)
  private def getOrElseFetchArticleSummaryAndImages[A <: Article](uriId: Id[NormalizedURI], url: String)(implicit kind: ArticleKind[A]): Future[Option[(RoverArticleSummary, Set[RoverImage])]] = {
    val payload = Json.obj(
      "uriId" -> uriId,
      "url" -> url,
      "kind" -> kind
    )
    call(Rover.internal.getOrElseFetchArticleSummaryAndImages, payload, callTimeouts = longTimeout).map { r =>
      implicit val reads = TupleFormat.tuple2Reads[RoverArticleSummary, Set[RoverImage]]
      r.json.asOpt[(RoverArticleSummary, Set[RoverImage])]
    }
  }
}

case class RoverCacheProvider @Inject() (
  articleSummaryCache: RoverArticleSummaryCache,
  articleImagesCache: RoverArticleImagesCache)
