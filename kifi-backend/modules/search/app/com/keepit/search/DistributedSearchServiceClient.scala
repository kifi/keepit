package com.keepit.search

import com.keepit.common.json.EitherFormat
import com.keepit.common.service.{ ServiceUri, ServiceType, ServiceClient }
import com.keepit.common.zookeeper.{ ServiceCluster, ServiceInstance }
import com.keepit.search.engine.library.LibraryShardResult
import com.keepit.search.engine.user.UserShardResult
import com.keepit.search.index.sharding.{ DistributedSearchRouter, Shard }
import com.keepit.model.{ User, NormalizedURI }
import scala.concurrent.Future
import com.keepit.common.db.Id
import com.keepit.common.routes.{ Search, ServiceRoute }
import com.keepit.common.net.{ HttpClient, ClientResponse }
import com.google.inject.Inject
import scala.collection.mutable.ListBuffer
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

  def distSearch(
    plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    query: String,
    filter: Option[String],
    maxHits: Int,
    context: Option[String],
    debug: Option[String]): Seq[Future[JsValue]]

  def distSearchUris(
    plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    query: String,
    filter: Option[Either[Id[User], String]],
    libraryId: LibraryContext,
    orderBy: SearchRanking,
    maxHits: Int,
    context: Option[String],
    debug: Option[String]): Seq[Future[JsValue]]

  def distSearchLibraries(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], request: LibrarySearchRequest): Seq[Future[Seq[LibraryShardResult]]]

  def distSearchUsers(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], request: UserSearchRequest): Seq[Future[Seq[UserShardResult]]]

  def distLangFreqs(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], userId: Id[User], libraryContext: LibraryContext): Seq[Future[Map[Lang, Int]]]

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

  def distSearch(
    plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    query: String,
    filter: Option[String],
    maxHits: Int,
    context: Option[String],
    debug: Option[String]): Seq[Future[JsValue]] = {

    distSearch(Search.internal.distSearch, plan, userId, firstLang, secondLang, query, filter.map(Right(_)), LibraryContext.None, SearchRanking.default, maxHits, context, debug)
  }

  def distSearchUris(
    plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    query: String,
    filter: Option[Either[Id[User], String]],
    library: LibraryContext,
    orderBy: SearchRanking,
    maxHits: Int,
    context: Option[String],
    debug: Option[String]): Seq[Future[JsValue]] = {

    distSearch(Search.internal.distSearchUris, plan, userId, firstLang, secondLang, query, filter, library, orderBy, maxHits, context, debug)
  }

  private def distSearch(
    path: ServiceRoute,
    plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    query: String,
    filter: Option[Either[Id[User], String]],
    library: LibraryContext,
    orderBy: SearchRanking,
    maxHits: Int,
    context: Option[String],
    debug: Option[String]): Seq[Future[JsValue]] = {

    var builder = new SearchRequestBuilder(new ListBuffer)
    // keep the following in sync with SearchController
    builder += ("userId", userId.id)
    builder += ("query", query)
    builder += ("orderBy", orderBy.orderBy)
    builder += ("maxHits", maxHits)
    builder += ("lang1", firstLang.lang)
    if (secondLang.isDefined) builder += ("lang2", secondLang.get.lang)
    if (filter.isDefined) {
      implicit val format = EitherFormat[Id[User], String]
      builder += ("filter", Json.toJson(filter.get))
    }
    library match {
      case LibraryContext.Authorized(libId) => builder += ("authorizedLibrary", libId)
      case LibraryContext.NotAuthorized(libId) => builder += ("library", libId)
      case _ =>
    }
    if (context.isDefined) builder += ("context", context.get)
    if (debug.isDefined) builder += ("debug", debug.get)
    val request = builder.build

    distRouter.dispatch(plan, path, request).map { f => f.map(_.json) }
  }

  def distLangFreqs(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], userId: Id[User], libraryContext: LibraryContext): Seq[Future[Map[Lang, Int]]] = {
    var builder = new SearchRequestBuilder(new ListBuffer)
    // keep the following in sync with SearchController
    builder += ("userId", userId.id)
    libraryContext match {
      case LibraryContext.Authorized(libId) => builder += ("authorizedLibrary", libId)
      case LibraryContext.NotAuthorized(libId) => builder += ("library", libId)
      case _ =>
    }
    val request = builder.build

    distRouter.dispatch(plan, Search.internal.distLangFreqs, request).map { f =>
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

class SearchRequestBuilder(val params: ListBuffer[(String, JsValue)]) extends AnyVal {
  def +=(name: String, value: String): Unit = { params += (name -> JsString(value)) }
  def +=(name: String, value: Long): Unit = { params += (name -> JsNumber(value)) }
  def +=(name: String, value: Boolean): Unit = { params += (name -> JsBoolean(value)) }
  def +=(name: String, value: JsValue): Unit = { params += (name -> value) }

  def build: JsObject = JsObject(params)
}
