package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
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
    userRepo: UserRepo,
    articleSearchResultStore: ArticleSearchResultStore,
    uriRepo: NormalizedURIRepo,
    searchConfigRepo: SearchConfigExperimentRepo,
    searchClient: SearchServiceClient,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    heimdal: HeimdalServiceClient) extends AdminUserActions with Logging {

  def explain(query: String, uriId: Id[NormalizedURI], lang: String, debug: Option[String]) = AdminUserPage.async { request =>
    searchClient.explainResult(query, request.userId, uriId, lang, debug).map(Ok(_))
  }

  def articleSearchResult(id: ExternalId[ArticleSearchResult]) = AdminUserPage { implicit request =>

    articleSearchResultStore.get(id) match {
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
