package com.keepit.search

import com.google.inject.Singleton
import com.keepit.common.crypto.PublicId
import com.keepit.common.zookeeper._
import com.keepit.common.healthcheck.BenchmarkResultsJson._
import com.keepit.common.healthcheck.{ AirbrakeNotifier, BenchmarkResults }
import com.keepit.common.service.{ RequestConsolidator, ServiceClient, ServiceType, ServiceUri }
import com.keepit.common.db.Id
import com.keepit.common.net.{ CallTimeouts, ClientResponse, HttpClient }
import com.keepit.common.routes.{ ServiceRoute, Search, Common }
import com.keepit.model._
import com.keepit.search.index.{ IndexInfo }
import com.keepit.search.user.DeprecatedUserSearchResult
import com.keepit.search.user.DeprecatedUserSearchRequest
import play.api.libs.json._
import play.twirl.api.Html
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{ Future }
import scala.concurrent.duration._
import com.keepit.typeahead._
import com.keepit.social.{ BasicUser, TypeaheadUserHit }
import com.keepit.search.augmentation._
import com.keepit.common.core._

trait SearchServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SEARCH

  def warmUpUser(userId: Id[User]): Unit

  def updateKeepIndex(): Unit
  def updateLibraryIndex(): Unit

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
  def searchUsers(userId: Option[Id[User]], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[DeprecatedUserSearchResult]
  def searchUsersByName(userId: Id[User], query: String, limit: Int = 10, acceptLangs: Seq[String], userExperiments: Set[UserExperimentType]): Future[Seq[UserPrefixSearchHit]]
  def userTypeahead(userId: Id[User], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[Seq[TypeaheadHit[BasicUser]]]
  def userTypeaheadWithUserId(userId: Id[User], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[Seq[TypeaheadHit[TypeaheadUserHit]]]
  def explainUriResult(query: String, userId: Id[User], uriId: Id[NormalizedURI], libraryId: Option[Id[Library]], lang: String, debug: Option[String], disablePrefixSearch: Boolean, disableFullTextSearch: Boolean): Future[Html]
  def explainLibraryResult(query: String, userId: Id[User], libraryId: Id[Library], acceptLangs: Seq[String], debug: Option[String], disablePrefixSearch: Boolean, disableFullTextSearch: Boolean): Future[Html]
  def explainUserResult(query: String, userId: Id[User], resultUserId: Id[User], acceptLangs: Seq[String], debug: Option[String], disablePrefixSearch: Boolean, disableFullTextSearch: Boolean): Future[Html]
  def showUserConfig(id: Id[User]): Future[SearchConfig]
  def setUserConfig(id: Id[User], params: Map[String, String]): Unit
  def resetUserConfig(id: Id[User]): Unit
  def getSearchDefaultConfig: Future[SearchConfig]

  def dumpLuceneDocument(uri: Id[NormalizedURI], deprecated: Boolean): Future[Html]
  def getLibraryDocument(library: DetailedLibraryView): Future[Html]

  def benchmarks(): Future[BenchmarkResults]
  def version(): Future[String]

  def indexInfoList(): Seq[Future[(ServiceInstance, Seq[IndexInfo])]]
  def indexerVersions(): Future[Map[String, Int]]

  def searchMessages(userId: Id[User], query: String, page: Int): Future[Seq[PublicId[Keep]]]

  def augmentation(request: ItemAugmentationRequest): Future[ItemAugmentationResponse]

  def augment(userId: Option[Id[User]], filter: SearchFilter, hideOtherPublishedKeeps: Boolean, maxKeepsShown: Int, maxKeepersShown: Int, maxLibrariesShown: Int, maxTagsShown: Int, items: Seq[AugmentableItem]): Future[Seq[LimitedAugmentationInfo]]

  def call(instance: ServiceInstance, url: ServiceRoute, body: JsValue): Future[ClientResponse]
}

@Singleton
class SearchServiceClientImpl(
  override val serviceCluster: ServiceCluster,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier)
    extends SearchServiceClient() {

  // request consolidation
  private[this] val consolidateSharingUserInfoReq = new RequestConsolidator[(Id[User], Id[NormalizedURI]), SharingUserInfo](ttl = 3 seconds)

  def warmUpUser(userId: Id[User]): Unit = {
    call(Search.internal.warmUpUser(userId))
  }

  def updateKeepIndex(): Unit = {
    debounceBroadcast(Search.internal.updateKeepIndex())
  }

  def updateLibraryIndex(): Unit = {
    debounceBroadcast(Search.internal.updateLibraryIndex())
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
    debounceBroadcast(Search.internal.updateUserIndex())
  }

  def sharingUserInfo(userId: Id[User], uriId: Id[NormalizedURI]): Future[SharingUserInfo] = consolidateSharingUserInfoReq((userId, uriId)) {
    case (userId, uriId) => sharingUserInfo(userId, Seq(uriId)).map(_.head)
  }

  def sharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[SharingUserInfo]] = {
    Future.successful(Seq.empty)
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

  def searchUsers(userId: Option[Id[User]], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[DeprecatedUserSearchResult] = {
    val payload = Json.toJson(DeprecatedUserSearchRequest(userId, query, maxHits, context, filter))
    call(Search.internal.searchUsers(), payload).map { r =>
      Json.fromJson[DeprecatedUserSearchResult](r.json).get
    }
  }

  def searchUsersByName(userId: Id[User], query: String, limit: Int = 10, acceptLangs: Seq[String], userExperiments: Set[UserExperimentType]): Future[Seq[UserPrefixSearchHit]] = {
    Future.successful(Seq.empty)
  }

  def userTypeahead(userId: Id[User], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[Seq[TypeaheadHit[BasicUser]]] = {
    Future.successful(Seq.empty)
  }

  def userTypeaheadWithUserId(userId: Id[User], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[Seq[TypeaheadHit[TypeaheadUserHit]]] = {
    Future.successful(Seq.empty)
  }

  def explainUriResult(query: String, userId: Id[User], uriId: Id[NormalizedURI], libraryId: Option[Id[Library]], lang: String, debug: Option[String], disablePrefixSearch: Boolean, disableFullTextSearch: Boolean): Future[Html] = {
    call(Search.internal.explainUriResult(query, userId, uriId, libraryId, Some(lang), debug, disablePrefixSearch, disableFullTextSearch)).map(r => Html(r.body))
  }

  def explainLibraryResult(query: String, userId: Id[User], libraryId: Id[Library], acceptLangs: Seq[String], debug: Option[String], disablePrefixSearch: Boolean, disableFullTextSearch: Boolean): Future[Html] = {
    call(Search.internal.explainLibraryResult(query, userId, libraryId, acceptLangs, debug, disablePrefixSearch, disableFullTextSearch)).map(r => Html(r.body))
  }

  def explainUserResult(query: String, userId: Id[User], resultUserId: Id[User], acceptLangs: Seq[String], debug: Option[String], disablePrefixSearch: Boolean, disableFullTextSearch: Boolean): Future[Html] = {
    call(Search.internal.explainUserResult(query, userId, resultUserId, acceptLangs, debug, disablePrefixSearch, disableFullTextSearch)).map(r => Html(r.body))
  }

  def dumpLuceneDocument(id: Id[NormalizedURI], deprecated: Boolean): Future[Html] = {
    call(Search.internal.searchDumpLuceneDocument(id, deprecated)).map(r => Html(r.body))
  }

  def getLibraryDocument(library: DetailedLibraryView): Future[Html] = {
    val payload = Json.toJson(library)
    call(Search.internal.getLibraryDocument(), payload).map(r => Html(r.body))
  }

  def benchmarks(): Future[BenchmarkResults] = {
    call(Common.internal.benchmarksResults()).map(r => Json.fromJson[BenchmarkResults](r.json).get)
  }

  def version(): Future[String] = {
    call(Common.internal.version()).map(r => r.body)
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

  def indexInfoList(): Seq[Future[(ServiceInstance, Seq[IndexInfo])]] = {
    val url = Search.internal.indexInfoList()
    val longTimeout = CallTimeouts(responseTimeout = Some(10000), maxWaitTime = Some(1000), maxJsonParseTime = Some(10000))
    serviceCluster.allMembers.collect {
      case instance if instance.isHealthy =>
        callUrl(url, new ServiceUri(instance, protocol, port, url.url), JsNull, true, longTimeout).map { r => (instance, Json.fromJson[Seq[IndexInfo]](r.json).get) }
    }
  }

  def indexerVersions(): Future[Map[String, Int]] = {
    call(Search.internal.versions()).map { r => r.json.as[Map[String, Int]] }
  }

  def updateUserGraph(): Unit = {
    broadcast(Search.internal.updateUserGraph())
  }
  def updateSearchFriendGraph(): Unit = {
    broadcast(Search.internal.updateSearchFriendGraph())
  }
  def reindexUserGraphs(): Unit = {
    broadcast(Search.internal.reindexUserGraphs())
  }

  //the return values here are external id's of threads, a model that is not available here. Need to rethink this a bit. -Stephen
  def searchMessages(userId: Id[User], query: String, page: Int): Future[Seq[PublicId[Keep]]] = {
    Future.successful(Seq.empty[PublicId[Keep]])
  }

  def augmentation(request: ItemAugmentationRequest): Future[ItemAugmentationResponse] = {
    call(Search.internal.augmentation(), Json.toJson(request)).map(_.json.as[ItemAugmentationResponse])
  }

  def augment(userId: Option[Id[User]], filter: SearchFilter, hideOtherPublishedKeeps: Boolean, maxKeepsShown: Int, maxKeepersShown: Int, maxLibrariesShown: Int, maxTagsShown: Int, items: Seq[AugmentableItem]): Future[Seq[LimitedAugmentationInfo]] = {
    Future.successful(Seq.empty[LimitedAugmentationInfo])
  }

  def call(instance: ServiceInstance, url: ServiceRoute, body: JsValue): Future[ClientResponse] = {
    callUrl(url, new ServiceUri(instance, protocol, port, url.url), body)
  }

  private val _debounceBroadcast = {
    def b(c: ServiceRoute) = broadcast(c)
    extras.debounce(3.second)(b)
  }
  private def debounceBroadcast(call: ServiceRoute): Unit = {
    _debounceBroadcast(call)
  }
}
