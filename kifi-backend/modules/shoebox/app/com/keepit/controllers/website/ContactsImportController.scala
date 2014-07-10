package com.keepit.controllers.website

import play.api.mvc.Action
import com.keepit.common.controller.{ ActionAuthenticator, WebsiteController, ShoeboxServiceController }
import com.google.inject.Inject

class ContactsImportController @Inject() (actionAuthenticator: ActionAuthenticator)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def importContactsSuccess(redirectUrl: Option[String], numContacts: Option[Int] = None) = Action { implicit request =>
    Ok(views.html.website.importContactsResult(true, redirectUrl, numContacts))
  }

  def importContactsFailure(redirectUrl: Option[String]) = Action { implicit request =>
    Ok(views.html.website.importContactsResult(false, redirectUrl))
  }
}
