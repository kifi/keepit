package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.commanders.URIImageCommander
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.controllers.RequestSource
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class ExtURIImageController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  uriImageCommander: URIImageCommander) extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def getImageForURL() = JsonAction.authenticatedParseJsonAsync { request =>
    uriImageCommander.getImageForURL(request.body, RequestSource.EXTENSION) map { response =>
      (response \ "error").asOpt[String] match {
        case Some(error) => BadRequest(response)
        case None => Ok(response)
      }
    }
  }
}
