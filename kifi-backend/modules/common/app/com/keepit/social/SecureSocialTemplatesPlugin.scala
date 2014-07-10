package com.keepit.social

import net.codingwell.scalaguice.InjectorExtensions._

import com.keepit.FortyTwoGlobal

import play.api.Application
import play.api.data.Form
import play.api.mvc.{ RequestHeader, Request }
import securesocial.controllers.PasswordChange.ChangeInfo
import securesocial.controllers.Registration.RegistrationInfo
import securesocial.controllers.TemplatesPlugin
import securesocial.core.{ SecuredRequest, Identity }
import com.keepit.common.logging.Logging

class SecureSocialTemplatesPlugin(app: Application) extends TemplatesPlugin with Logging {
  lazy val plugin = app.global.asInstanceOf[FortyTwoGlobal].injector.instance[TemplatesPlugin]
  def getLoginPage[A](implicit request: Request[A], form: Form[(String, String)], msg: Option[String]) =
    plugin.getLoginPage
  def getSignUpPage[A](implicit request: Request[A], form: Form[RegistrationInfo], token: String) =
    plugin.getSignUpPage
  def getStartSignUpPage[A](implicit request: Request[A], form: Form[String]) =
    plugin.getStartSignUpPage
  def getResetPasswordPage[A](implicit request: Request[A], form: Form[(String, String)], token: String) =
    plugin.getResetPasswordPage
  def getStartResetPasswordPage[A](implicit request: Request[A], form: Form[String]) =
    plugin.getStartResetPasswordPage
  def getPasswordChangePage[A](implicit request: SecuredRequest[A], form: Form[ChangeInfo]) =
    plugin.getPasswordChangePage
  def getNotAuthorizedPage[A](implicit request: Request[A]) =
    plugin.getNotAuthorizedPage
  def getSignUpEmail(token: String)(implicit request: RequestHeader) =
    plugin.getSignUpEmail(token)
  def getAlreadyRegisteredEmail(user: Identity)(implicit request: RequestHeader) =
    plugin.getAlreadyRegisteredEmail(user)
  def getWelcomeEmail(user: Identity)(implicit request: RequestHeader) =
    plugin.getWelcomeEmail(user)
  def getUnknownEmailNotice()(implicit request: RequestHeader) =
    plugin.getUnknownEmailNotice()
  def getSendPasswordResetEmail(user: Identity, token: String)(implicit request: RequestHeader) =
    plugin.getSendPasswordResetEmail(user, token)
  def getPasswordChangedNoticeEmail(user: Identity)(implicit request: RequestHeader) =
    plugin.getPasswordChangedNoticeEmail(user)
}
