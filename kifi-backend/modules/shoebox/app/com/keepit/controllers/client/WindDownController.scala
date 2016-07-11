package com.keepit.controllers.client

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.controller.{ UserActionsHelper, ShoeboxServiceController, UserActions }

@Singleton
class WindDownController @Inject() (
    userExperimentCommander: LocalUserExperimentCommander,
    val userActionsHelper: UserActionsHelper) extends UserActions with ShoeboxServiceController {

  def faq() = MaybeUserAction { request =>
    userExperimentCommander.getBuzzState(request.userIdOpt) match {
      case None => NotFound(views.html.error.notFound(request.path))
      case Some(_) => Ok(views.html.faq())
    }
  }
}
