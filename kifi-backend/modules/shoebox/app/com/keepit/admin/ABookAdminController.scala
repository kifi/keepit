package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import com.keepit.common.db.slick.Database
import com.keepit.abook.ABookServiceClient
import play.api.libs.concurrent.Execution.Implicits._


class ABookAdminController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  abookServiceClient: ABookServiceClient)
extends AdminController(actionAuthenticator) {

  def abookInfos = AdminHtmlAction { implicit request =>
    Async {
      abookServiceClient.getAllABookInfos() map { abookInfos =>
        Ok(views.html.admin.abook(abookInfos))
      }
    }
  }

}

