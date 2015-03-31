package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.search._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import views.html

case class ArticleSearchResultHitMeta(uri: NormalizedURI, hit: ArticleHit)

class AdminSearchController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    articleSearchResultStore: ArticleSearchResultStore,
    uriRepo: NormalizedURIRepo,
    searchClient: SearchServiceClient,
    implicit val publicIdConfig: PublicIdConfiguration) extends AdminUserActions with Logging {

  def explainUriResult(query: String, uriId: Id[NormalizedURI], lang: String, debug: Option[String]) = AdminUserPage.async { request =>
    searchClient.explainUriResult(query, request.userId, uriId, lang, debug).map(Ok(_))
  }

  def explainLibraryResult(query: String, libraryId: PublicId[Library], debug: Option[String], disablePrefixSearch: Boolean) = AdminUserPage.async { request =>
    val acceptLangs = request.acceptLanguages.map(_.code)
    searchClient.explainLibraryResult(query, request.userId, Library.decodePublicId(libraryId).get, acceptLangs, debug, disablePrefixSearch).map(Ok(_))
  }

  def explainUserResult(query: String, userId: Id[User], debug: Option[String], disablePrefixSearch: Boolean) = AdminUserPage.async { request =>
    val acceptLangs = request.acceptLanguages.map(_.code)
    searchClient.explainUserResult(query, request.userId, userId, acceptLangs, debug, disablePrefixSearch).map(Ok(_))
  }

  def articleSearchResult(id: ExternalId[ArticleSearchResult]) = AdminUserPage { implicit request =>

    articleSearchResultStore.syncGet(id) match {
      case Some(result) =>
        val metas = db.readOnlyMaster { implicit s =>
          result.hits map { hit => ArticleSearchResultHitMeta(uriRepo.get(hit.uriId), hit) }
        }
        Ok(html.admin.articleSearchResult(result, metas))
      case None =>
        NotFound("search result not found")
    }
  }
}
