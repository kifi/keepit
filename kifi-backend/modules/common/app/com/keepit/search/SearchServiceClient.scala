package com.keepit.search

import com.keepit.common.zookeeper._
import com.keepit.common.healthcheck.BenchmarkResultsJson._
import com.keepit.common.healthcheck.{ AirbrakeNotifier, BenchmarkResults }
import com.keepit.common.service.{ RequestConsolidator, ServiceClient, ServiceType, ServiceUri }
import com.keepit.common.db.Id
import com.keepit.common.net.{ ClientResponse, HttpClient }
import com.keepit.common.routes.{ ServiceRoute, Search, Common }
import com.keepit.model.{ Collection, NormalizedURI, User }
import com.keepit.search.user.UserSearchResult
import com.keepit.search.user.UserSearchRequest
import com.keepit.search.spellcheck.ScoredSuggest
import com.keepit.search.sharding.{ DistributedSearchRouter }
import play.api.libs.json._
import play.twirl.api.Html
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{ Future }
import scala.concurrent.duration._
import com.keepit.typeahead.TypeaheadHit
import com.keepit.social.{ BasicUser, TypeaheadUserHit }
import com.keepit.typeahead.PrefixMatching
import com.keepit.typeahead.PrefixFilter
import scala.collection.mutable.ListBuffer

