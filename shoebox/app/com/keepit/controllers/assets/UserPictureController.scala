package com.keepit.controllers.assets

import com.keepit.common.controller.WebsiteController
import com.keepit.common.logging.Logging
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.http.ContentTypes
import play.api.mvc._
import play.api._
import com.keepit.model._
import com.keepit.common.db.slick._
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.ExternalId
import com.keepit.controllers.core.UserHelper

@Singleton
class UserPictureController @Inject() (
  db: Database,
  userHelper: UserHelper)
  extends WebsiteController {

  def get(width: Int, userExternalId: ExternalId[User]) = Action { request =>
    val url = db.readOnly(implicit s => userHelper.getAvatarByUserExternalId(width, userExternalId))
    Redirect(url)
  }
}
