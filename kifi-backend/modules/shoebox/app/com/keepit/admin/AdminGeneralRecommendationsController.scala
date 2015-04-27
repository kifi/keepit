package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.curator.CuratorServiceClient
import com.keepit.model._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import views.html
import play.api.libs.json._

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

class AdminCuratorController @Inject() (
    val userActionsHelper: UserActionsHelper,
    curator: CuratorServiceClient) extends AdminUserActions {

  def view() = AdminUserAction { implicit request =>
    Ok(html.admin.curator.feedback())
  }

  def examineUserFeedbackCounter() = AdminUserAction.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val userId = body.get("userId").get.trim().toLong
    curator.examineUserFeedbackCounter(Id[User](userId)).map {
      case (votes, signals) =>
        val msg = "format: (socialBucket, topicBucket, ups, downs)\n" + "votes:\n" + votes.mkString("\n") + "\nsignals:\n" + signals.mkString("\n")
        Ok(msg.replaceAll("\n", "\n<br>"))
    }
  }
}
