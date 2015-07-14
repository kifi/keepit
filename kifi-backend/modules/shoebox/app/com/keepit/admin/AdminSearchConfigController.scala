package com.keepit.controllers.admin

import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.search._

import play.api.libs.json._
import views.html
import com.keepit.heimdal.{ UserEvent, HeimdalServiceClient }
import scala.Predef._
import com.keepit.commanders.LocalUserExperimentCommander

class AdminSearchConfigController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    userRepo: UserRepo,
    searchConfigExperimentRepo: SearchConfigExperimentRepo,
    userExperimentCommander: LocalUserExperimentCommander,
    searchClient: SearchServiceClient,
    heimdal: HeimdalServiceClient) extends AdminUserActions {

  def showUserConfig(userId: Id[User]) = AdminUserPage { implicit request =>
    val searchConfigFuture = searchClient.showUserConfig(userId)
    val user = db.readOnlyMaster { implicit s => userRepo.get(userId) }
    val searchConfig = Await.result(searchConfigFuture, 5 seconds)
    Ok(views.html.admin.searchConfig(user, searchConfig.iterator.toSeq.sortBy(_._1)))
  }

  def setUserConfig(userId: Id[User]) = AdminUserPage { implicit request =>
    val form = request.request.body.asFormUrlEncoded match {
      case Some(req) => req.map(r => (r._1 -> r._2.head))
      case None => throw new Exception("whoops")
    }
    searchClient.setUserConfig(userId, form)
    Redirect(com.keepit.controllers.admin.routes.AdminSearchConfigController.showUserConfig(userId))
  }

  def resetUserConfig(userId: Id[User]) = AdminUserPage { implicit request =>
    searchClient.resetUserConfig(userId)
    Redirect(com.keepit.controllers.admin.routes.AdminSearchConfigController.showUserConfig(userId))
  }

}
