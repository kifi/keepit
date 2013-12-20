package com.keepit.controllers.admin

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.search._
import views.html
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.json._


case class ArticleSearchResultHitMeta(uri: NormalizedURI, users: Seq[User], scoring: Scoring, hit: ArticleHit)

class AdminSearchController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    db: Database,
    userRepo: UserRepo,
    articleSearchResultStore: ArticleSearchResultStore,
    uriRepo: NormalizedURIRepo,
    searchClient: SearchServiceClient
  ) extends AdminController(actionAuthenticator) {

  def explain(query: String, uriId: Id[NormalizedURI], lang: String) = AdminHtmlAction { request =>
    Async {
      searchClient.explainResult(query, request.userId, uriId, lang).map(Ok(_))
    }
  }

  def blindTest() = AdminHtmlAction { request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val userId = body.get("userId").get.toLong
    val query = body.get("query").get
    val maxHits = body.get("maxHits").get.toInt
    val config = Await.result(searchClient.getSearchDefaultConfig, 1 second)
    val res = Await.result(searchClient.searchWithConfig(Id[User](userId), query, maxHits, config), 1 second)
    Ok(JsArray(res.zipWithIndex.map{ case ((uriId, title, url), idx) => Json.obj("index" -> (idx + 1) , "title" -> title, "url" -> url) }))
  }

  def blindTestPage() = AdminHtmlAction { request =>
    Ok(html.admin.adminSearchBlindTest())
  }

  def articleSearchResult(id: ExternalId[ArticleSearchResult]) = AdminHtmlAction { implicit request =>

    val result = articleSearchResultStore.get(id).get
    val metas: Seq[ArticleSearchResultHitMeta] = db.readOnly { implicit s =>
      result.hits.zip(result.scorings) map { tuple =>
        val hit = tuple._1
        val scoring = tuple._2
        val uri = uriRepo.get(hit.uriId)
        val users = hit.users.map { userId =>
          userRepo.get(userId)
        }
        ArticleSearchResultHitMeta(uri, users.toSeq, scoring, hit)
      }
    }
    Ok(html.admin.articleSearchResult(result, metas))
  }
}
