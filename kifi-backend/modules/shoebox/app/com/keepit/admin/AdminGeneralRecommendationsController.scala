package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.curator.CuratorServiceClient
import com.keepit.model._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import views.html

class AdminGeneralRecommendationsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    uriRepo: NormalizedURIRepo,
    db: Database,
    curator: CuratorServiceClient) extends AdminUserActions with Logging {

  def view() = AdminUserAction.async { implicit request =>
    curator.generalRecos() map { recos =>
      val uris = db.readOnlyReplica { implicit session => recos.map(reco => uriRepo.get(reco.uriId)) }
      Ok(html.admin.curator.generalRecos(uris))
    }
  }
}
