package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.curator.CuratorServiceClient
import com.keepit.model.User
import play.api.libs.json.Json
import views.html

import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }

import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits.defaultContext

class AdminLibraryRecommendationsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    curator: CuratorServiceClient) extends AdminUserActions with Logging {

  def index() = AdminUserAction { implicit request =>
    Ok(html.admin.curator.librecos.index())
  }

  def view() = AdminUserAction.async { implicit request =>
    val body = request.body.asFormUrlEncoded.map(_.mapValues(_.head)) getOrElse Map.empty
    val userId = body.get("userId").map(s => Id[User](s.toInt)) getOrElse request.userId
    val recosF = curator.topLibraryRecos(userId)

    recosF map { recos =>
      Ok(Json.toJson(recos))
    }
  }

}
