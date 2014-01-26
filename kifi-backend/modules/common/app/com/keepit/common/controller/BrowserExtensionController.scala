package com.keepit.common.controller

import play.api.http.ContentTypes

abstract class BrowserExtensionController(val actionAuthenticator: ActionAuthenticator) extends ServiceController with ActionsBuilder {

  object JsonAction extends Actions.AuthenticatedActions with Actions.NonAuthenticatedActions {
    override val contentTypeOpt = Some(ContentTypes.JSON)
  }
  object HtmlAction extends Actions.AuthenticatedActions with Actions.NonAuthenticatedActions {
    override val apiClient = false
  }
  object AnyAction extends Actions.AuthenticatedActions with Actions.NonAuthenticatedActions

}
