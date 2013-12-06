package com.keepit.search

import com.keepit.common.zookeeper._
import com.keepit.common.healthcheck.BenchmarkResultsJson._
import com.keepit.common.healthcheck.{AirbrakeNotifier, BenchmarkResults}
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.db.Id
import com.keepit.common.net.HttpClient
import com.keepit.model.Collection
import play.api.libs.json.{JsValue, Json}
import play.api.templates.Html
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import com.keepit.common.routes.Search
import com.keepit.common.routes.Common
import scala.concurrent.Promise
import play.api.libs.json.JsArray
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import com.keepit.social.BasicUser
import com.keepit.search.user.UserHit
import com.keepit.search.user.UserSearchResult
import com.keepit.search.user.UserSearchRequest

class FakeSearchServiceClient() extends SearchServiceClientImpl(null, -1, null, null) {

  override def logResultClicked(resultClicked: ResultClicked): Unit = {}

  override def logSearchEnded(searchEnded: SearchEnded): Unit = {}

  override def updateBrowsingHistory(userId: Id[User], uriIds: Id[NormalizedURI]*): Unit = {}

  override def updateURIGraph(): Unit = {}

  override def reindexURIGraph(): Unit = {}

  override def reindexCollection(): Unit = {}

  override def index(): Unit = {}

  override def reindex(): Unit = {}

  override def articleIndexInfo(): Future[IndexInfo] = ???

  override def uriGraphIndexInfo(): Future[Seq[IndexInfo]] = ???

  override def sharingUserInfo(userId: Id[User], uriId: Id[NormalizedURI]): Future[SharingUserInfo] =
    Future.successful(SharingUserInfo(sharingUserIds = Set(Id[User](1)), keepersEdgeSetSize = 1))

  var sharingUserInfoDataFix: Seq[SharingUserInfo] = Seq(SharingUserInfo(Set(Id[User](99)), 1))
  def sharingUserInfoData(data: Seq[SharingUserInfo]): Unit = sharingUserInfoDataFix = data

  override def sharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[SharingUserInfo]] = Future.successful(sharingUserInfoDataFix)

  override def articleIndexerSequenceNumber(): Future[Int] = ???

  override def refreshSearcher(): Unit = {}

  override def refreshPhrases(): Unit = {}

  override def searchKeeps(userId: Id[User], query: String): Future[Set[Id[NormalizedURI]]] = ???

  override def searchUsers(userId: Option[Id[User]], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[UserSearchResult] = ???

  override def explainResult(query: String, userId: Id[User], uriId: Id[NormalizedURI], lang: String): Future[Html] = ???

  override def friendMapJson(userId: Id[User], q: Option[String] = None, minKeeps: Option[Int]): Future[JsArray] = ???

  override def dumpLuceneURIGraph(userId: Id[User]): Future[Html] = ???

  override def dumpLuceneCollection(colId: Id[Collection], userId: Id[User]): Future[Html] = ???

  override def dumpLuceneDocument(id: Id[NormalizedURI]): Future[Html] = ???

  override def benchmarks(): Future[BenchmarkResults] = ???

  override def version(): Future[String] = ???

  override def correctSpelling(text: String, enableBoost: Boolean): Future[String] = ???

  override def showUserConfig(id: Id[User]): Future[SearchConfig] = ???

  override def setUserConfig(id: Id[User], params: Map[String, String]): Unit = {}

  override def resetUserConfig(id: Id[User]): Unit = {}

  override def getSearchDefaultConfig: Future[SearchConfig] = ???

  override def leaveOneOut(queryText: String, stem: Boolean, useSketch: Boolean): Future[Map[String, Float]] = ???

  override def allSubsets(queryText: String, stem: Boolean, useSketch: Boolean): Future[Map[String, Float]] = ???
}
