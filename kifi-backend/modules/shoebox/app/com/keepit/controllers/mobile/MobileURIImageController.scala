package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders.URIImageCommander
import com.keepit.common.controller.{MobileController, ShoeboxServiceController, ActionAuthenticator}
import com.keepit.controllers.RequestSource
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class MobileURIImageController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  uriImageCommander: URIImageCommander) extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  def getImageForURL() = JsonAction.authenticatedParseJsonAsync { request =>
    uriImageCommander.getImageForURL(request.body, RequestSource.MOBILE) map { response =>
      (response \ "error").asOpt[String] match {
        case Some(error) => BadRequest(response)
        case None => Ok(response)
      }
    }
  }
}
