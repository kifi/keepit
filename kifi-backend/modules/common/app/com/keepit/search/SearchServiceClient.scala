package com.keepit.search

import com.keepit.common.zookeeper._
import com.keepit.common.healthcheck.BenchmarkResultsJson._
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.db.Id
import com.keepit.common.net.HttpClient
import com.keepit.model.Comment
import com.keepit.model.Collection
import play.api.libs.json.{JsValue, Json}
import play.api.templates.Html
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import com.keepit.serializer.UriLabelSerializer
import com.keepit.common.routes.Search
import com.keepit.common.routes.Common
import scala.concurrent.Promise
import com.keepit.common.healthcheck.{HealthcheckPlugin, BenchmarkResults}
import play.api.libs.json.JsArray
import com.keepit.model.NormalizedURI
import com.keepit.model.User

trait SearchServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SEARCH

  def logResultClicked(userId: Id[User], query: String, uriId: Id[NormalizedURI], rank: Int, isUserKeep: Boolean): Unit

  def updateURIGraph(): Unit
  def reindexURIGraph(): Unit
  def reindexCollection(): Unit
  def uriGraphIndexInfo(): Future[Seq[IndexInfo]]

  def index(): Unit
  def reindex(): Unit
  def articleIndexInfo(): Future[IndexInfo]
  def articleIndexerSequenceNumber(): Future[Int]

  def commentIndexInfo(): Future[Seq[IndexInfo]]
  def reindexComment(): Unit

  def sharingUserInfo(userId: Id[User], uriId: Id[NormalizedURI]): Future[SharingUserInfo]
  def sharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[SharingUserInfo]]
  def refreshSearcher(): Unit
  def refreshPhrases(): Unit
  def searchKeeps(userId: Id[User], query: String): Future[Set[Id[NormalizedURI]]]
  def explainResult(query: String, userId: Id[User], uriId: Id[NormalizedURI], lang: String): Future[Html]
  def friendMapJson(userId: Id[User], q: Option[String] = None, minKeeps: Option[Int]): Future[JsArray]
  def buildSpellCorrectorDictionary(): Unit
  def getSpellCorrectorStatus(): Future[Boolean]
  def correctSpelling(text: String): Future[String]
  def showUserConfig(id: Id[User]): Future[SearchConfig]
  def setUserConfig(id: Id[User], params: Map[String, String]): Unit
  def resetUserConfig(id: Id[User]): Unit
  def getSearchDefaultConfig: Future[SearchConfig]
  def getSearchStatistics(queryUUID: String, queryString: String, userId: Id[User], labeledUris: Map[Id[NormalizedURI], UriLabel]): Future[JsArray]

  def dumpLuceneURIGraph(userId: Id[User]): Future[Html]
  def dumpLuceneCollection(colId: Id[Collection], userId: Id[User]): Future[Html]
  def dumpLuceneComment(commentId: Id[Comment]): Future[Html]
  def dumpLuceneDocument(uri: Id[NormalizedURI]): Future[Html]

  def benchmarks(): Future[BenchmarkResults]
  def version(): Future[String]
}



class SearchServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val port: Int,
    override val httpClient: HttpClient,
    val healthcheck: HealthcheckPlugin)
  extends SearchServiceClient() {

  def logResultClicked(userId: Id[User], query: String, uriId: Id[NormalizedURI], rank: Int, isKeep: Boolean): Unit = {
    val json = Json.toJson(ResultClicked(userId, query, uriId, rank, isKeep))
    call(Search.internal.logResultClicked(), json)
  }

  def getSearchStatistics(queryUUID: String, queryString: String, userId: Id[User], labeledUris: Map[Id[NormalizedURI], UriLabel]): Future[JsArray] = {
    val uriIds = labeledUris.map(_._1).map(id => id.id)
    val uriLabels = labeledUris.map(_._2).map{label => UriLabelSerializer.serializer.writes(label)}
    val json = Json.obj(
        "queryUUID" -> queryUUID,
        "queryString" -> queryString,
        "userId" -> userId.id,
        "uriIds" -> uriIds,
        "uriLabels" -> uriLabels
        )
    call(Search.internal.getSearchStatistics, json).map{
        r => r.json.as[JsArray]
    }
  }

  def updateURIGraph(): Unit = {
    broadcast(Search.internal.updateURIGraph())
  }

  def reindexURIGraph(): Unit = {
    broadcast(Search.internal.uriGraphReindex())
  }

  def reindexCollection(): Unit = {
    broadcast(Search.internal.collectionReindex())
  }

  def reindexComment(): Unit = {
    broadcast(Search.internal.commentReindex())
  }

  def index(): Unit = {
    broadcast(Search.internal.searchUpdate())
  }

  def reindex(): Unit = {
    broadcast(Search.internal.searchReindex())
  }

  def articleIndexInfo(): Future[IndexInfo] = {
    call(Search.internal.indexInfo()).map(r => Json.fromJson[IndexInfo](r.json).get)
  }

  def commentIndexInfo(): Future[Seq[IndexInfo]] = {
    call(Search.internal.commentIndexInfo()).map(r => Json.fromJson[Seq[IndexInfo]](r.json).get)
  }

  def uriGraphIndexInfo(): Future[Seq[IndexInfo]] = {
    call(Search.internal.uriGraphInfo()).map(r => Json.fromJson[Seq[IndexInfo]](r.json).get)
  }

  def sharingUserInfo(userId: Id[User], uriId: Id[NormalizedURI]): Future[SharingUserInfo] = {
    call(Search.internal.sharingUserInfo(userId), Json.toJson(Seq(uriId.id))) map { r =>
      Json.fromJson[Seq[SharingUserInfo]](r.json).get.head
    }
  }

  def sharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[SharingUserInfo]] = {
    if (uriIds.isEmpty) {
      Promise.successful(Seq[SharingUserInfo]()).future
    } else {
      call(Search.internal.sharingUserInfo(userId), Json.toJson(uriIds.map(_.id))) map { r =>
        Json.fromJson[Seq[SharingUserInfo]](r.json).get
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

  def searchKeeps(userId: Id[User], query: String): Future[Set[Id[NormalizedURI]]] = {
    call(Search.internal.searchKeeps(userId, query)).map {
      _.json.as[Seq[JsValue]].map(v => Id[NormalizedURI](v.as[Long])).toSet
    }
  }

  def explainResult(query: String, userId: Id[User], uriId: Id[NormalizedURI], lang: String): Future[Html] = {
    call(Search.internal.explain(query, userId, uriId, lang)).map(r => Html(r.body))
  }

  def friendMapJson(userId: Id[User], q: Option[String] = None, minKeeps: Option[Int]): Future[JsArray] = {
    call(Search.internal.friendMapJson(userId, q, minKeeps)).map(_.json.as[JsArray])
  }

  def dumpLuceneURIGraph(userId: Id[User]): Future[Html] = {
    call(Search.internal.uriGraphDumpLuceneDocument(userId)).map(r => Html(r.body))
  }

  def dumpLuceneCollection(colId: Id[Collection], userId: Id[User]): Future[Html] = {
    call(Search.internal.collectionDumpLuceneDocument(colId, userId)).map(r => Html(r.body))
  }

  def dumpLuceneComment(commentId: Id[Comment]): Future[Html] = {
    call(Search.internal.commentDumpLuceneDocument(commentId)).map(r => Html(r.body))
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

  def buildSpellCorrectorDictionary(): Unit = {
    broadcast(Search.internal.buildDictionary())
  }

  def getSpellCorrectorStatus(): Future[Boolean] = {
    call(Search.internal.getBuildStatus()).map(r => r.body.toBoolean)
  }

  def correctSpelling(text: String): Future[String] = {
    call(Search.internal.correctSpelling(text)).map(r => (r.json \ "correction").asOpt[String].getOrElse(text))
  }

  def showUserConfig(id: Id[User]): Future[SearchConfig] = {
    call(Search.internal.showUserConfig(id)).map{ r =>
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

  def getSearchDefaultConfig: Future[SearchConfig]  = {
    call(Search.internal.getSearchDefaultConfig).map{ r =>
      val param = Json.fromJson[Map[String, String]](r.json).get
      new SearchConfig(param)
    }
  }
}
