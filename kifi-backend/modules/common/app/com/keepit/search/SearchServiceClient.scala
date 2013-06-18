package com.keepit.search

import com.keepit.common.healthcheck.BenchmarkResults
import com.keepit.common.healthcheck.BenchmarkResultsJson._
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.db.Id
import com.keepit.common.net.HttpClient
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import play.api.libs.json.{JsArray, JsValue, Json, JsString}
import play.api.templates.Html
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import com.keepit.serializer.UriLabelSerializer
import com.keepit.common.routes.Search
import com.keepit.common.routes.Common
import com.keepit.common.search.{ResultClicked, SharingUserInfo, IndexInfo}
import scala.concurrent.Promise

trait SearchServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SEARCH

  def logResultClicked(userId: Id[User], query: String, uriId: Id[NormalizedURI], rank: Int, isUserKeep: Boolean): Future[Unit]
  def updateURIGraph(): Future[Int]
  def reindexURIGraph(): Future[Unit]
  def index(): Future[Int]
  def reindex(): Future[Unit]
  def articleIndexInfo(): Future[IndexInfo]
  def articleIndexerSequenceNumber(): Future[Int]
  def uriGraphIndexInfo(): Future[Seq[IndexInfo]]
  def sharingUserInfo(userId: Id[User], uriId: Id[NormalizedURI]): Future[SharingUserInfo]
  def sharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[SharingUserInfo]]
  def refreshSearcher(): Future[Unit]
  def refreshPhrases(): Future[Unit]
  def searchKeeps(userId: Id[User], query: String): Future[Set[Id[NormalizedURI]]]
  def explainResult(query: String, userId: Id[User], uriId: Id[NormalizedURI]): Future[Html]
  def friendMapJson(userId: Id[User], q: Option[String] = None, minKeeps: Option[Int]): Future[JsArray]
  def buildSpellCorrectorDictionary(): Future[Unit]
  def getSpellCorrectorStatus(): Future[Boolean]
  def correctSpelling(text: String): Future[String]
  def showUserConfig(id: Id[User]): Future[Html]
  def setUserConfig(id: Id[User], params: Map[String, String]): Future[Unit]
  def resetUserConfig(id: Id[User]): Future[Unit]
  def getSearchDefaultConfig: Future[SearchConfig]
  def getSearchStatistics(queryUUID: String, queryString: String, userId: Id[User], labeledUris: Map[Id[NormalizedURI], UriLabel]): Future[JsArray]
  def dumpLuceneURIGraph(userId: Id[User]): Future[Html]
  def dumpLuceneDocument(uri: Id[NormalizedURI]): Future[Html]
  def benchmarks(): Future[BenchmarkResults]
  def version(): Future[String]
}

class SearchServiceClientImpl(override val host: String, override val port: Int, override val httpClient: HttpClient)
    extends SearchServiceClient {

  def logResultClicked(userId: Id[User], query: String, uriId: Id[NormalizedURI], rank: Int, isKeep: Boolean): Future[Unit] = {
    val json = Json.toJson(ResultClicked(userId, query, uriId, rank, isKeep))
    call(Search.internal.logResultClicked(), json).map(_ => Unit)
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

  def updateURIGraph(): Future[Int] = {
    call(Search.internal.updateURIGraph()).map(r => (r.json \ "users").as[Int])
  }

  def reindexURIGraph(): Future[Unit] = {
    call(Search.internal.uriGraphReindex()).map(r => ())
  }

  def index(): Future[Int] = {
    call(Search.internal.searchUpdate()).map(r => (r.json \ "articles").as[Int])
  }

  def reindex(): Future[Unit] = {
    call(Search.internal.searchReindex()).map(r => ())
  }

  def articleIndexInfo(): Future[IndexInfo] = {
    call(Search.internal.indexInfo()).map(r => Json.fromJson[IndexInfo](r.json).get)
  }

  def uriGraphIndexInfo(): Future[Seq[IndexInfo]] = {
    call(Search.internal.uriGraphInfo()).map(r => Json.fromJson[Seq[IndexInfo]](r.json).get)
  }

  def sharingUserInfo(userId: Id[User], uriId: Id[NormalizedURI]): Future[SharingUserInfo] = {
    call(Search.internal.sharingUserInfo(userId, uriId.id.toString)) map { r =>
      Json.fromJson[Seq[SharingUserInfo]](r.json).get.head
    }
  }

  def sharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[SharingUserInfo]] = {
    if (uriIds.isEmpty) {
      Promise.successful(Seq[SharingUserInfo]()).future
    } else {
      call(Search.internal.sharingUserInfo(userId, uriIds.map(_.id).mkString(","))) map { r =>
        Json.fromJson[Seq[SharingUserInfo]](r.json).get
      }
    }
  }

  def articleIndexerSequenceNumber(): Future[Int] = {
    call(Search.internal.getSequenceNumber()).map(r => (r.json \ "sequenceNumber").as[Int])
  }

  def refreshSearcher(): Future[Unit] = {
    call(Search.internal.refreshSearcher()).map(_ => Unit)
  }

  def refreshPhrases(): Future[Unit] = {
    call(Search.internal.refreshPhrases()).map(_ => Unit)
  }

  def searchKeeps(userId: Id[User], query: String): Future[Set[Id[NormalizedURI]]] = {
    call(Search.internal.searchKeeps(userId, query)).map {
      _.json.as[Seq[JsValue]].map(v => Id[NormalizedURI](v.as[Long])).toSet
    }
  }

  def explainResult(query: String, userId: Id[User], uriId: Id[NormalizedURI]): Future[Html] = {
    call(Search.internal.explain(query, userId, uriId)).map(r => Html(r.body))
  }

  def friendMapJson(userId: Id[User], q: Option[String] = None, minKeeps: Option[Int]): Future[JsArray] = {
    call(Search.internal.friendMapJson(userId, q, minKeeps)).map(_.json.as[JsArray])
  }

  def dumpLuceneURIGraph(userId: Id[User]): Future[Html] = {
    call(Search.internal.uriGraphDumpLuceneDocument(userId)).map(r => Html(r.body))
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

  def buildSpellCorrectorDictionary(): Future[Unit] = {
    call(Search.internal.buildDictionary()).map(r => ())
  }

  def getSpellCorrectorStatus(): Future[Boolean] = {
    call(Search.internal.getBuildStatus()).map(r => r.body.toBoolean)
  }

  def correctSpelling(text: String): Future[String] = {
    call(Search.internal.correctSpelling(text)).map(r => (r.json \ "correction").asOpt[String].getOrElse(text))
  }

  def showUserConfig(id: Id[User]): Future[Html] = {
    call(Search.internal.showUserConfig(id)).map(r => Html(r.body))
  }

  def setUserConfig(id: Id[User], params: Map[String, String]): Future[Unit] = {
    call(Search.internal.setUserConfig(id), Json.toJson(params)).map(r => ())
  }

  def resetUserConfig(id: Id[User]): Future[Unit] = {
    call(Search.internal.resetUserConfig(id)).map(r => ())
  }

  def getSearchDefaultConfig: Future[SearchConfig]  = {
    call(Search.internal.getSearchDefaultConfig).map{ r =>
      val param = Json.fromJson[Map[String, String]](r.json).get
      new SearchConfig(param)
    }
  }
}
