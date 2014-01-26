package com.keepit.common.controller

import play.api.mvc._
import play.api.http.ContentTypes

abstract class MobileController(val actionAuthenticator: ActionAuthenticator) extends ServiceController with ActionsBuilder {
  object JsonAction extends Actions.AuthenticatedActions with Actions.NonAuthenticatedActions {
    override val contentTypeOpt = Some(ContentTypes.JSON)
  }
}