trait SearchServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SEARCH

  def warmUpUser(userId: Id[User]): Unit

  def updateURIGraph(): Unit
  def reindexURIGraph(): Unit

  def updateUserGraph(): Unit
  def updateSearchFriendGraph(): Unit
  def reindexUserGraphs(): Unit

  def index(): Unit
  def reindex(): Unit
  def articleIndexerSequenceNumber(): Future[Int]

  def reindexUsers(): Unit
  def updateUserIndex(): Unit

  def sharingUserInfo(userId: Id[User], uriId: Id[NormalizedURI]): Future[SharingUserInfo]
  def sharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[SharingUserInfo]]
  def refreshSearcher(): Unit
  def refreshPhrases(): Unit
  def searchUsers(userId: Option[Id[User]], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[UserSearchResult]
  def userTypeahead(userId: Id[User], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[Seq[TypeaheadHit[BasicUser]]]
  def userTypeaheadWithUserId(userId: Id[User], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[Seq[TypeaheadHit[TypeaheadUserHit]]]
  def explainResult(query: String, userId: Id[User], uriId: Id[NormalizedURI], lang: String): Future[Html]
  def correctSpelling(text: String, enableBoost: Boolean): Future[String]
  def showUserConfig(id: Id[User]): Future[SearchConfig]
  def setUserConfig(id: Id[User], params: Map[String, String]): Unit
  def resetUserConfig(id: Id[User]): Unit
  def getSearchDefaultConfig: Future[SearchConfig]

  def dumpLuceneURIGraph(userId: Id[User]): Future[Html]
  def dumpLuceneCollection(colId: Id[Collection], userId: Id[User]): Future[Html]
  def dumpLuceneDocument(uri: Id[NormalizedURI]): Future[Html]

  def benchmarks(): Future[BenchmarkResults]
  def version(): Future[String]

  def searchWithConfig(userId: Id[User], query: String, maxHits: Int, config: SearchConfig): Future[Seq[(String, String, String)]]

  def leaveOneOut(queryText: String, stem: Boolean, useSketch: Boolean): Future[Map[String, Float]]
  def allSubsets(queryText: String, stem: Boolean, useSketch: Boolean): Future[Map[String, Float]]
  def semanticSimilarity(query1: String, query2: String, stem: Boolean): Future[Float]
  def visualizeSemanticVector(queries: Seq[String]): Future[Seq[String]]
  def semanticLoss(query: String): Future[Map[String, Float]]
  def indexInfoList(): Seq[Future[(ServiceInstance, Seq[IndexInfo])]]

  def searchMessages(userId: Id[User], query: String, page: Int): Future[Seq[String]]

  def augmentation(request: ItemAugmentationRequest): Future[ItemAugmentationResponse]

  //todo(Léo): move to DistributedSearchServiceClient once sharing user info has been migrated to the new augmentation logic
  def distRouter: DistributedSearchRouter
  def call(instance: ServiceInstance, url: ServiceRoute, body: JsValue): Future[ClientResponse]
}

class SearchServiceClientImpl(
  override val serviceCluster: ServiceCluster,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier)
    extends SearchServiceClient() {

  // request consolidation
  private[this] val consolidateSharingUserInfoReq = new RequestConsolidator[(Id[User], Id[NormalizedURI]), SharingUserInfo](ttl = 3 seconds)

  lazy val distRouter = {
    val router = new DistributedSearchRouter(this)
    serviceCluster.setCustomRouter(Some(router))
    router
  }

  def warmUpUser(userId: Id[User]): Unit = {
    call(Search.internal.warmUpUser(userId))
  }

  def updateURIGraph(): Unit = {
    broadcast(Search.internal.updateURIGraph())
  }

  def reindexURIGraph(): Unit = {
    broadcast(Search.internal.uriGraphReindex())
  }

  def index(): Unit = {
    broadcast(Search.internal.searchUpdate())
  }

  def reindex(): Unit = {
    broadcast(Search.internal.searchReindex())
  }

  def reindexUsers(): Unit = {
    broadcast(Search.internal.userReindex())
  }

  def updateUserIndex(): Unit = {
    broadcast(Search.internal.updateUserIndex())
  }

  def sharingUserInfo(userId: Id[User], uriId: Id[NormalizedURI]): Future[SharingUserInfo] = consolidateSharingUserInfoReq((userId, uriId)) {
    case (userId, uriId) => sharingUserInfo(userId, Seq(uriId)).map(_.head)
  }

  def sharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[SharingUserInfo]] = {
    if (uriIds.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val items = uriIds.map(AugmentableItem(_))
      val request = ItemAugmentationRequest.uniform(userId, items: _*)
      augmentation(request).map { response =>
        items.map { item =>
          val info = response.infos(item)
          SharingUserInfo(info.keeps.map(_.keptBy).flatten.toSet, info.keeps.size + info.otherDiscoverableKeeps + info.otherDiscoverableKeeps)
        }
      }
    }
  }

  def articleIndexerSequenceNumber(): Future[Int] = {
    call(Search.internal.getSequenceNumber()).map(r => (r.json \ "sequenceNumber").as[Int])
  }

  def refreshSearcher(): Unit = {
    broadcast(Search.internal.refreshSearcher())
  }

  def refreshPhrases(): Unit = {
    broadcast(Search.internal.refreshPhrases())
  }

  def searchUsers(userId: Option[Id[User]], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[UserSearchResult] = {
    val payload = Json.toJson(UserSearchRequest(userId, query, maxHits, context, filter))
    call(Search.internal.searchUsers(), payload).map { r =>
      Json.fromJson[UserSearchResult](r.json).get
    }
  }

  def userTypeahead(userId: Id[User], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[Seq[TypeaheadHit[BasicUser]]] = {
    val payload = Json.toJson(UserSearchRequest(Some(userId), query, maxHits, context, filter))
    call(Search.internal.userTypeahead(), payload).map { r =>
      val userSearchResult = Json.fromJson[UserSearchResult](r.json).get
      if (userSearchResult.hits.isEmpty) Seq[TypeaheadHit[BasicUser]]()
      else {
        val queryTerms = PrefixFilter.normalize(query).split("\\s+")
        var ordinal = 0
        userSearchResult.hits.map { hit =>
          val name = hit.basicUser.firstName + " " + hit.basicUser.lastName
          val normalizedName = PrefixFilter.normalize(name)
          val score = PrefixMatching.distance(normalizedName, queryTerms)
          ordinal += 1
          new TypeaheadHit[BasicUser](score, name, ordinal, hit.basicUser)
        }
      }
    }
  }

  def userTypeaheadWithUserId(userId: Id[User], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[Seq[TypeaheadHit[TypeaheadUserHit]]] = {
    val payload = Json.toJson(UserSearchRequest(Some(userId), query, maxHits, context, filter))
    call(Search.internal.userTypeahead(), payload).map { r =>
      val userSearchResult = Json.fromJson[UserSearchResult](r.json).get
      if (userSearchResult.hits.isEmpty) Seq()
      else {
        val queryTerms = PrefixFilter.normalize(query).split("\\s+")
        var ordinal = 0
        userSearchResult.hits.map { hit =>
          val name = hit.basicUser.firstName + " " + hit.basicUser.lastName
          val normalizedName = PrefixFilter.normalize(name)
          val score = PrefixMatching.distance(normalizedName, queryTerms)
          ordinal += 1
          val basicUserWithUserId = TypeaheadUserHit.fromBasicUserAndId(hit.basicUser, hit.id)
          TypeaheadHit(score, name, ordinal, basicUserWithUserId)
        }
      }
    }
  }

  def explainResult(query: String, userId: Id[User], uriId: Id[NormalizedURI], lang: String): Future[Html] = {
    log.info("running explain in distributed mode")
    distRouter.call(userId, uriId, Search.internal.explain(query, userId, uriId, lang)).map(r => Html(r.body))
  }

  def dumpLuceneURIGraph(userId: Id[User]): Future[Html] = {
    call(Search.internal.uriGraphDumpLuceneDocument(userId)).map(r => Html(r.body))
  }

  def dumpLuceneCollection(colId: Id[Collection], userId: Id[User]): Future[Html] = {
    call(Search.internal.collectionDumpLuceneDocument(colId, userId)).map(r => Html(r.body))
  }

  def dumpLuceneDocument(id: Id[NormalizedURI]): Future[Html] = {
    call(Search.internal.searchDumpLuceneDocument(id)).map(r => Html(r.body))
  }

  def benchmarks(): Future[BenchmarkResults] = {
    call(Common.internal.benchmarksResults()).map(r => Json.fromJson[BenchmarkResults](r.json).get)
  }

  def version(): Future[String] = {
    call(Common.internal.version()).map(r => r.body)
  }

  def correctSpelling(text: String, enableBoost: Boolean): Future[String] = {
    call(Search.internal.correctSpelling(text, enableBoost)).map { r =>
      val suggests = r.json.as[JsArray].value.map { x => Json.fromJson[ScoredSuggest](x).get }
      suggests.map { x => x.value + ", " + x.score }.mkString("\n")
    }
  }

  def showUserConfig(id: Id[User]): Future[SearchConfig] = {
    call(Search.internal.showUserConfig(id)).map { r =>
      val param = Json.fromJson[Map[String, String]](r.json).get
      new SearchConfig(param)
    }
  }

  def setUserConfig(id: Id[User], params: Map[String, String]): Unit = {
    broadcast(Search.internal.setUserConfig(id), Json.toJson(params))
  }

  def resetUserConfig(id: Id[User]): Unit = {
    broadcast(Search.internal.resetUserConfig(id))
  }

  def getSearchDefaultConfig: Future[SearchConfig] = {
    call(Search.internal.getSearchDefaultConfig).map { r =>
      val param = Json.fromJson[Map[String, String]](r.json).get
      new SearchConfig(param)
    }
  }

  def searchWithConfig(userId: Id[User], query: String, maxHits: Int, config: SearchConfig): Future[Seq[(String, String, String)]] = {
    val payload = Json.obj("userId" -> userId.id, "query" -> query, "maxHits" -> maxHits, "config" -> Json.toJson(config.params))
    call(Search.internal.searchWithConfig(), payload).map { r =>
      r.json.as[JsArray].value.map { js =>
        ((js \ "uriId").as[Long].toString, (js \ "title").as[String], (js \ "url").as[String])
      }
    }
  }

  def leaveOneOut(queryText: String, stem: Boolean, useSketch: Boolean): Future[Map[String, Float]] = {
    call(Search.internal.leaveOneOut(queryText, stem, useSketch)).map { r =>
      Json.fromJson[Map[String, Float]](r.json).get
    }
  }

  def allSubsets(queryText: String, stem: Boolean, useSketch: Boolean): Future[Map[String, Float]] = {
    call(Search.internal.allSubsets(queryText, stem, useSketch)).map { r =>
      Json.fromJson[Map[String, Float]](r.json).get
    }
  }

  def semanticSimilarity(query1: String, query2: String, stem: Boolean): Future[Float] = {
    call(Search.internal.semanticSimilarity(query1, query2, stem)).map { r =>
      Json.fromJson[Float](r.json).get
    }
  }

  def visualizeSemanticVector(queries: Seq[String]): Future[Seq[String]] = {
    val payload = Json.toJson(queries)
    call(Search.internal.visualizeSemanticVector(), payload).map { r =>
      Json.fromJson[Seq[String]](r.json).get
    }
  }

  def semanticLoss(query: String): Future[Map[String, Float]] = {
    call(Search.internal.semanticLoss(query)).map { r =>
      Json.fromJson[Map[String, Float]](r.json).get
    }
  }

  def indexInfoList(): Seq[Future[(ServiceInstance, Seq[IndexInfo])]] = {
    val url = Search.internal.indexInfoList()
    serviceCluster.allServices.map(new ServiceUri(_, protocol, port, url.url)).map {
      case u: ServiceUri =>
        callUrl(url, u, JsNull).map { r => (u.serviceInstance, Json.fromJson[Seq[IndexInfo]](r.json).get) }
    }
  }

  def updateUserGraph() {
    broadcast(Search.internal.updateUserGraph())
  }
  def updateSearchFriendGraph() {
    broadcast(Search.internal.updateSearchFriendGraph())
  }
  def reindexUserGraphs() {
    broadcast(Search.internal.reindexUserGraphs())
  }

  //the return values here are external id's of threads, a model that is not available here. Need to rethink this a bit. -Stephen
  def searchMessages(userId: Id[User], query: String, page: Int): Future[Seq[String]] = {
    call(Search.internal.searchMessages(userId, query, page)).map { r =>
      Json.fromJson[Seq[String]](r.json).get
    }
  }

  def augmentation(request: ItemAugmentationRequest): Future[ItemAugmentationResponse] = {
    call(Search.internal.augmentation(), Json.toJson(request)).map(_.json.as[ItemAugmentationResponse])
  }

  def call(instance: ServiceInstance, url: ServiceRoute, body: JsValue): Future[ClientResponse] = {
    callUrl(url, new ServiceUri(instance, protocol, port, url.url), body)
  }
}

class SearchRequestBuilder(val params: ListBuffer[(String, JsValue)]) extends AnyVal {
  def +=(name: String, value: String): Unit = { params += (name -> JsString(value)) }
  def +=(name: String, value: Long): Unit = { params += (name -> JsNumber(value)) }
  def +=(name: String, value: Boolean): Unit = { params += (name -> JsBoolean(value)) }
  def +=(name: String, value: JsValue): Unit = { params += (name -> value) }

  def build: JsObject = JsObject(params)
}
