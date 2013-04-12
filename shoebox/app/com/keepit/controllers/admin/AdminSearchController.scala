package com.keepit.controllers.admin

import scala.concurrent.ExecutionContext.Implicits.global

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.search._

import views.html

case class ArticleSearchResultHitMeta(uri: NormalizedURI, users: Seq[User], scoring: Scoring, hit: ArticleHit)

@Singleton
class AdminSearchController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    db: Database,
    userRepo: UserRepo,
    articleSearchResultStore: ArticleSearchResultStore,
    articleSearchResultRefRepo: ArticleSearchResultRefRepo,
    uriRepo: NormalizedURIRepo,
    searchClient: SearchServiceClient
  ) extends AdminController(actionAuthenticator) {

  def explain(query: String, uriId: Id[NormalizedURI]) = AdminHtmlAction { request =>
    Async {
      searchClient.explainResult(query, request.userId, uriId).map(Ok(_))
    }
  }

  def articleSearchResult(id: ExternalId[ArticleSearchResultRef]) = AdminHtmlAction { implicit request =>
    val ref = db.readWrite { implicit s =>
      articleSearchResultRefRepo.get(id)
    }
    val result = articleSearchResultStore.get(ref.externalId).get
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
