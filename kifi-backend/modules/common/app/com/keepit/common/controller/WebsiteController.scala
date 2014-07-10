package com.keepit.common.controller

import com.keepit.common.logging.Logging
import play.api.http.ContentTypes
import play.api.mvc._
import play.api.libs.json._

abstract class WebsiteController(val actionAuthenticator: ActionAuthenticator) extends ServiceController with ActionsBuilder {

  object JsonAction extends Actions.AuthenticatedActions with Actions.NonAuthenticatedActions {
    override val contentTypeOpt = Some(ContentTypes.JSON)
  }
  object HtmlAction extends Actions.AuthenticatedActions with Actions.NonAuthenticatedActions {
    override val contentTypeOpt = Some(ContentTypes.HTML)
    override val apiClient = false
  }
  object AnyAction extends Actions.AuthenticatedActions with Actions.NonAuthenticatedActions

}
