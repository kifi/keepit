package com.keepit.controllers.website

import com.keepit.common.controller.WebsiteController
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
import com.keepit.common.controller.ActionAuthenticator
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.crypto.UserVoiceTokenGenerator
import com.keepit.common.store.S3ImageStore
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class FeedbackController @Inject() (
  db: Database,
  actionAuthenticator: ActionAuthenticator,
  emailAddressRepo: EmailAddressRepo,
  s3ImageStore: S3ImageStore,
  userVoiceTokenGenerator: UserVoiceTokenGenerator)
  extends WebsiteController(actionAuthenticator) {
  
  //  "{\"trusted\":true,\"guid\":\""+"1"+"\",\"display_name\":\""+"KiFi support"+"\",\"email\":\""+"uservoice@42go.com"+"\",\"avatar_url\":\""+"https://www.kifi.com/assets/images/logo2.png"+"\"}";

  def feedbackForm = HtmlAction(true)(authenticatedAction = { request =>
    val email = db.readOnly(emailAddressRepo.getByUser(request.user.id.get)(_)).last.address
    val picUrlFut = s3ImageStore.getPictureUrl(50, request.user)

    Async {
      picUrlFut map { avatar =>
        val ssoToken = userVoiceTokenGenerator.createSSOToken(request.user.id.get.id.toString, s"${request.user.firstName} ${request.user.lastName}", email, avatar)
        Ok(views.html.website.feedback(Some(ssoToken.value)))
      }
    }
  }, unauthenticatedAction = { request =>
    Ok(views.html.website.feedback(None))
  })

  def feedback = HtmlAction(true)(authenticatedAction = { request =>
    val email = db.readOnly(emailAddressRepo.getByUser(request.user.id.get)(_)).last.address
    val avatarFut = s3ImageStore.getPictureUrl(50, request.user)

    Async {
      avatarFut map { avatar =>
        val ssoToken = userVoiceTokenGenerator.createSSOToken(request.user.id.get.id.toString, s"${request.user.firstName} ${request.user.lastName}", email, avatar)
        Redirect("http://kifi.uservoice.com?sso=" + ssoToken.value)
      }
    }
  }, unauthenticatedAction = { request =>
    Redirect("http://kifi.uservoice.com")
  })
}
