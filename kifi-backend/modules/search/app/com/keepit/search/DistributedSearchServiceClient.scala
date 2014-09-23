package com.keepit.search

import com.keepit.common.service.{ ServiceUri, ServiceType, ServiceClient }
import com.keepit.common.zookeeper.{ ServiceCluster, ServiceInstance }
import com.keepit.search.sharding.{ DistributedSearchRouter, Shard }
import com.keepit.model.{ User, NormalizedURI }
import scala.concurrent.Future
import com.keepit.search.engine.result.LibraryShardResult
import com.keepit.common.db.Id
import play.api.libs.json.{ Json, JsNumber, JsValue }
import com.keepit.common.routes.{ Search, ServiceRoute }
import com.keepit.common.net.{ HttpClient, ClientResponse }
import com.google.inject.Inject
import scala.collection.mutable.ListBuffer
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait DistributedSearchServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SEARCH
  def distLibrarySearch(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], request: LibrarySearchRequest): Seq[Future[Set[LibraryShardResult]]] = ???
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

  def distSearch2(
    plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    query: String,
    filter: Option[String],
    libraryId: LibraryContext,
    maxHits: Int,
    context: Option[String],
    debug: Option[String]): Seq[Future[JsValue]]

  def distLangFreqs(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], userId: Id[User]): Seq[Future[Map[Lang, Int]]]

  def distLangFreqs2(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], userId: Id[User], libraryContext: LibraryContext): Seq[Future[Map[Lang, Int]]]

  def distAugmentation(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], request: ItemAugmentationRequest): Seq[Future[ItemAugmentationResponse]]

  def call(instance: ServiceInstance, url: ServiceRoute, body: JsValue): Future[ClientResponse]
}

class DistributedSearchServiceClientImpl @Inject() (
    searchClient: SearchServiceClient,
    val serviceCluster: ServiceCluster,
    val httpClient: HttpClient,
    val airbrakeNotifier: AirbrakeNotifier) extends DistributedSearchServiceClient {

  private lazy val distRouter = searchClient.distRouter

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

    distSearch(Search.internal.distSearch, plan, userId, firstLang, secondLang, query, filter, LibraryContext.None, maxHits, context, debug)
  }

  def distSearch2(
    plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    query: String,
    filter: Option[String],
    library: LibraryContext,
    maxHits: Int,
    context: Option[String],
    debug: Option[String]): Seq[Future[JsValue]] = {

    distSearch(Search.internal.distSearch2, plan, userId, firstLang, secondLang, query, filter, library, maxHits, context, debug)
  }

  private def distSearch(
    path: ServiceRoute,
    plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])],
    userId: Id[User],
    firstLang: Lang,
    secondLang: Option[Lang],
    query: String,
    filter: Option[String],
    library: LibraryContext,
    maxHits: Int,
    context: Option[String],
    debug: Option[String]): Seq[Future[JsValue]] = {

    var builder = new SearchRequestBuilder(new ListBuffer)
    // keep the following in sync with SearchController
    builder += ("userId", userId.id)
    builder += ("query", query)
    builder += ("maxHits", maxHits)
    builder += ("lang1", firstLang.lang)
    if (secondLang.isDefined) builder += ("lang2", secondLang.get.lang)
    if (filter.isDefined) builder += ("filter", filter.get)
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

  def distLangFreqs(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], userId: Id[User]): Seq[Future[Map[Lang, Int]]] = {
    distRouter.dispatch(plan, Search.internal.distLangFreqs, JsNumber(userId.id)).map { f =>
      f.map { r => r.json.as[Map[String, Int]].map { case (k, v) => Lang(k) -> v } }
    }
  }

  def distLangFreqs2(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], userId: Id[User], libraryContext: LibraryContext): Seq[Future[Map[Lang, Int]]] = {
    var builder = new SearchRequestBuilder(new ListBuffer)
    // keep the following in sync with SearchController
    builder += ("userId", userId.id)
    libraryContext match {
      case LibraryContext.Authorized(libId) => builder += ("authorizedLibrary", libId)
      case LibraryContext.NotAuthorized(libId) => builder += ("library", libId)
      case _ =>
    }
    val request = builder.build

    distRouter.dispatch(plan, Search.internal.distLangFreqs2, request).map { f =>
      f.map { r => r.json.as[Map[String, Int]].map { case (k, v) => Lang(k) -> v } }
    }
  }

  def distAugmentation(plan: Seq[(ServiceInstance, Set[Shard[NormalizedURI]])], request: ItemAugmentationRequest): Seq[Future[ItemAugmentationResponse]] = {
    if (plan.isEmpty) Seq.empty else distRouter.dispatch(plan, Search.internal.distAugmentation(), Json.toJson(request)).map { futureClientResponse =>
      futureClientResponse.map { r => r.json.as[ItemAugmentationResponse] }
    }
  }

  // for DistributedSearchRouter
  def call(instance: ServiceInstance, url: ServiceRoute, body: JsValue): Future[ClientResponse] = {
    callUrl(url, new ServiceUri(instance, protocol, port, url.url), body)
  }
}
