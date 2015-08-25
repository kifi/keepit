package com.keepit.controllers.core

import com.keepit.common.http._
import com.keepit.commanders.emails.ResetPasswordEmailSender
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.net.UserAgent
import com.google.inject.Inject
import com.keepit.common.oauth.adaptor.SecureSocialAdaptor
import com.keepit.common.oauth.{ OAuth1ProviderRegistry, OAuth2AccessToken, ProviderIds, OAuth2ProviderRegistry }
import play.api.Mode
import play.api.Mode._
import play.api.mvc._
import play.api.http.{ Status, HeaderNames }
import securesocial.core._
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.commanders._
import com.keepit.social._
import securesocial.core.providers.utils.PasswordHasher
import com.keepit.common.controller.KifiSession._
import com.keepit.common.store.S3ImageStore
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.time._
import play.api.Play._
import play.api.data._
import play.api.data.Forms._
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import scala.util.{ Try, Failure, Success }
import play.api.mvc.Result
import play.api.libs.json.{ Json, JsNumber, JsValue }
import play.api.mvc.DiscardingCookie
import play.api.mvc.Cookie
import com.keepit.common.mail.EmailAddress
import com.keepit.social.SocialId
import com.keepit.common.controller.{ MaybeUserRequest, UserRequest }
import com.keepit.model.Invitation
import com.keepit.social.UserIdentity
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.performance._
import scala.concurrent.{ Await, Future }
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.inject.FortyTwoConfig

object AuthHelper {
  val PWD_MIN_LEN = 7
  def validatePwd(pwd: String) = (pwd.nonEmpty && pwd.length >= PWD_MIN_LEN)
}

