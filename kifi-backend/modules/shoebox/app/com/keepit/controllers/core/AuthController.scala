package com.keepit.controllers.core

import com.google.inject.Inject
import com.keepit.common.controller.{ActionAuthenticator, ShoeboxServiceController}
import com.keepit.common.db.slick.Database
import com.keepit.model.UserCredRepo

import play.api.data.Forms._
import play.api.data._
import play.api.data.validation.Constraints
import play.api.http.HeaderNames
import play.api.libs.json.Json
import play.api.mvc._
import securesocial.controllers.ProviderController
import securesocial.core._
import securesocial.core.providers.utils.GravatarHelper

class AuthController @Inject() (db: Database, userCredRepo: UserCredRepo) extends ShoeboxServiceController {

  private implicit val readsOAuth2Info = Json.reads[OAuth2Info]

  // Some of the below code is taken from ProviderController in SecureSocial
  def mobileAuth(providerName: String) = Action(parse.json) { implicit request =>
    // format { "accessToken": "..." }
    val oauth2Info = request.body.asOpt[OAuth2Info]
    val provider = Registry.providers.get(providerName).get
    val filledUser = provider.fillProfile(
      SocialUser(IdentityId("", provider.id), "", "", "", None, None, provider.authMethod, oAuth2Info = oauth2Info))
    UserService.find(filledUser.identityId) map { user =>
      val newSession = Events.fire(new LoginEvent(user)).getOrElse(session)
      Authenticator.create(user).fold(
        error => throw error,
        authenticator => Ok(Json.obj("sessionId" -> authenticator.id))
          .withSession(newSession - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
          .withCookies(authenticator.toCookie)
      )
    } getOrElse NotFound(Json.obj("error" -> "user not found"))
  }

  // These methods are nice wrappers for the SecureSocial authentication methods.
  // Logout is still handled by SecureSocial directly.

  def login(provider: String) = getAuthAction(provider, isLogin = true)
  def loginByPost(provider: String) = getAuthAction(provider, isLogin = true)
  def link(provider: String) = getAuthAction(provider, isLogin = false)
  def linkByPost(provider: String) = getAuthAction(provider, isLogin = false)

  private def getSession(res: SimpleResult[_], refererAsOriginalUrl: Boolean = false)
      (implicit request: RequestHeader): Session = {
    val sesh = Session.decodeFromCookie(
      res.header.headers.get(SET_COOKIE).flatMap(Cookies.decode(_).find(_.name == Session.COOKIE_NAME)))
    val originalUrlOpt = sesh.get(SecureSocial.OriginalUrlKey) orElse {
      if (refererAsOriginalUrl) request.headers.get(HeaderNames.REFERER) else None
    }
    originalUrlOpt map { url => sesh + (SecureSocial.OriginalUrlKey -> url) } getOrElse sesh
  }

  private def getAuthAction(provider: String, isLogin: Boolean): Action[AnyContent] = Action { implicit request =>
    ProviderController.authenticate(provider)(request) match {
      case res: SimpleResult[_] =>
        res.withSession(if (isLogin) getSession(res) - ActionAuthenticator.FORTYTWO_USER_ID else getSession(res, true))
      case res => res
    }
  }

  private case class RegistrationInfo(email: String, password: String, firstName: String, lastName: String)

  private val emailPasswordForm = Form[RegistrationInfo](
    mapping(
      "email" -> email.verifying("Email is invalid", email => db.readOnly { implicit s =>
        userCredRepo.findByEmailOpt(email).isEmpty
      }),
      "firstname" -> nonEmptyText,
      "lastname" -> nonEmptyText,
      "password" -> tuple("1" -> nonEmptyText, "2" -> nonEmptyText)
        .verifying("Passwords do not match", pw => pw._1 == pw._2).transform(_._1, (a: String) => (a, a))
        .verifying(Constraints.minLength(7))
    )
    (RegistrationInfo.apply)
    (RegistrationInfo.unapply)
  )

  def signup() = Action { implicit request =>
    Ok(views.html.website.signup())
  }

  def handleSignup() = Action { implicit request =>
    emailPasswordForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.website.signup(Some("Please fill out all fields"))),
      { case RegistrationInfo(email, firstName, lastName, password) =>
        val identity: Identity = SocialUser(
          identityId = IdentityId(email, "userpass"),
          firstName = firstName,
          lastName = lastName,
          fullName = s"$firstName $lastName",
          email = Some(email),
          avatarUrl = GravatarHelper.avatarFor(email),
          authMethod = AuthenticationMethod.UserPassword,
          passwordInfo = Some(Registry.hashers.currentHasher.hash(password))
        )
        UserService.save(identity)
        Authenticator.create(identity).fold(
          error => InternalServerError(views.html.website.signup(Some("Cannot create user"))),
          authenticator => Redirect("/")
            .withSession(session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId)
            .withCookies(authenticator.toCookie)
        )
     })
  }
}
