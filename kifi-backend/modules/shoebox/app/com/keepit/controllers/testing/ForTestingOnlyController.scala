package com.keepit.controllers.testing

import com.google.inject.Inject
import com.keepit.common.controller._
import com.keepit.common.db.slick.Database
import com.keepit.model.UserRepo

import play.api.http.ContentTypes.TEXT
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

class ForTestingOnlyController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  userRepo: UserRepo)
    extends UserActions with ShoeboxServiceController {

  def me = UserAction { request =>
    val user = db.readOnlyMaster(implicit s => userRepo.get(request.userId))
    Ok(user.externalId.toString)
  }

}