class AuthHelper @Inject() (
    db: Database,
    clock: Clock,
    airbrake: AirbrakeNotifier,
    oauth1ProviderRegistry: OAuth1ProviderRegistry,
    oauth2ProviderRegistry: OAuth2ProviderRegistry,
    authCommander: AuthCommander,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    libraryInviteRepo: LibraryInviteRepo,
    userCredRepo: UserCredRepo,
    socialRepo: SocialUserInfoRepo,
    emailAddressRepo: UserEmailAddressRepo,
    userValueRepo: UserValueRepo,
    passwordResetRepo: PasswordResetRepo,
    kifiInstallationRepo: KifiInstallationRepo, // todo: factor out
    orgRepo: OrganizationRepo,
    s3ImageStore: S3ImageStore,
    postOffice: LocalPostOffice,
    inviteCommander: InviteCommander,
    libraryCommander: LibraryCommander,
    libPathCommander: PathCommander,
    libraryInviteCommander: LibraryInviteCommander,
    userEmailAddressCommander: UserEmailAddressCommander,
    userCommander: UserCommander,
    twitterWaitlistCommander: TwitterWaitlistCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    implicit val secureSocialClientIds: SecureSocialClientIds,
    implicit val config: PublicIdConfiguration,
    resetPasswordEmailSender: ResetPasswordEmailSender,
    fortytwoConfig: FortyTwoConfig,
    mode: Mode) extends HeaderNames with Results with Status with Logging {

  def connectOptionView(email: EmailAddress, providerId: String) = {
    log.info(s"[connectOptionView] $email matches some kifi user, but no (social) user exists given $providerId")
    views.html.auth.connectToAuthenticate(
      emailAddress = email.address,
      network = SocialNetworkType(providerId),
      logInAttempted = true
    )
  }

  def transformResult(res: Result)(f: => (Seq[Cookie], Session) => Result) = {
    val resCookies = res.header.headers.get(SET_COOKIE).map(Cookies.decode).getOrElse(Seq.empty)
    val resSession = Session.decodeFromCookie(resCookies.find(_.name == Session.COOKIE_NAME))
    f(resCookies, resSession)
  }

  def checkForExistingUser(email: EmailAddress): Option[(Boolean, SocialUserInfo)] = timing("existing user") {
    db.readOnlyMaster { implicit s =>
      socialRepo.getOpt(SocialId(email.address), SocialNetworks.FORTYTWO).map(s => (true, s)) orElse {
        emailAddressRepo.getByAddress(email).map {
          case emailAddr if emailAddr.verified =>
            (true, socialRepo.getByUser(emailAddr.userId).find(_.networkType == SocialNetworks.FORTYTWO).headOption)
          case emailAddr =>
            // Someone is trying to register with someone else's unverified + non-login email address.
            (false, socialRepo.getByUser(emailAddr.userId).find(_.networkType == SocialNetworks.FORTYTWO).headOption)
        }.flatMap {
          case candidate if candidate._2.isDefined => Some((candidate._1, candidate._2.get))
          case otherwise => None
        }
      }
    }
  }

  def handleEmailPasswordSuccessForm(emailAddress: EmailAddress, passwordOpt: Option[String])(implicit request: MaybeUserRequest[_]) = timing(s"handleEmailPasswordSuccess($emailAddress)") {
    val hasher = Registry.hashers.currentHasher
    val tupleOpt: Option[(Boolean, SocialUserInfo)] = checkForExistingUser(emailAddress)
    val session = request.session
    val home = com.keepit.controllers.website.routes.HomeController.home()
    val res: Result = tupleOpt collect {
      case (emailIsVerifiedOrPrimary, sui) if sui.credentials.isDefined && sui.userId.isDefined =>
        // Social user exists with these credentials
        val identity = sui.credentials.get
        val matchesOpt = passwordOpt.map(p => hasher.matches(identity.passwordInfo.get, p))
        if (matchesOpt.exists(p => p)) {
          Authenticator.create(identity).fold(
            error => Status(INTERNAL_SERVER_ERROR)("0"),
            authenticator => {
              val finalized = db.readOnlyMaster { implicit session =>
                userRepo.get(sui.userId.get).state != UserStates.INCOMPLETE_SIGNUP
              }
              if (finalized) {
                Ok(Json.obj("uri" -> session.get(SecureSocial.OriginalUrlKey).getOrElse(home.url).asInstanceOf[String])) // todo(ray): uri not relevant for mobile
                  .withSession((session - SecureSocial.OriginalUrlKey).setUserId(sui.userId.get))
                  .withCookies(authenticator.toCookie)
              } else {
                Ok(Json.obj("success" -> true))
                  .withSession(session.setUserId(sui.userId.get))
                  .withCookies(authenticator.toCookie)
              }
            }
          )
        } else {
          // emailIsVerifiedOrPrimary lets you know if the email is verified to the user.
          // Deal with later?
          Forbidden(Json.obj("error" -> "user_exists_failed_auth"))
        }
    } getOrElse {
      val pInfo = passwordOpt.map(p => hasher.hash(p))
      val (newIdentity, userId) = authCommander.saveUserPasswordIdentity(None, request.identityOpt, emailAddress, pInfo, firstName = "", lastName = "", isComplete = false)
      Authenticator.create(newIdentity).fold(
        error => Status(INTERNAL_SERVER_ERROR)("0"),
        authenticator =>
          Ok(Json.obj("success" -> true))
            .withSession(session.setUserId(userId))
            .withCookies(authenticator.toCookie)
      )
    }
    res
  }

  val emailPasswordForm = Form[EmailPassword](
    mapping(
      "email" -> EmailAddress.formMapping,
      "password" -> optional(text.verifying("password_too_short", pw => AuthHelper.validatePwd(pw)))
    )((validEmail, pwd) => EmailPassword(validEmail, pwd))((ep: EmailPassword) => Some(ep.email, ep.password))
  )

  /**
   * For email login, a (emailString, password) is tied to a user. This email string
   * has no direct connection to a user's actual active email address. So, we need to
   * keep in mind that whenever the user supplies an email address, it may or may not
   * be related to what's their (emailString, password) login combination.
   */
  def userPasswordSignupAction(implicit request: MaybeUserRequest[JsValue]) = emailPasswordForm.bindFromRequest.fold(
    hasErrors = formWithErrors => {
      log.warn("[userpass-signup] Rejected email signup because of form errors: " + formWithErrors.errors)
      Forbidden(Json.obj("error" -> formWithErrors.errors.head.message))
    },
    success = {
      case EmailPassword(emailAddress, password) => handleEmailPasswordSuccessForm(emailAddress.copy(address = emailAddress.address.trim), password)
    }
  )

  private val url = fortytwoConfig.applicationBaseUrl

  private def saveKifiCampaignId(userId: Id[User], kcid: String): Unit = try {
    db.readWrite(attempts = 2) { implicit session =>
      userValueRepo.save(UserValue(userId = userId, name = UserValueName.KIFI_CAMPAIGN_ID, value = kcid))
    }
  } catch {
    case t: Throwable => airbrake.notify(s"fail to save Kifi Campaign Id for user $userId where kcid = $kcid", t)
  }

  trait PostRegIntent
  case class AutoFollowLibrary(libraryPublicId: PublicId[Library], authToken: Option[String]) extends PostRegIntent
  case class AutoJoinOrganization(organizationPublicId: PublicId[Organization], authToken: String) extends PostRegIntent
  case object JoinTwitterWaitlist extends PostRegIntent
  case object NoIntent extends PostRegIntent

  def finishSignup(user: User,
    emailAddress: EmailAddress,
    newIdentity: Identity,
    emailConfirmedAlready: Boolean,
    libraryPublicId: Option[PublicId[Library]],
    libAuthToken: Option[String],
    orgPublicId: Option[PublicId[Organization]],
    orgAuthToken: Option[String],
    isFinalizedImmediately: Boolean)(implicit request: MaybeUserRequest[_]): Result = {

    // This is a Big ball of mud. Hopefully we can restore a bit of sanity.
    // This function does some end-of-the-line wiring after registration. We support registrations directly from an API,
    // oauth tokens from social networks, and a several-step HTTP flow.
    // Right now, we need to automatically do tasks based on an `intent` (ie, auto-follow library, auto-friend a user, etc)

    // You'll notice that the signature of this function is weird. Sigh. Some callers don't have cookies, so we can't read
    // the `intent` from the cookies. Hence, ambiguity. In practice, we should find an intent in a cookie, or libraryPublicId is set
    // (meaning, "follow" intent), or nothing. Below is an attempt to unify state:

    val intentFromCookie: Option[PostRegIntent] = request.cookies.get("intent").map(_.value).flatMap {
      case "follow" =>
        request.cookies.get("publicLibraryId").map(i => PublicId[Library](i.value)).map { libPubId =>
          val authToken = request.cookies.get("libraryAuthToken").map(_.value)
          AutoFollowLibrary(libPubId, authToken)
        }
      case "joinOrg" =>
        request.cookies.get("publicOrgId").map(i => PublicId[Organization](i.value)).flatMap { orgPubId =>
          request.cookies.get("orgAuthToken").map(_.value).map { authToken =>
            AutoJoinOrganization(orgPubId, authToken)
          }
        }
      case "waitlist" =>
        Some(JoinTwitterWaitlist)
      case _ => None
    }

    val intent: PostRegIntent = intentFromCookie.orElse {
      (libraryPublicId, libAuthToken, orgPublicId, orgAuthToken) match { // assumes only one intent exists per request
        case (Some(libPubId), authTokenOpt, _, _) =>
          Some(AutoFollowLibrary(libPubId, authTokenOpt))
        case (_, _, Some(orgPublicId), Some(authToken)) =>
          Some(AutoJoinOrganization(orgPublicId, authToken))
        case _ => None
      }
    }.getOrElse(NoIntent)

    // Sorry, refactoring this is exceeding my mental stack, so keeping some original behavior:

    intent match {
      case JoinTwitterWaitlist => // Nothing for now
      case _ => // Anything BUT twitter waitlist
        if (!emailConfirmedAlready) {
          val unverifiedEmail = newIdentity.email.flatMap(EmailAddress.validate(_).toOption).getOrElse(emailAddress)
          if (mode == Prod) {
            // Do not sent the email in dev
            SafeFuture { userCommander.sendWelcomeEmail(user, withVerification = true, Some(unverifiedEmail)) }
          }
        } else {
          db.readWrite { implicit session =>
            emailAddressRepo.getByAddressAndUser(user.id.get, emailAddress) foreach { emailAddr =>
              userEmailAddressCommander.setAsPrimaryEmail(emailAddr)
            }
          }
          if (mode == Prod) {
            // Do not sent the email in dev
            SafeFuture { userCommander.sendWelcomeEmail(user, withVerification = false) }
          }
        }
    }

    val uri = intent match {
      case AutoFollowLibrary(libId, authTokenOpt) =>
        authCommander.autoJoinLib(user.id.get, libId, authTokenOpt)
        val url = Library.decodePublicId(libId).map { libraryId =>
          val library = db.readOnlyMaster { implicit session => libraryRepo.get(libraryId) }
          libPathCommander.getPathForLibrary(library)
        }.getOrElse("/")
        url
      case AutoJoinOrganization(orgPubId, authToken) =>
        authCommander.autoJoinOrg(user.id.get, orgPubId, authToken)
        val url = Organization.decodePublicId(orgPubId).map { orgId =>
          val handle = db.readOnlyMaster { implicit session => orgRepo.get(orgId) }.handle
          s"/${handle.value}"
        }.getOrElse("/")
        url
      case JoinTwitterWaitlist =>
        // Waitlist joining happens on this page, so nothing to do:
        "/twitter/thanks"
      case NoIntent =>
        request.session.get(SecureSocial.OriginalUrlKey).getOrElse {
          request.headers.get(USER_AGENT).flatMap { agentString =>
            val agent = UserAgent(agentString)
            if (agent.canRunExtensionIfUpToDate) Some("/install") else None
          } getOrElse "/" // In case the user signs up on a browser that doesn't support the extension
        }
    }

    request.session.get("kcid").foreach(saveKifiCampaignId(user.id.get, _))
    val discardedCookies = Seq("publicLibraryId", "intent", "libraryAuthToken", "inv", "publicOrgId", "orgAuthToken").map(n => DiscardingCookie(n))

    Authenticator.create(newIdentity).fold(
      error => Status(INTERNAL_SERVER_ERROR)("0"),
      authenticator => {
        val result = if (isFinalizedImmediately) {
          Redirect(uri)
        } else {
          Ok(Json.obj("uri" -> uri))
        }
        result.withCookies(authenticator.toCookie).discardingCookies(discardedCookies: _*)
          .withSession(request.session.setUserId(user.id.get))
      }
    )
  }

  private val socialFinalizeAccountForm = Form[SocialFinalizeInfo](
    mapping(
      "email" -> EmailAddress.formMapping.verifying("known_email_address", email => db.readOnlyMaster { implicit s =>
        val existing = emailAddressRepo.getByAddress(email) // todo(Léo / Andrew): only enforce this for verified emails?
        if (existing.nonEmpty) {
          log.warn("[social-finalize] Can't finalize because email is known: " + existing)
        }
        existing.isEmpty
      }),
      "firstName" -> text, // todo(ray/andrew): revisit non-empty requirement for twitter
      "lastName" -> optional(text),
      "password" -> optional(text),
      "picToken" -> optional(text),
      "picHeight" -> optional(number),
      "picWidth" -> optional(number),
      "cropX" -> optional(number),
      "cropY" -> optional(number),
      "cropSize" -> optional(number)
    )({ (email, fName, lName, pwd, picToken, picH, picW, cX, cY, cS) =>
        val allowedPassword = if (pwd.exists(p => AuthHelper.validatePwd(p))) {
          Some(pwd.get)
        } else {
          log.warn(s"[social-finalize] Rejected social password, generating one instead. Supplied password was ${pwd.map(_.length).getOrElse(0)} chars.")
          None
        }
        SocialFinalizeInfo(email = email.copy(address = email.address.trim), firstName = fName, lastName = lName.getOrElse(""), password = allowedPassword, picToken = picToken, picHeight = picH, picWidth = picW, cropX = cX, cropY = cY, cropSize = cS)
      })((sfi: SocialFinalizeInfo) =>
        Some((sfi.email, sfi.firstName, Option(sfi.lastName), sfi.password, sfi.picToken, sfi.picHeight, sfi.picWidth, sfi.cropX, sfi.cropY, sfi.cropSize)))
  )
  def doSocialFinalizeAccountAction(implicit request: MaybeUserRequest[JsValue]): Result = {
    socialFinalizeAccountForm.bindFromRequest.fold(
      formWithErrors => {
        log.warn("[social-finalize] Rejected social finalize because of form errors: " + formWithErrors.errors + " from request " + request.body + " from " + request.userAgentOpt)
        BadRequest(Json.obj("error" -> formWithErrors.errors.head.message))
      }, {
        case sfi: SocialFinalizeInfo =>
          handleSocialFinalizeInfo(sfi, None, None, None, None, isFinalizedImmediately = false)
      })
  }

  def doTokenFinalizeAccountAction(implicit request: MaybeUserRequest[JsValue]): Result = {
    request.body.asOpt[TokenFinalizeInfo] match {
      case None => BadRequest(Json.obj("error" -> "invalid_arguments"))
      case Some(info) => handleSocialFinalizeInfo(TokenFinalizeInfo.toSocialFinalizeInfo(info), info.libraryPublicId, None, info.orgPublicId, info.orgAuthToken, isFinalizedImmediately = false)
    }
  }

  def handleSocialFinalizeInfo(sfi: SocialFinalizeInfo,
    libraryPublicId: Option[PublicId[Library]],
    libAuthToken: Option[String],
    orgPublicId: Option[PublicId[Organization]],
    orgAuthToken: Option[String],
    isFinalizedImmediately: Boolean)(implicit request: MaybeUserRequest[_]): Result = {
    require(request.identityOpt.isDefined, "A social identity should be available in order to finalize social account")

    val identity = request.identityOpt.get
    if (identity.identityId.userId.trim.isEmpty) {
      throw new Exception(s"empty social id for $identity joining library $libraryPublicId with $sfi")
    }
    val inviteExtIdOpt: Option[ExternalId[Invitation]] = request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value))
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val (user, emailPassIdentity) = authCommander.finalizeSocialAccount(sfi, identity, inviteExtIdOpt)
    val emailConfirmedBySocialNetwork = identity.email.map(EmailAddress.validate).collect { case Success(validEmail) => validEmail.copy(address = validEmail.address.trim) }.exists(_.equalsIgnoreCase(sfi.email))
    if (user.state == UserStates.INCOMPLETE_SIGNUP) airbrake.notify(s"[finalizeSocialAccount] calling finishSignup without an active user! ${user.toString}")
    finishSignup(user, sfi.email, emailPassIdentity, emailConfirmedAlready = emailConfirmedBySocialNetwork, libraryPublicId = libraryPublicId, libAuthToken = libAuthToken,
      orgPublicId = orgPublicId, orgAuthToken = orgAuthToken, isFinalizedImmediately = isFinalizedImmediately)
  }

  private val userPassFinalizeAccountForm = Form[EmailPassFinalizeInfo](mapping(
    "firstName" -> nonEmptyText,
    "lastName" -> nonEmptyText,
    "picToken" -> optional(text),
    "picWidth" -> optional(number),
    "picHeight" -> optional(number),
    "cropX" -> optional(number),
    "cropY" -> optional(number),
    "cropSize" -> optional(number),
    "companyName" -> optional(text)
  )(EmailPassFinalizeInfo.apply)(EmailPassFinalizeInfo.unapply))
  def doUserPassFinalizeAccountAction(implicit request: UserRequest[JsValue]): Future[Result] = {
    userPassFinalizeAccountForm.bindFromRequest.fold(
      formWithErrors => {
        log.warn("[userpass-finalize] Rejected user pass finalize because of form errors: " + formWithErrors.errors)
        Future.successful(Forbidden(Json.obj("error" -> "user_exists_failed_auth")))
      }, {
        case efi: EmailPassFinalizeInfo =>
          handleEmailPassFinalizeInfo(efi, None, None, None, None)
      }
    )
  }

  def handleEmailPassFinalizeInfo(efi: EmailPassFinalizeInfo, libraryPublicId: Option[PublicId[Library]], libAuthToken: Option[String], orgPublicId: Option[PublicId[Organization]], orgAuthToken: Option[String])(implicit request: UserRequest[JsValue]): Future[Result] = {
    val inviteExtIdOpt: Option[ExternalId[Invitation]] = request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value))
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    authCommander.finalizeEmailPassAccount(efi, request.userId, request.user.externalId, request.identityOpt, inviteExtIdOpt).map {
      case (user, email, newIdentity) =>
        val verifiedEmail = verifySignupEmail(request.userId, email, libraryPublicId, libAuthToken)
        if (user.state == UserStates.INCOMPLETE_SIGNUP) airbrake.notify(s"[finalizeEmailPassAccount] calling finishSignup without an active user! ${user.toString}")
        finishSignup(user, email, newIdentity, emailConfirmedAlready = verifiedEmail, libraryPublicId = libraryPublicId, libAuthToken = libAuthToken, orgPublicId, orgAuthToken, isFinalizedImmediately = false)
    }
  }

  private def verifySignupEmail(userId: Id[User], email: EmailAddress, libraryPublicId: Option[PublicId[Library]], libAuthToken: Option[String]): Boolean = {
    libAuthToken.exists { authToken =>
      libraryPublicId.exists { publicLibId =>
        Library.decodePublicId(publicLibId).toOption.exists { libId =>
          db.readWrite { implicit session =>
            libraryInviteRepo.getByLibraryIdAndAuthToken(libId, authToken).exists { libraryInvite =>
              libraryInvite.emailAddress.exists { sentTo =>
                (sentTo == email) && emailAddressRepo.getByAddressAndUser(userId, email).exists { emailRecord =>
                  userEmailAddressCommander.saveAsVerified(emailRecord)
                  true
                }
              }
            }
          }
        }
      }
    }
  }

  private def getResetEmailAddresses(email: EmailAddress): Option[(Id[User], Option[EmailAddress])] = {
    db.readOnlyMaster { implicit s =>
      emailAddressRepo.getByAddress(email).map(_.userId) map { userId =>
        (userId, Try(emailAddressRepo.getByUser(userId)).toOption)
      }
    }
  }

  def doForgotPassword(implicit request: Request[JsValue]): Future[Result] = {
    (request.body \ "email").asOpt[EmailAddress].map { suppliedEmailAddress =>
      db.readOnlyMaster { implicit session =>
        getResetEmailAddresses(suppliedEmailAddress)
      } match {
        case Some((userId, resetEmailAddressOpt)) =>
          val emailAddresses = Set(suppliedEmailAddress) ++ resetEmailAddressOpt
          val emailsF = Future.sequence(emailAddresses.map { email => resetPasswordEmailSender.sendToUser(userId, email) }.toSeq)
          emailsF.map { e =>
            Ok(Json.obj("addresses" -> emailAddresses.map {
              case email if email == suppliedEmailAddress => suppliedEmailAddress.address
              case email => AuthController.obscureEmailAddress(email.address)
            }))
          }
        case _ =>
          log.warn(s"Could not reset password because supplied email address $suppliedEmailAddress not found.")
          Future.successful(BadRequest(Json.obj("error" -> "no_account")))
      }
    } getOrElse Future.successful(BadRequest("0"))
  }

  def doSetPassword(implicit request: Request[JsValue]): Result = {
    (for {
      code <- (request.body \ "code").asOpt[String]
      password <- (request.body \ "password").asOpt[String].filter(_.length >= 7)
    } yield {
      db.readWrite { implicit s =>
        passwordResetRepo.getByToken(code) match {
          case Some(pr) if passwordResetRepo.useResetToken(code, request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)) =>
            emailAddressRepo.getByAddress(pr.sentTo).foreach(userEmailAddressCommander.saveAsVerified(_)) // mark email address as verified
            val results = for (sui <- socialRepo.getByUser(pr.userId) if sui.networkType == SocialNetworks.FORTYTWO) yield {
              val pwdInfo = current.plugin[PasswordHasher].get.hash(password)
              UserService.save(UserIdentity(
                userId = sui.userId,
                socialUser = sui.credentials.get.copy(
                  passwordInfo = Some(pwdInfo)
                )
              ))
              val updated = userCredRepo.findByUserIdOpt(sui.userId.get) map { userCred =>
                userCredRepo.save(userCred.withCredentials(pwdInfo.password))
              }
              log.info(s"[doSetPassword] UserCreds updated=${updated.map(c => s"id=${c.id} userId=${c.userId}")}")
              authenticateUser(sui.userId.get, onError = { error =>
                throw error
              }, onSuccess = { authenticator =>
                Ok(Json.obj("uri" -> com.keepit.controllers.website.routes.HomeController.home.url))
                  .withSession(request.session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
                  .withCookies(authenticator.toCookie)
              })
            }
            results.headOption.getOrElse {
              Ok(Json.obj("error" -> "invalid_user"))
            }
          case Some(pr) if pr.state == PasswordResetStates.ACTIVE || pr.state == PasswordResetStates.INACTIVE =>
            Ok(Json.obj("error" -> "expired"))
          case Some(pr) if pr.state == PasswordResetStates.USED =>
            Ok(Json.obj("error" -> "already_used"))
          case _ =>
            Ok(Json.obj("error" -> "invalid_code"))
        }
      }
    }) getOrElse BadRequest("0")
  }

  private def authenticateUser[T](userId: Id[User], onError: Error => T, onSuccess: Authenticator => T) = {
    val identity = db.readOnlyMaster { implicit session =>
      val suis = socialRepo.getByUser(userId)
      val sui = socialRepo.getByUser(userId).find(_.networkType == SocialNetworks.FORTYTWO).getOrElse(suis.head)
      sui.credentials.get
    }
    Authenticator.create(identity).fold(onError, onSuccess)
  }

  def doVerifyEmail(code: String)(implicit request: MaybeUserRequest[_]): Result = {
    db.readWrite { implicit s =>
      userEmailAddressCommander.verifyEmailAddress(code) map {
        case (address, _) if userRepo.get(address.userId).state == UserStates.PENDING =>
          Redirect(s"/?m=1")
        case (address, true) if request.userIdOpt.isEmpty || (request.userIdOpt.isDefined && request.userIdOpt.get.id == address.userId) =>
          // first time being used, not logged in OR logged in as correct user
          authenticateUser(address.userId,
            error => throw error,
            authenticator => {
              val resp = if (request.userAgentOpt.exists(_.isMobile)) {
                Ok(views.html.mobile.mobileAppRedirect("/email/verified"))
              } else if (kifiInstallationRepo.all(address.userId, Some(KifiInstallationStates.INACTIVE)).isEmpty) { // todo: factor out
                // user has no installations
                Redirect("/install")
              } else {
                Redirect(s"/?m=1")
              }
              resp.withSession(request.session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
                .withCookies(authenticator.toCookie)
            }
          )
        case (address, false) if request.userIdOpt.isDefined && request.userIdOpt.get.id == address.userId =>
          Redirect(s"/?m=1")
        case (address, _) =>
          val user = userRepo.get(address.userId)
          Ok(views.html.website.verifyEmailThanks(address.address.address, user.firstName, secureSocialClientIds))
      }
    }.getOrElse {
      BadRequest(views.html.website.verifyEmailError(error = "invalid_code", secureSocialClientIds))
    }
  }

  def doUploadBinaryPicture(implicit request: MaybeUserRequest[play.api.libs.Files.TemporaryFile]): Result = {
    request.userOpt.orElse(request.identityOpt) match {
      case Some(userInfo) =>
        s3ImageStore.uploadTemporaryPicture(request.body.file) match {
          case Success((token, pictureUrl)) =>
            Ok(Json.obj("token" -> token, "url" -> pictureUrl))
          case Failure(ex) =>
            airbrake.notify("Couldn't upload temporary picture (xhr direct) for $userInfo", ex)
            BadRequest(JsNumber(0))
        }
      case None => Forbidden(JsNumber(0))
    }
  }

  def doUploadFormEncodedPicture(implicit request: MaybeUserRequest[MultipartFormData[play.api.libs.Files.TemporaryFile]]) = {
    request.userOpt.orElse(request.identityOpt) match {
      case Some(_) =>
        request.body.file("picture").map { picture =>
          s3ImageStore.uploadTemporaryPicture(picture.ref.file) match {
            case Success((token, pictureUrl)) =>
              Ok(Json.obj("token" -> token, "url" -> pictureUrl))
            case Failure(ex) =>
              airbrake.notify(AirbrakeError(ex, Some("Couldn't upload temporary picture (form encoded)")))
              BadRequest(JsNumber(0))
          }
        } getOrElse {
          BadRequest(JsNumber(0))
        }
      case None => Forbidden(JsNumber(0))
    }
  }

  val signUpUrl = com.keepit.controllers.core.routes.AuthController.signupPage().url

  def doAccessTokenSignup(providerName: String, oauth2InfoOrig: OAuth2Info)(implicit request: Request[JsValue]): Future[Result] = {
    oauth2ProviderRegistry.get(ProviderIds.toProviderId(providerName)) match {
      case None =>
        log.error(s"[accessTokenSignup($providerName)] Failed to retrieve provider; request=${request.body}")
        Future.successful(BadRequest(Json.obj("error" -> "invalid_arguments")))
      case Some(provider) =>
        provider.getUserProfileInfo(OAuth2AccessToken(oauth2InfoOrig.accessToken)) flatMap { profileInfo =>
          val filledUser = SecureSocialAdaptor.toSocialUser(profileInfo, AuthenticationMethod.OAuth2)
          val longTermTokenInfoF = provider.exchangeLongTermToken(oauth2InfoOrig) recover {
            case t: Throwable =>
              airbrake.notify(s"[accessTokenSignup($providerName)] Caught Exception($t) during token exchange; token=${oauth2InfoOrig}; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}")
              OAuth2TokenInfo.fromOAuth2Info(oauth2InfoOrig)
          }
          longTermTokenInfoF map { oauth2InfoNew =>
            authCommander.signupWithTrustedSocialUser(providerName, filledUser.copy(oAuth2Info = Some(oauth2InfoNew)), signUpUrl)
          }
        } recover {
          case t: Throwable =>
            airbrake.notify(s"[accessTokenSignup($providerName)] Caught Exception($t) during getUserProfileInfo; token=${oauth2InfoOrig}; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}")
            BadRequest(Json.obj("error" -> "invalid_token"))
        }
    }
  }

  def doOAuth1TokenSignup(providerName: String, oauth1Info: OAuth1Info)(implicit request: Request[JsValue]): Future[Result] = {
    oauth1ProviderRegistry.get(ProviderIds.toProviderId(providerName)) match {
      case None =>
        log.error(s"[accessTokenSignup($providerName)] Failed to retrieve provider; request=${request.body}")
        Future.successful(BadRequest(Json.obj("error" -> "invalid_arguments")))
      case Some(provider) =>
        provider.getUserProfileInfo(oauth1Info) map { info =>
          val filledUser = SecureSocialAdaptor.toSocialUser(info, AuthenticationMethod.OAuth1)
          authCommander.signupWithTrustedSocialUser(providerName, filledUser.copy(oAuth1Info = Some(oauth1Info)), signUpUrl)
        } recover {
          case t: Throwable =>
            airbrake.notify(s"[accessTokenSignup($providerName)] Caught Exception($t) during getUserProfileInfo; token=${oauth1Info}; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}")
            BadRequest(Json.obj("error" -> "invalid_token"))
        }
    }
  }

  def doAccessTokenLogin(providerName: String, oAuth2Info: OAuth2Info)(implicit request: Request[JsValue]): Future[Result] = {
    oauth2ProviderRegistry.get(ProviderIds.toProviderId(providerName)) match {
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "invalid_arguments")))
      case Some(provider) =>
        provider.getUserProfileInfo(OAuth2AccessToken(oAuth2Info.accessToken)) map { info =>
          val socialUser = SecureSocialAdaptor.toSocialUser(info, AuthenticationMethod.OAuth2)
          authCommander.loginWithTrustedSocialIdentity(socialUser.identityId)
        } recover {
          case t: Throwable =>
            log.error(s"[accessTokenLogin($providerName)] Caught Exception($t) during getUserProfileInfo; token=${oAuth2Info}; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}")
            BadRequest(Json.obj("error" -> "invalid_token"))
        }
    }
  }

  def doOAuth1TokenLogin(providerName: String, oauth1Info: OAuth1Info)(implicit request: Request[JsValue]): Future[Result] = {
    oauth1ProviderRegistry.get(ProviderIds.toProviderId(providerName)) match {
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "invalid_arguments")))
      case Some(provider) =>
        provider.getUserProfileInfo(oauth1Info) map { info =>
          val socialUser = SecureSocialAdaptor.toSocialUser(info, AuthenticationMethod.OAuth1)
          authCommander.loginWithTrustedSocialIdentity(socialUser.identityId)
        } recover {
          case t: Throwable =>
            log.error(s"[doOAuth1TokenLogin($providerName)] Caught Exception($t) during getUserProfileInfo; token=${oauth1Info}; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}")
            BadRequest(Json.obj("error" -> "invalid_token"))
        }

    }
  }

}
