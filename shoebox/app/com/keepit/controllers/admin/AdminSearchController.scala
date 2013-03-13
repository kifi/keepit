package com.keepit.controllers.admin

import play.api.data._
import play.api._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.http.ContentTypes

import play.api.Play.current
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.async._
import com.keepit.model._
import java.sql.Connection
import com.keepit.common.logging.Logging
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.Hit
import com.keepit.search.graph._
import com.keepit.search._
import com.keepit.common.social.UserWithSocial
import com.keepit.search.ArticleSearchResultStore
import com.keepit.common.controller.AdminController

import scala.util.Try
import views.html

import com.google.inject.{Inject, Singleton}

case class ArticleSearchResultHitMeta(uri: NormalizedURI, users: Seq[User], scoring: Scoring, hit: ArticleHit)

@Singleton
class AdminSearchController @Inject() (
  db: Database,
  userRepo: UserRepo,
  socialConnectionRepo: SocialConnectionRepo,
  searchConfigManager: SearchConfigManager,
  mainSearcherFactory: MainSearcherFactory,
  articleSearchResultStore: ArticleSearchResultStore,
  articleSearchResultRefRepo: ArticleSearchResultRefRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  bookmarkRepo: BookmarkRepo,
  uriRepo: NormalizedURIRepo)
    extends AdminController {

  def explain(queryString: String, uriId: Id[NormalizedURI]) = AdminHtmlAction { request =>
    val userId = request.userId
    val friendIds = db.readOnly { implicit s =>
      socialConnectionRepo.getFortyTwoUserConnections(userId)
    }
    val (config, _) = searchConfigManager.getConfig(userId, queryString)

    val searcher = mainSearcherFactory(userId, friendIds, SearchFilter.default(), config)
    val explanation = searcher.explain(queryString, uriId)

    Ok(html.admin.explainResult(queryString, userId, uriId, explanation))
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
