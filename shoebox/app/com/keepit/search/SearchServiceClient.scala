package com.keepit.search

import com.keepit.common.controller.{ServiceClient, ServiceType}
import com.keepit.common.db.Id
import com.keepit.common.net.HttpClient
import com.keepit.controllers.search._
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import play.api.libs.json.{JsValue, Json}
import play.api.templates.Html
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait SearchServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SEARCH

  def logResultClicked(userId: Id[User], query: String, uriId: Id[NormalizedURI], isUserKeep: Boolean): Future[Unit]
  def updateURIGraph(): Future[Int]
  def index(): Future[Int]
  def articleIndexInfo(): Future[ArticleIndexInfo]
  def articleIndexerSequenceNumber(): Future[Int]
  def uriGraphIndexInfo(): Future[URIGraphIndexInfo]
  def sharingUserInfo(userId: Id[User], uriId: Id[NormalizedURI]): Future[SharingUserInfo]
  def refreshSearcher(): Future[Unit]
  def searchKeeps(userId: Id[User], query: String): Future[Set[Id[NormalizedURI]]]

  def dumpLuceneURIGraph(userId: Id[User]): Future[Html]
  def dumpLuceneDocument(uri: Id[NormalizedURI]): Future[Html]
}
class SearchServiceClientImpl(override val host: String, override val port: Int, override val httpClient: HttpClient)
    extends SearchServiceClient {

  import com.keepit.controllers.search.ArticleIndexInfoJson._
  import com.keepit.controllers.search.ResultClickedJson._
  import com.keepit.controllers.search.URIGraphJson._

  def logResultClicked(userId: Id[User], query: String, uriId: Id[NormalizedURI], isKeep: Boolean): Future[Unit] = {
    val json = Json.toJson(ResultClicked(userId, query, uriId, isKeep))
    call(routes.SearchEventController.logResultClicked(), json).map(_ => Unit)
  }

  def updateURIGraph(): Future[Int] = {
    call(routes.URIGraphController.updateURIGraph()).map(r => (r.json \ "users").as[Int])
  }

  def index(): Future[Int] = {
    call(routes.ArticleIndexerController.index()).map(r => (r.json \ "articles").as[Int])
  }

  def articleIndexInfo(): Future[ArticleIndexInfo] = {
    call(routes.ArticleIndexerController.indexInfo()).map(r => Json.fromJson[ArticleIndexInfo](r.json).get)
  }

  def uriGraphIndexInfo(): Future[URIGraphIndexInfo] = {
    call(routes.URIGraphController.indexInfo()).map(r => Json.fromJson[URIGraphIndexInfo](r.json).get)
  }

  def sharingUserInfo(userId: Id[User], uriId: Id[NormalizedURI]): Future[SharingUserInfo] = {
    call(routes.URIGraphController.sharingUserInfo(userId, uriId)).map(r => Json.fromJson[SharingUserInfo](r.json).get)
  }

  def articleIndexerSequenceNumber(): Future[Int] = {
    call(routes.ArticleIndexerController.getSequenceNumber()).map(r => (r.json \ "sequenceNumber").as[Int])
  }

  def refreshSearcher(): Future[Unit] = {
    call(routes.ArticleIndexerController.refreshSearcher()).map(_ => Unit)
  }

  def searchKeeps(userId: Id[User], query: String): Future[Set[Id[NormalizedURI]]] = {
    call(routes.SearchController.searchKeeps(userId, query)).map {
      _.json.as[Seq[JsValue]].map(v => Id[NormalizedURI](v.as[Long])).toSet
    }
  }

  def dumpLuceneURIGraph(userId: Id[User]): Future[Html] = {
    call(routes.URIGraphController.dumpLuceneDocument(userId)).map(r => Html(r.body))
  }

  def dumpLuceneDocument(id: Id[NormalizedURI]): Future[Html] = {
    call(routes.ArticleIndexerController.dumpLuceneDocument(id)).map(r => Html(r.body))
  }
}
