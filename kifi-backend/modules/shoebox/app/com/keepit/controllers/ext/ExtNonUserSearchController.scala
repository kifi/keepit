package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}

import play.api.libs.json.JsArray

class ExtNonUserSearchController @Inject() (
  actionAuthenticator: ActionAuthenticator)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def findPeopleToInvite(q: String, n: Int) = JsonAction.authenticated { request =>
    Ok(JsArray())
  }

}
