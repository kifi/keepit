package com.keepit.controllers.core

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.KifiSession._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper, UserRequest, MaybeUserRequest, NonUserRequest }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.net.UserAgent
import com.keepit.common.store.S3UserPictureConfig
import com.keepit.common.time._
import com.keepit.heimdal.{ AnonymousEvent, EventType, HeimdalContextBuilder, HeimdalServiceClient }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.slack.models.SlackTeamId
import com.keepit.social._
import com.keepit.social.providers.ProviderController
import com.kifi.macros.json
import play.api.Play
import play.api.Play._
import play.api.i18n.Messages
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.{ JsNumber, JsValue, Json }
import play.api.mvc._
import securesocial.core._
import securesocial.core.providers.utils.RoutesHelper

import scala.concurrent.Future
import scala.util.Try

object AuthController {
  val LinkWithKey = "linkWith"

  private lazy val obscureRegex = """^(?:[^@]|([^@])[^@]+)@""".r
  def obscureEmailAddress(address: String) = obscureRegex.replaceFirstIn(address, """$1...@""")
}

@json case class UserPassFinalizeInfo(
  email: EmailAddress,
  password: String,
  firstName: String,
  lastName: String,
  picToken: Option[String],
  picWidth: Option[Int],
  picHeight: Option[Int],
  cropX: Option[Int],
  cropY: Option[Int],
  cropSize: Option[Int],
  libraryPublicId: Option[PublicId[Library]],
  libAuthToken: Option[String],
  orgPublicId: Option[PublicId[Organization]],
  orgAuthToken: Option[String],
  keepPublicId: Option[PublicId[Keep]],
  keepAuthToken: Option[String])

object UserPassFinalizeInfo {
  def toEmailPassFinalizeInfo(info: UserPassFinalizeInfo): EmailPassFinalizeInfo =
    EmailPassFinalizeInfo(
      info.firstName,
      info.lastName,
      info.picToken,
      info.picWidth,
      info.picHeight,
      info.cropX,
      info.cropY,
      info.cropSize,
      companyName = None
    )
}

@json case class TokenFinalizeInfo(
  email: EmailAddress,
  firstName: String,
  lastName: String,
  password: String,
  picToken: Option[String],
  picHeight: Option[Int],
  picWidth: Option[Int],
  cropX: Option[Int],
  cropY: Option[Int],
  cropSize: Option[Int],
  libraryPublicId: Option[PublicId[Library]],
  libAuthToken: Option[String],
  orgPublicId: Option[PublicId[Organization]],
  orgAuthToken: Option[String],
  keepPublicId: Option[PublicId[Keep]],
  keepAuthToken: Option[String])

object TokenFinalizeInfo {
  def toSocialFinalizeInfo(info: TokenFinalizeInfo): SocialFinalizeInfo = {
    SocialFinalizeInfo(
      info.email,
      info.firstName,
      info.lastName,
      Option(info.password),
      info.picToken,
      info.picHeight,
      info.picWidth,
      info.cropX,
      info.cropY,
      info.cropSize
    )
  }
}

