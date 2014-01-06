package com.keepit.controllers.core

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator.MaybeAuthenticatedRequest
import com.keepit.common.controller.{AuthenticatedRequest, WebsiteController, ActionAuthenticator}
import com.keepit.common.controller.ActionAuthenticator.FORTYTWO_USER_ID
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.social.SocialNetworkType

import play.api.Play._
import play.api.libs.json.{JsNumber, Json}
import play.api.mvc._
import securesocial.controllers.ProviderController
import securesocial.core._
import play.api.libs.iteratee.Enumerator
import play.api.Play
import com.keepit.common.store.{S3UserPictureConfig, S3ImageStore}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.commanders.InviteCommander
import com.keepit.common.net.UserAgent

object AuthController {
  val LinkWithKey = "linkWith"

  private lazy val obscureRegex = """^(?:[^@]|([^@])[^@]+)@""".r
  def obscureEmailAddress(address: String) = obscureRegex.replaceFirstIn(address, """$1...@""")
}

class AuthController @Inject() (
    db: Database,
    clock: Clock,
    authHelper: AuthHelper,
    userCredRepo: UserCredRepo,
    socialRepo: SocialUserInfoRepo,
    actionAuthenticator: ActionAuthenticator,
    userRepo: UserRepo,
    postOffice: LocalPostOffice,
    userValueRepo: UserValueRepo,
    s3ImageStore: S3ImageStore,
    airbrakeNotifier: AirbrakeNotifier,
    emailAddressRepo: EmailAddressRepo,
    inviteCommander: InviteCommander,
    passwordResetRepo: PasswordResetRepo,
    kifiInstallationRepo: KifiInstallationRepo
  ) extends WebsiteController(actionAuthenticator) with Logging {

  private val PopupKey = "popup"

  // Note: some of the below code is taken from ProviderController in SecureSocial
  // Logout is still handled by SecureSocial directly.

  def loginSocial(provider: String) = ProviderController.authenticate(provider)
  def logInWithUserPass(link: String) = Action { implicit request =>
    ProviderController.authenticate("userpass")(request) match {
      case res: SimpleResult[_] if res.header.status == 303 =>
        authHelper.authHandler(request, res) { (cookies:Seq[Cookie], sess:Session) =>
          val newSession = if (link != "") {
            sess - SecureSocial.OriginalUrlKey + (AuthController.LinkWithKey -> link) // removal of OriginalUrlKey might be redundant
          } else sess
          Ok(Json.obj("uri" -> res.header.headers.get(LOCATION).get)).withCookies(cookies: _*).withSession(newSession)
        }
      case res => res
    }
  }

  def afterLogin() = HtmlAction(allowPending = true)(authenticatedAction = { implicit request =>
    if (request.user.state == UserStates.PENDING) {
      inviteCommander.markPendingInvitesAsAccepted(request.user.id.get, request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value)))
      Redirect("/")
    } else if (request.user.state == UserStates.INCOMPLETE_SIGNUP) {
      Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
    } else if (request.kifiInstallationId.isEmpty && !hasSeenInstall) {
      inviteCommander.markPendingInvitesAsAccepted(request.user.id.get, request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value)))
      Redirect(com.keepit.controllers.website.routes.HomeController.install())
    } else {
      session.get(SecureSocial.OriginalUrlKey) map { url =>
        Redirect(url).withSession(session - SecureSocial.OriginalUrlKey)
      } getOrElse {
        Redirect("/")
      }
    }
  }, unauthenticatedAction = { implicit request =>
    if (request.identityOpt.isDefined) {
      // User tried to log in (not sign up) with social network.
      request.identityOpt.get.email.flatMap(e => db.readOnly(emailAddressRepo.getByAddressOpt(e)(_))) match {
        case Some(addr) =>
          // A user with this email address exists in the system, but it is not yet linked to this social identity.
          Ok(views.html.auth.connectToAuthenticate(
            emailAddress = request.identityOpt.get.email.get,
            network = SocialNetworkType(request.identityOpt.get.identityId.providerId),
            logInAttempted = true
          ))
        case None =>
          // No email for this user exists in the system.
          Redirect("/signup")
      }
    } else {
      Redirect("/") // error??
      // Ok(views.html.website.welcome(msg = request.flash.get("error")))
    }
  })

  def signup(provider: String) = Action { implicit request =>
    ProviderController.authenticate(provider)(request) match {
      case res: SimpleResult[_] =>
        authHelper.authHandler(request, res) { (_, sess:Session) =>
          // TODO: set FORTYTWO_USER_ID instead of clearing it and then setting it on the next request?
          res.withSession(sess - FORTYTWO_USER_ID + (SecureSocial.OriginalUrlKey -> routes.AuthController.signupPage().url))
        }
      case res => res
    }
  }

  def link(provider: String) = Action { implicit request =>
    ProviderController.authenticate(provider)(request) match {
      case res: SimpleResult[_] =>
        val resCookies = res.header.headers.get(SET_COOKIE).map(Cookies.decode).getOrElse(Seq.empty)
        val resSession = Session.decodeFromCookie(resCookies.find(_.name == Session.COOKIE_NAME))
        if (resSession.get(PopupKey).isDefined) {
          res.withSession(resSession + (SecureSocial.OriginalUrlKey -> routes.AuthController.popupAfterLinkSocial(provider).url))
        } else if (resSession.get(SecureSocial.OriginalUrlKey).isEmpty) {
          request.headers.get(REFERER).map { url =>
            res.withSession(resSession + (SecureSocial.OriginalUrlKey -> url))
          } getOrElse res
        } else res
      case res => res
    }
  }

  def popupBeforeLinkSocial(provider: String) = AuthenticatedHtmlAction(allowPending = true) { implicit request =>
    Ok(views.html.auth.popupBeforeLinkSocial(SocialNetworkType(provider))).withSession(session + (PopupKey -> "1"))
  }

  def popupAfterLinkSocial(provider: String) = AuthenticatedHtmlAction(allowPending = true) { implicit request =>
    def esc(s: String) = s.replaceAll("'", """\\'""")
    val identity = request.identityOpt.get
    Ok(s"<script>try{window.opener.afterSocialLink('${esc(identity.firstName)}','${esc(identity.lastName)}','${esc(identityPicture(identity))}')}finally{window.close()}</script>")
      .withSession(session - PopupKey)
  }

  // --
  // Utility methods
  // --

  private def hasSeenInstall(implicit request: AuthenticatedRequest[_]): Boolean = {
    db.readOnly { implicit s => userValueRepo.getValue(request.userId, "has_seen_install").exists(_.toBoolean) }
  }

  def loginPage() = HtmlAction(allowPending = true)(authenticatedAction = { request =>
    Redirect("/")
  }, unauthenticatedAction = { request =>
    request.request.headers.get(USER_AGENT) map { agentString =>
      val agent = UserAgent.fromString(agentString)
      log.info(s"trying to log in via $agent. orig string: $agentString")
      if (agent.isMobile) {
        log.info(s"placeholder for redirecting to mobile only page")
      } else if (!agent.isSupportedDesktop) {
        log.info(s"placeholder for redirecting to unsupported browsers page")
      }
    }
    Ok(views.html.auth.auth("login"))
  })

  // Finalize account
  def signupPage() = HtmlAction(allowPending = true)(authenticatedAction = doSignupPage(_), unauthenticatedAction = doSignupPage(_))


  // Initial user/pass signup JSON action
  def userPasswordSignup() = JsonToJsonAction(allowPending = true)(
    authenticatedAction = authHelper.userPasswordSignupAction(_),
    unauthenticatedAction = authHelper.userPasswordSignupAction(_)
  )

  private def doSignupPage(implicit request: Request[_]): Result = {
    def emailAddressMatchesSomeKifiUser(identity: Identity): Boolean = {
      identity.email.flatMap { addr =>
        db.readOnly { implicit s =>
          emailAddressRepo.getByAddressOpt(addr)
        }
      }.isDefined
    }

    (request.userOpt, request.identityOpt) match {
      case (Some(user), _) if user.state != UserStates.INCOMPLETE_SIGNUP =>
        // Complete user, they don't need to be here!
        Redirect(s"${com.keepit.controllers.website.routes.HomeController.home.url}?m=0")
      case (Some(user), Some(identity)) =>
        // User exists, is incomplete
        val (firstName, lastName) = if (identity.firstName.contains("@")) ("","") else (User.sanitizeName(identity.firstName), User.sanitizeName(identity.lastName))
        val picture = identityPicture(identity)
        Ok(views.html.auth.auth(
          view = "signup2Email",
          emailAddress = identity.email.getOrElse(""),
          picturePath = picture,
          firstName = firstName,
          lastName = lastName
        ))
      case (Some(user), None) =>
        // User but no identity. Huh?
        // Haven't run into this one. Redirecting user to logout, ideally to fix their cookie situation
        Redirect(securesocial.controllers.routes.LoginPage.logout)
      case (None, Some(identity)) if emailAddressMatchesSomeKifiUser(identity) =>
        // No user exists, but social network identity’s email address matches a Kifi user
        Ok(views.html.auth.connectToAuthenticate(
          emailAddress = identity.email.get,
          network = SocialNetworkType(identity.identityId.providerId),
          logInAttempted = false
        ))
      case (None, Some(identity)) if request.flash.get("signin_error").exists(_ == "no_account") =>
        // No user exists, social login was attempted. Let user choose what to do next.
        Ok(views.html.auth.loggedInWithWrongNetwork(
          network = SocialNetworkType(identity.identityId.providerId)
        ))
      case (None, Some(identity)) =>
        // No user exists, has social network identity, must finalize
        Ok(views.html.auth.auth(
          view = "signup2Social",
          firstName = User.sanitizeName(identity.firstName),
          lastName = User.sanitizeName(identity.lastName),
          emailAddress = identity.email.getOrElse(""),
          picturePath = identityPicture(identity),
          network = Some(SocialNetworkType(identity.identityId.providerId))
        ))
      case (None, None) =>
        Ok(views.html.auth.auth("signup"))
    }
  }

  private def identityPicture(identity: Identity) = {
    identity.identityId.providerId match {
      case "facebook" =>
        s"//graph.facebook.com/${identity.identityId.userId}/picture?width=200&height=200"
      case _ => identity.avatarUrl.getOrElse(S3UserPictureConfig.defaultImage)
    }
  }

  def userPassFinalizeAccountAction() = JsonToJsonAction(allowPending = true)(
    authenticatedAction = authHelper.doUserPassFinalizeAccountAction(_),
    unauthenticatedAction = _ => Forbidden(JsNumber(0))
  )

  def socialFinalizeAccountAction() = JsonToJsonAction(allowPending = true)(
    authenticatedAction = authHelper.doSocialFinalizeAccountAction(_),
    unauthenticatedAction = authHelper.doSocialFinalizeAccountAction(_)
  )

  def OkStreamFile(filename: String) =
    Ok.stream(Enumerator.fromStream(Play.resourceAsStream(filename).get)) as HTML

  def verifyEmail(code: String) = HtmlAction(allowPending = true)(authenticatedAction = authHelper.doVerifyEmail(code)(_), unauthenticatedAction = authHelper.doVerifyEmail(code)(_))
  def requireLoginToVerifyEmail(code: String)(implicit request: Request[_]): Result = {
    Redirect(routes.AuthController.loginPage())
      .withSession(session + (SecureSocial.OriginalUrlKey -> routes.AuthController.verifyEmail(code).url))
  }

  def forgotPassword() = JsonToJsonAction(allowPending = true)(
    authenticatedAction = authHelper.doForgotPassword(_),
    unauthenticatedAction = authHelper.doForgotPassword(_)
  )

  def setPasswordPage(code: String) = Action { implicit request =>
    db.readWrite { implicit s =>
      passwordResetRepo.getByToken(code) match {
        case Some(pr) if passwordResetRepo.tokenIsNotExpired(pr) =>
          Ok(views.html.auth.setPassword(code = code))
        case Some(pr) if pr.state == PasswordResetStates.ACTIVE || pr.state == PasswordResetStates.INACTIVE =>
          Ok(views.html.auth.setPassword(error = "expired"))
        case Some(pr) if pr.state == PasswordResetStates.USED =>
          Ok(views.html.auth.setPassword(error = "already_used"))
        case _ =>
          Ok(views.html.auth.setPassword(error = "invalid_code"))
      }
    }
  }

  def setPassword() = JsonToJsonAction(allowPending = true)(
    authenticatedAction = authHelper.doSetPassword(_),
    unauthenticatedAction = authHelper.doSetPassword(_)
  )

  def uploadBinaryPicture() = JsonAction(allowPending = true, parser = parse.temporaryFile)(
    authenticatedAction = authHelper.doUploadBinaryPicture(_),
    unauthenticatedAction = authHelper.doUploadBinaryPicture(_)
  )

  def uploadFormEncodedPicture() = JsonAction(allowPending = true, parser = parse.multipartFormData)(
    authenticatedAction = authHelper.doUploadFormEncodedPicture(_),
    unauthenticatedAction = authHelper.doUploadFormEncodedPicture(_)
  )

  def cancelAuth() = JsonAction(allowPending = true)(
    authenticatedAction = doCancelPage(_),
    unauthenticatedAction = doCancelPage(_)
  )
  private def doCancelPage(implicit request: Request[_]): Result = {
    // todo(Andrew): Remove from database: user, credentials, securesocial session
    Ok("1").withNewSession.discardingCookies(
      DiscardingCookie(Authenticator.cookieName, Authenticator.cookiePath, Authenticator.cookieDomain, Authenticator.cookieSecure))
  }

}
