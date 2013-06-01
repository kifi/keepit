package com.keepit.search

import com.keepit.common.healthcheck.BenchmarkResults
import com.keepit.common.healthcheck.BenchmarkResultsJson._
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.db.Id
import com.keepit.common.net.HttpClient
import com.keepit.controllers.search._
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import play.api.libs.json.{JsArray, JsValue, Json, JsString}
import play.api.templates.Html
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import com.keepit.serializer.UriLabelSerializer

trait SearchServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SEARCH

  def logResultClicked(userId: Id[User], query: String, uriId: Id[NormalizedURI], rank: Int, isUserKeep: Boolean): Future[Unit]
  def updateURIGraph(): Future[Int]
  def reindexURIGraph(): Future[Unit]
  def index(): Future[Int]
  def reindex(): Future[Unit]
  def articleIndexInfo(): Future[ArticleIndexInfo]
  def articleIndexerSequenceNumber(): Future[Int]
  def uriGraphIndexInfo(): Future[URIGraphIndexInfo]
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
}

class SearchServiceClientImpl(override val host: String, override val port: Int, override val httpClient: HttpClient)
    extends SearchServiceClient {

  import com.keepit.controllers.search.ArticleIndexInfoJson._
  import com.keepit.controllers.search.ResultClickedJson._
  import com.keepit.controllers.search.URIGraphJson._

  def logResultClicked(userId: Id[User], query: String, uriId: Id[NormalizedURI], rank: Int, isKeep: Boolean): Future[Unit] = {
    val json = Json.toJson(ResultClicked(userId, query, uriId, rank, isKeep))
    call(routes.SearchEventController.logResultClicked(), json).map(_ => Unit)
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
    call(routes.SearchStatisticsController.getSearchStatistics, json).map{
        r => r.json.as[JsArray]
    }
  }

  def updateURIGraph(): Future[Int] = {
    call(routes.URIGraphController.updateURIGraph()).map(r => (r.json \ "users").as[Int])
  }

  def reindexURIGraph(): Future[Unit] = {
    call(routes.URIGraphController.reindex()).map(r => ())
  }

  def index(): Future[Int] = {
    call(routes.ArticleIndexerController.index()).map(r => (r.json \ "articles").as[Int])
  }

  def reindex(): Future[Unit] = {
    call(routes.ArticleIndexerController.reindex()).map(r => ())
  }

  def articleIndexInfo(): Future[ArticleIndexInfo] = {
    call(routes.ArticleIndexerController.indexInfo()).map(r => Json.fromJson[ArticleIndexInfo](r.json).get)
  }

  def uriGraphIndexInfo(): Future[URIGraphIndexInfo] = {
    call(routes.URIGraphController.indexInfo()).map(r => Json.fromJson[URIGraphIndexInfo](r.json).get)
  }

  def sharingUserInfo(userId: Id[User], uriId: Id[NormalizedURI]): Future[SharingUserInfo] = {
    call(routes.URIGraphController.sharingUserInfo(userId, uriId.id.toString)) map { r =>
      Json.fromJson[Seq[SharingUserInfo]](r.json).get.head
    }
  }

  def sharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[SharingUserInfo]] = {
    call(routes.URIGraphController.sharingUserInfo(userId, uriIds.map(_.id).mkString(","))) map { r =>
      Json.fromJson[Seq[SharingUserInfo]](r.json).get
    }
  }

  def articleIndexerSequenceNumber(): Future[Int] = {
    call(routes.ArticleIndexerController.getSequenceNumber()).map(r => (r.json \ "sequenceNumber").as[Int])
  }

  def refreshSearcher(): Future[Unit] = {
    call(routes.ArticleIndexerController.refreshSearcher()).map(_ => Unit)
  }

  def refreshPhrases(): Future[Unit] = {
    call(routes.ArticleIndexerController.refreshPhrases()).map(_ => Unit)
  }

  def searchKeeps(userId: Id[User], query: String): Future[Set[Id[NormalizedURI]]] = {
    call(routes.SearchController.searchKeeps(userId, query)).map {
      _.json.as[Seq[JsValue]].map(v => Id[NormalizedURI](v.as[Long])).toSet
    }
  }

  def explainResult(query: String, userId: Id[User], uriId: Id[NormalizedURI]): Future[Html] = {
    call(routes.SearchController.explain(query, userId, uriId)).map(r => Html(r.body))
  }

  def friendMapJson(userId: Id[User], q: Option[String] = None, minKeeps: Option[Int]): Future[JsArray] = {
    call(routes.SearchController.friendMapJson(userId, q, minKeeps)).map(_.json.as[JsArray])
  }

  def dumpLuceneURIGraph(userId: Id[User]): Future[Html] = {
    call(routes.URIGraphController.dumpLuceneDocument(userId)).map(r => Html(r.body))
  }

  def dumpLuceneDocument(id: Id[NormalizedURI]): Future[Html] = {
    call(routes.ArticleIndexerController.dumpLuceneDocument(id)).map(r => Html(r.body))
  }

  implicit val benchmarksResultsFormat = Json.format[BenchmarkResults]
  def benchmarks(): Future[BenchmarkResults] = {
    call(com.keepit.common.healthcheck.routes.AdminBenchmarkController.benchmarksResults()).map(r => Json.fromJson[BenchmarkResults](r.json).get)
  }

  def buildSpellCorrectorDictionary(): Future[Unit] = {
    call(routes.SpellCorrectorController.buildDictionary()).map(r => ())
  }

  def getSpellCorrectorStatus(): Future[Boolean] = {
    call(routes.SpellCorrectorController.getBuildStatus()).map(r => r.body.toBoolean)
  }

  def correctSpelling(text: String): Future[String] = {
    call(routes.SpellCorrectorController.correctSpelling(text)).map(r => (r.json \ "correction").asOpt[String].getOrElse(text))
  }

  def showUserConfig(id: Id[User]): Future[Html] = {
    call(routes.SearchConfigController.showUserConfig(id)).map(r => Html(r.body))
  }

  def setUserConfig(id: Id[User], params: Map[String, String]): Future[Unit] = {
    call(routes.SearchConfigController.setUserConfig(id), Json.toJson(params)).map(r => ())
  }

  def resetUserConfig(id: Id[User]): Future[Unit] = {
    call(routes.SearchConfigController.resetUserConfig(id)).map(r => ())
  }

  def getSearchDefaultConfig: Future[SearchConfig]  = {
    call(routes.SearchConfigController.getSearchDefaultConfig).map{ r =>
      val param = Json.fromJson[Map[String, String]](r.json).get
      new SearchConfig(param)
    }
  }
}
