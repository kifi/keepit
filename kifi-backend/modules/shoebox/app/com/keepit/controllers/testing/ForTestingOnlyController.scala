package com.keepit.controllers.testing

import com.google.inject.Inject
import com.keepit.common.controller.{ ShoeboxServiceController, ActionsBuilder, ActionAuthenticator }
import com.keepit.common.db.slick.Database
import com.keepit.model.UserRepo

import play.api.http.ContentTypes.TEXT
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

class ForTestingOnlyController @Inject() (
  val actionAuthenticator: ActionAuthenticator,
  db: Database,
  userRepo: UserRepo)
    extends ShoeboxServiceController with ActionsBuilder {

  object PlainTextAction extends Actions.AuthenticatedActions with Actions.NonAuthenticatedActions {
    override val contentTypeOpt = Some(TEXT)
  }

  def me = PlainTextAction.authenticated { request =>
    val user = db.readOnlyMaster(implicit s => userRepo.get(request.userId))
    Ok(user.externalId.toString)
  }

}