class AuthController @Inject() (
    db: Database,
    clock: Clock,
    authHelper: AuthHelper,
    authCommander: AuthCommander,
    userIpAddressCommander: UserIpAddressCommander,
    val userActionsHelper: UserActionsHelper,
    userRepo: UserRepo,
    userValueRepo: UserValueRepo,
    emailAddressRepo: UserEmailAddressRepo,
    inviteCommander: InviteCommander,
    passwordResetRepo: PasswordResetRepo,
    heimdalServiceClient: HeimdalServiceClient,
    config: FortyTwoConfig,
    userIdentityHelper: UserIdentityHelper,
    implicit val secureSocialClientIds: SecureSocialClientIds,
    implicit val publicIdConfig: PublicIdConfiguration) extends UserActions with ShoeboxServiceController with Logging {

  // Note: some of the below code is taken from ProviderController in SecureSocial
  // Logout is still handled by SecureSocial directly.

  def loginSocial(provider: String, close: Boolean) = MaybeUserAction { implicit request =>
    Try { //making sure no way it will hurt login
      val userOpt = request.userIdOpt
      log.info(s"[login social] with $provider, (c=$close) user $userOpt")
    }
    val res = handleAuth(provider)
    if (close && res.header.status == 303) {
      authHelper.transformResult(res) { (_, session: Session) =>
        res.withSession(session + (SecureSocial.OriginalUrlKey -> routes.AuthController.afterLoginClosePopup.url))
      }
    } else {
      res
    }
  }
  def logInWithUserPass(link: String) = MaybeUserAction { implicit request =>
    handleAuth("userpass") match {
      case res: Result if res.header.status == 303 =>
        authHelper.transformResult(res) { (cookies: Seq[Cookie], sess: Session) =>
          if (link != "") {
            // Manually link accounts. Annoying...
            log.info(s"[logInWithUserPass] Attempting to link $link to newly logged in user ${sess.getUserId}")
            val linkAttempt = for {
              identityId <- request.identityId
              identity <- authCommander.getUserIdentity(identityId)
              userId <- sess.getUserId
            } yield {
              log.info(s"[logInWithUserPass] Linking userId $userId to $link, social data from $identity")
              val linkedIdentity = identity.withUserId(userId)
              authCommander.saveUserIdentity(linkedIdentity)
              log.info(s"[logInWithUserPass] Done. Hope it worked? for userId $userId / $link, $linkedIdentity")
            }
            if (linkAttempt.isEmpty) {
              log.info(s"[logInWithUserPass] No identity/userId found, ${request.identityId}, userId ${sess.getUserId}")
            }
          }
          Ok(Json.obj("uri" -> res.header.headers.get(LOCATION).get)).withCookies(cookies: _*).withSession(sess)
        }
      case res => res
    }
  }

  private def handleAuth(provider: String)(implicit request: Request[_]): Result = {
    Registry.providers.get(provider) match { // todo(ray): remove dependency on SecureSocial registry
      case Some(p) => {
        try {
          p.authenticate().fold(
            result => result,
            user => completeAuthentication(user, request.session)
          )
        } catch {
          case ex: AccessDeniedException => {
            Redirect(RoutesHelper.login()).flashing("error" -> Messages("securesocial.login.accessDenied"))
          }
          case other: Throwable => {
            log.error("Unable to log user in. An exception was thrown", other)
            Redirect(RoutesHelper.login()).flashing("error" -> Messages("securesocial.login.errorLoggingIn"))
          }
        }
      }
      case _ => NotFound
    }
  }

  private def completeAuthentication(socialUser: Identity, session: Session)(implicit request: RequestHeader): Result = {
    log.info(s"[completeAuthentication] user=[${socialUser.identityId}] class=${socialUser.getClass} sess=${session.data}")
    val sess = Events.fire(new LoginEvent(socialUser)).getOrElse(session) - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey
    Authenticator.create(socialUser) match {
      case Right(authenticator) => {
        val userId = db.readOnlyMaster { implicit session =>
          userIdentityHelper.getOwnerId(authenticator.identityId).get
        }
        Redirect(ProviderController.toUrl(sess))
          .withSession(sess.setUserId(userId))
          .withCookies(authenticator.toCookie)
      }
      case Left(error) => {
        log.error(s"[completeAuthentication] Caught error $error while creating authenticator; cause=${error.getCause}")
        throw new RuntimeException("Error creating authenticator", error)
      }
    }
  }

  def accessTokenLogin(providerName: String) = MaybeUserAction.async(parse.tolerantJson) { implicit request =>
    authHelper.doAccessTokenLogin(providerName)
  }

  def accessTokenSignup(providerName: String) = MaybeUserAction.async(parse.tolerantJson) { implicit request =>
    authHelper.doAccessTokenSignup(providerName)
  }

  def oauth1TokenSignup(providerName: String) = MaybeUserAction.async(parse.tolerantJson) { implicit request =>
    authHelper.doAccessTokenSignup(providerName)
  }

  def oauth1TokenLogin(providerName: String) = MaybeUserAction.async(parse.tolerantJson) { implicit request =>
    authHelper.doAccessTokenLogin(providerName)
  }

  // one-step sign-up
  def emailSignup() = MaybeUserAction.async(parse.tolerantJson) { implicit request =>
    request.body.asOpt[UserPassFinalizeInfo] match {
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "invalid_arguments")))
      case Some(info) =>
        val hasher = Registry.hashers.currentHasher
        val session = request.session
        val home = com.keepit.controllers.website.HomeControllerRoutes.home()
        authCommander.getUserIdentity(IdentityId(info.email.address, SocialNetworks.EMAIL.authProvider)) match {
          case Some(identity @ UserIdentity(_, Some(userId))) => {
            val matches = hasher.matches(identity.passwordInfo.get, info.password)
            if (!matches) {
              Future.successful(Forbidden(Json.obj("error" -> "user_exists_failed_auth")))
            } else {
              val user = db.readOnlyMaster { implicit s => userRepo.get(userId) }
              if (user.state != UserStates.INCOMPLETE_SIGNUP) {
                Authenticator.create(identity).fold(
                  error => Future.successful(Status(INTERNAL_SERVER_ERROR)("0")),
                  authenticator =>
                    Future.successful(
                      Ok(Json.obj("uri" -> session.get(SecureSocial.OriginalUrlKey).getOrElse(home).asInstanceOf[String]))
                        .withSession((session - SecureSocial.OriginalUrlKey).setUserId(userId))
                        .withCookies(authenticator.toCookie)
                    )
                )
              } else {
                authHelper.handleEmailPassFinalizeInfo(UserPassFinalizeInfo.toEmailPassFinalizeInfo(info), info.libraryPublicId, info.libAuthToken, info.orgPublicId, info.orgAuthToken, info.keepPublicId, info.keepAuthToken)(UserRequest(request, user.id.get, None, userActionsHelper))
              }
            }
          }
          case _ => {
            val pInfo = hasher.hash(info.password)
            val (_, userId) = authCommander.saveUserPasswordIdentity(None, info.email, Some(pInfo), firstName = "", lastName = "", isComplete = false)
            val user = db.readOnlyMaster { implicit s => userRepo.get(userId) }
            authHelper.handleEmailPassFinalizeInfo(UserPassFinalizeInfo.toEmailPassFinalizeInfo(info), info.libraryPublicId, info.libAuthToken, info.orgPublicId, info.orgAuthToken, info.keepPublicId, info.keepAuthToken)(UserRequest(request, user.id.get, None, userActionsHelper))
          }
        }
    }
  }

  // todo(Léo): why doesn't this deal with intents at all?
  def afterLogin() = MaybeUserAction { implicit req =>
    req match {
      case userRequest: UserRequest[_] =>
        userIpAddressCommander.logUserByRequest(userRequest)
        if (userRequest.user.state == UserStates.PENDING) {
          Redirect("/")
        } else if (userRequest.user.state == UserStates.INCOMPLETE_SIGNUP) {
          Redirect(com.keepit.controllers.core.routes.AuthController.signupPage())
        } else if (userRequest.kifiInstallationId.isEmpty && !hasSeenInstall(userRequest)) {
          inviteCommander.markPendingInvitesAsAccepted(userRequest.user.id.get, userRequest.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value)))
          Redirect(com.keepit.controllers.website.HomeControllerRoutes.install())
        } else {
          userRequest.session.get(SecureSocial.OriginalUrlKey) map { url =>
            Redirect(url).withSession(userRequest.session - SecureSocial.OriginalUrlKey)
          } getOrElse {
            Redirect("/")
          }
        }
      case nonUserRequest: NonUserRequest[_] => {
        nonUserRequest.identityId.flatMap(authCommander.getUserIdentity) match {

          case Some(identity) => {
            // User tried to log in (not sign up) with social network.
            identity.email.flatMap { addressStr =>
              EmailAddress.validate(addressStr).toOption.flatMap { validEmailAddress =>
                db.readOnlyMaster(emailAddressRepo.getByAddress((validEmailAddress))(_))
              }
            } match {
              case Some(addr) =>
                // A user with this email address exists in the system, but it is not yet linked to this social identity.
                Ok(views.html.authMinimal.linkSocial(identity.identityId.providerId, identity.email.get))
              case None =>
                // No email for this user exists in the system.
                Redirect("/signup").flashing("signin_error" -> "no_account")
            }
          }

          case None =>
            Redirect("/") // error??
          // Ok(views.html.website.welcome(msg = request.flash.get("error")))
        }
      }
    }
  }

  def afterLoginClosePopup() = MaybeUserAction { implicit req =>
    val message = req match {
      case request: UserRequest[_] => "authed"
      case request: NonUserRequest[_] => "not_authed"
    }
    Ok(s"<!doctype html><script>if(window.opener)opener.postMessage('$message',location.origin);window.close()</script>").as(HTML)
  }

  def signup(
    provider: String,
    publicLibraryId: Option[String], intent: Option[String], libAuthToken: Option[String],
    publicOrgId: Option[String], orgAuthToken: Option[String],
    publicKeepId: Option[String], keepAuthToken: Option[String]) = Action.async(parse.anyContent) { implicit request =>
    val authRes = ProviderController.authenticate(provider)
    authRes(request).map { result =>
      authHelper.transformResult(result) { (_, sess: Session) =>
        // TODO: set FORTYTWO_USER_ID instead of clearing it and then setting it on the next request?
        val res = result.withSession((sess + (SecureSocial.OriginalUrlKey -> routes.AuthController.signupPage().url)).deleteUserId)

        // todo implement POST hook
        // This could be a POST, url encoded body. If so, there may be a registration hook for us to add to their session.
        // ie, auto follow library, auto friend, etc

        val cookies = Seq(
          publicLibraryId.map(libId => Cookie("publicLibraryId", libId)),
          intent.map(action => Cookie("intent", action)),
          libAuthToken.map(at => Cookie("libAuthToken", at)),
          publicOrgId.map(orgId => Cookie("publicOrgId", orgId)),
          orgAuthToken.map(at => Cookie("orgAuthToken", at)),
          publicKeepId.map(keepId => Cookie("publicKeepId", keepId)),
          keepAuthToken.map(at => Cookie("keepAuthToken", at))
        ).flatten
        res.withCookies(cookies: _*)
      }
    }
  }

  def link(provider: String) = Action.async(parse.anyContent) { implicit request =>
    ProviderController.authenticate(provider)(request) map { res: Result =>
      val resCookies = res.header.headers.get(SET_COOKIE).map(Cookies.decode).getOrElse(Seq.empty)
      val resSession = Session.decodeFromCookie(resCookies.find(_.name == Session.COOKIE_NAME))
      if (request.session.get(SecureSocial.OriginalUrlKey).isDefined) {
        res.withSession(resSession + (SecureSocial.OriginalUrlKey -> request.session.get(SecureSocial.OriginalUrlKey).get))
      } else if (resSession.get(SecureSocial.OriginalUrlKey).isEmpty) {
        request.headers.get(REFERER).map { url =>
          res.withSession(resSession + (SecureSocial.OriginalUrlKey -> url))
        } getOrElse res
      } else res
    }
  }

  // --
  // Utility methods
  // --

  private def hasSeenInstall(implicit request: UserRequest[_]): Boolean = {
    db.readOnlyReplica { implicit s => userValueRepo.getValue(request.userId, UserValues.hasSeenInstall) }
  }

  def loginPage() = MaybeUserAction { implicit request =>
    request match {
      case ur: UserRequest[_] =>
        Redirect("/")
      case request: NonUserRequest[_] =>
        request.request.headers.get(USER_AGENT).flatMap { agentString =>
          val agent = UserAgent(agentString)
          log.info(s"trying to log in via $agent. orig string: $agentString")
          if (agent.isOldIE) {
            Some(Redirect(com.keepit.controllers.website.HomeControllerRoutes.unsupported()))
          } else None
        }.getOrElse(Ok(views.html.authMinimal.loginToKifi()))
    }
  }

  // Finalize account
  def signupPage() = MaybeUserAction { implicit request =>
    doSignupPage
  }

  private def temporaryReportSignupLoad()(implicit request: Request[_]): Unit = SafeFuture {
    val context = new HeimdalContextBuilder()
    context.addRequestInfo(request)
    heimdalServiceClient.trackEvent(AnonymousEvent(context.build, EventType("loaded_signup_page")))
  }

  // Initial user/pass signup JSON action
  def userPasswordSignup() = MaybeUserAction(parse.tolerantJson) { implicit request =>
    authHelper.userPasswordSignupAction
  }

  // todo(Léo): why does this deal with intents just like AuthHelper? DRY?
  private def doSignupPage(implicit request: MaybeUserRequest[_]): Result = {
    val agentOpt = request.headers.get("User-Agent").map { agent =>
      UserAgent(agent)
    }
    if (agentOpt.exists(_.isOldIE)) {
      Redirect(com.keepit.controllers.website.HomeControllerRoutes.unsupported())
    } else {
      val cookiePublicLibraryId = request.cookies.get("publicLibraryId")
      val cookieIntent = request.cookies.get("intent") // make sure everywhere handles this right
      val libAuthToken = request.cookies.get("libAuthToken")
      val pubLibIdOpt = cookiePublicLibraryId.map(cookie => PublicId[Library](cookie.value))
      val publicOrgIdCookie = request.cookies.get("publicOrgId")
      val orgAuthToken = request.cookies.get("orgAuthToken")
      val publicKeepIdCookie = request.cookies.get("publicKeepId")
      val pubKeepIdOpt = publicKeepIdCookie.map(cookie => PublicId[Keep](cookie.value))
      val keepAuthToken = request.cookies.get("keepAuthToken")
      val creditCodeCookie = request.cookies.get("creditCode")
      val slackTeamIdCookie = request.cookies.get("slackTeamId")

      val intentParams = PostRegIntentParams.fromCookies(request.cookies)

      val pubOrgIdOpt = publicOrgIdCookie.map(cookie => PublicId[Organization](cookie.value))

      val discardedCookies = Seq(cookiePublicLibraryId, cookieIntent, libAuthToken, publicOrgIdCookie, orgAuthToken, creditCodeCookie).flatten.map(c => DiscardingCookie(c.name))

      request match {
        case ur: UserRequest[_] =>
          if (ur.user.state != UserStates.INCOMPLETE_SIGNUP) {
            // Complete user, they don't need to be here!
            log.info(s"[doSignupPage] ${ur.userId} already completed signup!")

            val homeUrl = s"${com.keepit.controllers.website.HomeControllerRoutes.home}?m=0"

            val redirect = cookieIntent.map(_.value).map {
              case "follow" =>
                pubLibIdOpt.flatMap(Library.decodePublicId(_).toOption).foreach { libId =>
                  authCommander.autoJoinLib(ur.userId, libId, libAuthToken.map(_.value))
                }
                // todo redirect to library if `joinedSuccessfully`
                Redirect(homeUrl)
              case "joinOrg" =>
                (pubOrgIdOpt.flatMap(Organization.decodePublicId(_).toOption), orgAuthToken.map(_.value)) match {
                  case (Some(orgId), Some(authToken)) => authCommander.autoJoinOrg(ur.userId, orgId, authToken)
                  case _ =>
                }
                Redirect(homeUrl)
              case "joinKeep" =>
                pubKeepIdOpt.flatMap(Keep.decodePublicId(_).toOption).foreach { keepId =>
                  authCommander.autoJoinKeep(ur.userId, keepId, keepAuthToken.map(_.value))
                }
                Redirect(homeUrl)
              case "waitlist" =>
                Redirect("/twitter/thanks")
              case "slack" =>
                Redirect(com.keepit.controllers.core.routes.AuthController.startWithSlack(intentParams.slackTeamid).url)
              case _ =>
                Redirect(homeUrl)
            } getOrElse Redirect(homeUrl)

            redirect.discardingCookies(discardedCookies: _*)

          } else if (ur.identityId.exists(authCommander.getUserIdentity(_).isDefined)) {
            log.info(s"[doSignupPage] ${ur.identityId.get} has incomplete signup state")
            // User exists, is incomplete
            // Supporting top-level intents from here. These are non-privileged, and just determine where someone goes. (No ids, tokens, etc)
            request.getQueryString("intent") match {
              case Some(intentAction) =>
                Ok(views.html.authMinimal.signupGetName()).withCookies(Cookie("intent", intentAction))
              case None =>
                Ok(views.html.authMinimal.signupGetName())
            }
          } else {
            log.info(s"[doSignupPage] ${ur.userId} has no identity ${ur.user.state}")
            // User but no identity. Huh?
            // Haven't run into this one. Redirecting user to logout, ideally to fix their cookie situation
            Redirect("/logout")
          }
        case requestNonUser: NonUserRequest[_] =>
          val identityOpt = requestNonUser.identityId.flatMap(authCommander.getUserIdentity(_))
          if (identityOpt.isDefined) {
            val identity = identityOpt.get
            if (identity.identityId.userId.trim.isEmpty) {
              throw new Exception(s"got an identity [$identity] with empty user id for non user from request ${requestNonUser.path} headers ${requestNonUser.headers.toSimpleMap.mkString(",")} body [${requestNonUser.body}]")
            }
            val loginAndLinkEmail = request.queryString.get("link").map(_.headOption).flatten
            if (loginAndLinkEmail.isDefined || authCommander.isEmailAddressAlreadyOwned(identity)) {
              // No user exists, but social network identity’s email address matches a Kifi user
              log.info(s"[doSignupPage] ${identity} social network email ${identity.email}")
              Ok(views.html.authMinimal.linkSocial(
                identity.identityId.providerId,
                identity.email.getOrElse(loginAndLinkEmail.getOrElse(""))
              ))
            } else if (requestNonUser.flash.get("signin_error").exists(_ == "no_account")) {
              // No user exists, social login was attempted. Let user choose what to do next.
              log.info(s"[doSignupPage] ${identity} logged in with wrong network")
              // todo: Needs visual refresh
              Ok(views.html.authMinimal.accountNotFound(
                provider = identity.identityId.providerId
              ))
            } else {
              // No user exists, has social network identity, must finalize

              // todo: This shouldn't be special cased to twitter, this should be for social regs that don't provide an email
              if (requestNonUser.identityId.get.providerId == "twitter") {
                log.info(s"[doSignupPage] ${identity} finalizing twitter account")
                val purposeDrivenInstall = cookieIntent.isDefined && cookieIntent.get.value == "waitlist"
                Ok(views.html.authMinimal.signupGetEmail(
                  firstName = User.sanitizeName(identity.firstName.trim),
                  lastName = User.sanitizeName(identity.lastName.trim),
                  picture = identityPicture(identity),
                  purposeDrivenInstall = purposeDrivenInstall
                ))
              } else {
                log.info(s"[doSignupPage] ${identity} finalizing social id")

                val sfi = SocialFinalizeInfo(
                  email = EmailAddress(identity.email.getOrElse("")),
                  firstName = User.sanitizeName(identity.firstName),
                  lastName = User.sanitizeName(identity.lastName), //todo(andrew): is having an empty string for email is the right thing to do at this point???
                  password = None,
                  picToken = None, picHeight = None, picWidth = None, cropX = None, cropY = None, cropSize = None)

                val targetPubLibId = if (cookieIntent.exists(_.value == "follow")) pubLibIdOpt else None
                val targetPubOrgId = if (cookieIntent.exists(_.value == "joinOrg")) pubOrgIdOpt else None
                val targetPubKeepId = if (cookieIntent.exists(_.value == "joinKeep")) pubKeepIdOpt else None
                authHelper.handleSocialFinalizeInfo(sfi, targetPubLibId, libAuthToken.map(_.value), targetPubOrgId, orgAuthToken.map(_.value), targetPubKeepId, keepAuthToken.map(_.value), true)(request)
              }

            }
          } else {
            temporaryReportSignupLoad()(requestNonUser)
            Ok(views.html.authMinimal.signup())
          }

      }
    }
  }

  private def identityPicture(identity: Identity) = {
    identity.identityId.providerId match {
      case "facebook" =>
        s"//graph.facebook.com/v2.0/${identity.identityId.userId}/picture?width=200&height=200"
      case _ => identity.avatarUrl.getOrElse(S3UserPictureConfig.defaultImage)
    }
  }

  def userPassFinalizeAccountAction() = MaybeUserAction.async(parse.tolerantJson) { implicit request =>
    request match {
      case ur: UserRequest[JsValue] =>
        authHelper.doUserPassFinalizeAccountAction(ur)
      case _ =>
        resolve(Forbidden(JsNumber(0)))
    }
  }

  def socialFinalizeAccountAction() = MaybeUserAction(parse.tolerantJson) { implicit request =>
    authHelper.doSocialFinalizeAccountAction
  }

  def tokenFinalizeAccountAction() = MaybeUserAction(parse.tolerantJson) { implicit request =>
    authHelper.doTokenFinalizeAccountAction
  }

  def OkStreamFile(filename: String) =
    Status(200).chunked(Enumerator.fromStream(Play.resourceAsStream(filename).get)) as HTML

  def verifyEmail(code: EmailVerificationCode, orgPubId: Option[String] = None) = MaybeUserAction { implicit request =>
    val orgIdOpt = orgPubId.flatMap(pubId => Organization.decodePublicId(PublicId[Organization](pubId)).toOption)
    authHelper.doVerifyEmail(code, orgIdOpt)
  }

  def forgotPassword() = MaybeUserAction.async(parse.tolerantJson) { implicit request =>
    authHelper.doForgotPassword
  }

  def setPasswordPage(code: String) = Action { implicit request =>
    db.readWrite { implicit s =>
      passwordResetRepo.getByToken(code) match {
        case Some(pr) if passwordResetRepo.tokenIsNotExpired(pr) =>
          Ok(views.html.authMinimal.resetPassword(code = code))
        case Some(pr) if pr.state == PasswordResetStates.ACTIVE || pr.state == PasswordResetStates.INACTIVE =>
          Ok(views.html.authMinimal.resetPassword(error = "expired"))
        case Some(pr) if pr.state == PasswordResetStates.USED =>
          Ok(views.html.authMinimal.resetPassword(error = "already_used"))
        case _ =>
          Ok(views.html.authMinimal.resetPassword(error = "invalid_code"))
      }
    }
  }

  def setPassword() = MaybeUserAction(parse.tolerantJson) { implicit request =>
    authHelper.doSetPassword
  }

  def uploadBinaryPicture() = MaybeUserAction(parse.temporaryFile) { implicit request =>
    authHelper.doUploadBinaryPicture
  }

  def uploadFormEncodedPicture() = MaybeUserAction(parse.multipartFormData) { implicit request =>
    authHelper.doUploadFormEncodedPicture
  }

  def cancelAuth() = MaybeUserAction { implicit request =>
    doCancelPage
  }

  private def doCancelPage(implicit request: Request[_]): Result = {
    // todo(Andrew): Remove from database: user, credentials, securesocial session
    Ok("1").withNewSession.discardingCookies(
      DiscardingCookie(Authenticator.cookieName, Authenticator.cookiePath, Authenticator.cookieDomain, Authenticator.cookieSecure))
  }

  def connectWithSlack = MaybeUserAction { implicit request =>
    Ok(views.html.authMinimal.connectWithSlack())
  }

  def connectWithSlackGo = MaybeUserAction { implicit request =>
    Redirect(com.keepit.controllers.core.routes.AuthController.startWithSlack(slackTeamId = None).url, SEE_OTHER)
  }

  def startWithSlack(slackTeamId: Option[SlackTeamId]) = MaybeUserAction { implicit request =>
    request match {
      case userRequest: UserRequest[_] =>
        val slackTeamIdFromCookie = request.cookies.get("slackTeamId").map(_.value).map(SlackTeamId(_))
        val discardedCookie = DiscardingCookie("slackTeamId")
        val slackTeamIdThatWasAroundForSomeMysteriousReason = slackTeamId orElse slackTeamIdFromCookie
        Redirect(com.keepit.controllers.website.routes.SlackOAuthController.addSlackTeam(slackTeamIdThatWasAroundForSomeMysteriousReason).url, SEE_OTHER).discardingCookies(discardedCookie)
      case nonUserRequest: NonUserRequest[_] =>
        val signupUrl = com.keepit.controllers.core.routes.AuthController.signup(provider = "slack", intent = Some("slack")).url + slackTeamId.map(id => s"&slackTeamId=${id.value}").getOrElse("")
        Redirect(signupUrl, SEE_OTHER).withSession(request.session)
    }
  }
}
