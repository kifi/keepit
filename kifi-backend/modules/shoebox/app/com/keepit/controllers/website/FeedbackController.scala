package com.keepit.controllers.website

import com.keepit.common.controller.{ ShoeboxServiceController, WebsiteController, ActionAuthenticator }
import com.keepit.common.logging.Logging
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.http.ContentTypes
import play.api.mvc._
import play.api._
import play.api.libs.json._
import com.keepit.model._
import com.keepit.common.db.slick._
import com.keepit.common.store.S3ImageStore
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject

class FeedbackController @Inject() (actionAuthenticator: ActionAuthenticator)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def feedbackForm = Action {
    Redirect("http://support.kifi.com")
  }

  def feedback = Action {
    Redirect("http://support.kifi.com")
  }
}
