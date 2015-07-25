package com.keepit.search

import com.keepit.common.service.{ ServiceUri, ServiceType, ServiceClient }
import com.keepit.common.zookeeper.{ ServiceCluster, ServiceInstance }
import com.keepit.search.engine.library.LibraryShardResult
import com.keepit.search.engine.uri.UriShardResult
import com.keepit.search.engine.user.UserShardResult
import com.keepit.search.index.sharding.{ DistributedSearchRouter, Shard }
import com.keepit.model.{ User, NormalizedURI }
import scala.concurrent.Future
import com.keepit.common.db.Id
import com.keepit.common.routes.{ Search, ServiceRoute }
import com.keepit.common.net.{ HttpClient, ClientResponse }
import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import com.keepit.search.augmentation.{ ItemAugmentationResponse, ItemAugmentationRequest }

trait DistributedSearchServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SEARCH
  //
  // Distributed Search
  //
  def distPlan(userId: Id[User], shards: Set[Shard[NormalizedURI]], maxShardsPerInstance: Int): Seq[(ServiceInstance, Set[Shard[NormalizedURI]])]

  def distPlanRemoteOnly(userId: Id[User], maxShardsPerInstance: Int): Seq[(ServiceInstance, Set[Shard[NormalizedURI]])]

  def distSearchUris(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], request: UriSearchRequest): Seq[Future[UriShardResult]]

  def distSearchLibraries(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], request: LibrarySearchRequest): Seq[Future[Seq[LibraryShardResult]]]

  def distSearchUsers(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], request: UserSearchRequest): Seq[Future[Seq[UserShardResult]]]

  def distLangFreqs(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], userId: Id[User], libraryContext: Option[LibraryScope]): Seq[Future[Map[Lang, Int]]]

  def distAugmentation(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], request: ItemAugmentationRequest): Seq[Future[ItemAugmentationResponse]]

  def call(instance: ServiceInstance, url: ServiceRoute, body: JsValue): Future[ClientResponse]

  def call(userId: Id[User], uriId: Id[NormalizedURI], url: ServiceRoute, body: JsValue): Future[ClientResponse]
}

class DistributedSearchServiceClientImpl @Inject() (
    val serviceCluster: ServiceCluster,
    val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier) extends DistributedSearchServiceClient {

  private lazy val distRouter = {
    val router = new DistributedSearchRouter(this)
    serviceCluster.setCustomRouter(Some(router))
    router
  }

  def distPlan(userId: Id[User], shards: Set[Shard[NormalizedURI]], maxShardsPerInstance: Int = Int.MaxValue): Seq[(ServiceInstance, Set[Shard[NormalizedURI]])] = {
    distRouter.plan(userId, shards, maxShardsPerInstance)
  }

  def distPlanRemoteOnly(userId: Id[User], maxShardsPerInstance: Int = Int.MaxValue): Seq[(ServiceInstance, Set[Shard[NormalizedURI]])] = {
    distRouter.planRemoteOnly(userId, maxShardsPerInstance)
  }

  def distSearchUris(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], request: UriSearchRequest): Seq[Future[UriShardResult]] = {
    if (plan.isEmpty) Seq.empty else distRouter.dispatch(plan, Search.internal.distSearchUris(), Json.toJson(request)).map { futureClientResponse =>
      futureClientResponse.map { r => new UriShardResult(r.json) }
    }
  }

  def distLangFreqs(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], userId: Id[User], libraryContext: Option[LibraryScope]): Seq[Future[Map[Lang, Int]]] = {
    // keep the following in sync with SearchController
    val payload = Json.obj(
      "userId" -> userId,
      "library" -> libraryContext
    )
    distRouter.dispatch(plan, Search.internal.distLangFreqs, payload).map { f =>
      f.map { r => r.json.as[Map[String, Int]].map { case (k, v) => Lang(k) -> v } }
    }
  }

  def distAugmentation(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], request: ItemAugmentationRequest): Seq[Future[ItemAugmentationResponse]] = {
    if (plan.isEmpty) Seq.empty else distRouter.dispatch(plan, Search.internal.distAugmentation(), Json.toJson(request)).map { futureClientResponse =>
      futureClientResponse.map { r => r.json.as[ItemAugmentationResponse] }
    }
  }

  def distSearchLibraries(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], request: LibrarySearchRequest): Seq[Future[Seq[LibraryShardResult]]] = {
    if (plan.isEmpty) Seq.empty else distRouter.dispatch(plan, Search.internal.distSearchLibraries(), Json.toJson(request)).map { futureClientResponse =>
      futureClientResponse.map { r => r.json.as[JsArray].value.map(_.as[LibraryShardResult]) }
    }
  }

  def distSearchUsers(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], request: UserSearchRequest): Seq[Future[Seq[UserShardResult]]] = {
    if (plan.isEmpty) Seq.empty else distRouter.dispatch(plan, Search.internal.distSearchUsers(), Json.toJson(request)).map { futureClientResponse =>
      futureClientResponse.map { r => r.json.as[JsArray].value.map(_.as[UserShardResult]) }
    }
  }

  // for DistributedSearchRouter
  def call(instance: ServiceInstance, url: ServiceRoute, body: JsValue): Future[ClientResponse] = {
    callUrl(url, new ServiceUri(instance, protocol, port, url.url), body)
  }

  def call(userId: Id[User], uriId: Id[NormalizedURI], url: ServiceRoute, body: JsValue): Future[ClientResponse] = {
    distRouter.call(userId, uriId, url, body)
  }
}
