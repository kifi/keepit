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
import com.google.inject.{Inject, Singleton}
import com.keepit.common.db.ExternalId
import com.keepit.controllers.core.UserHelper

@Singleton
class UserPicture @Inject() (
  db: Database,
  userHelper: UserHelper)
    extends WebsiteController {

  /* Usage: /widthxheight/userExternalId
   * /200/9de9a8c4-74aa-43fb-bdd3-f329b4a1c0f6 for a 200x200 square
   * /200x250/9de9a8c4-74aa-43fb-bdd3-f329b4a1c0f6 for a 200x250 square
   */
  def getByExternalId(sizeStr: String, userExternalId: ExternalId[User]) = Action { request =>
    val url = db.readOnly( implicit s => userHelper.getAvatarByUserExternalId(sizeStr, userExternalId))
    Redirect(url)
  }
}
