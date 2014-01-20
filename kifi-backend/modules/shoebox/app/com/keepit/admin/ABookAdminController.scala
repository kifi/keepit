package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import com.keepit.common.db.slick.Database
import com.keepit.abook.ABookServiceClient
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.time._
import com.keepit.model.ABookInfo
import com.keepit.common.db.Id
import org.joda.time.DateTime


class ABookAdminController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  abookServiceClient: ABookServiceClient)
extends AdminController(actionAuthenticator) {

  // temporary; see abooksView
  def abookInfos = AdminHtmlAction { implicit request =>
    Async {
      abookServiceClient.getAllABookInfos() map { abookInfos =>
        Ok(views.html.admin.abook(abookInfos sortBy (_.createdAt) reverse))
      }
    }
  }

  def abooksView(page:Int, size:Int = 50) = AdminHtmlAction { implicit request =>
    Async {
      abookServiceClient.getPagedABookInfos(page, size) map { abookInfos =>
        Ok(views.html.admin.abook(abookInfos))
      }
    }
  }

}

