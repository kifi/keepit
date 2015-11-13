package com.keepit.controllers.core

import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.http._
import com.keepit.commanders.emails.ResetPasswordEmailSender
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.net.UserAgent
import com.google.inject.Inject
import com.keepit.common.oauth.adaptor.SecureSocialAdaptor
import com.keepit.common.oauth.{ OAuth1ProviderRegistry, OAuth2AccessToken, ProviderIds, OAuth2ProviderRegistry }
import com.keepit.common.service.IpAddress
import com.keepit.payments.{ CreditRewardCommander, CreditCode }
import play.api.Mode._
import play.api.mvc._
import play.api.http.{ Status, HeaderNames }
import securesocial.core._
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.commanders._
import com.keepit.social._
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
import com.keepit.common.controller.{ NonUserRequest, MaybeUserRequest, UserRequest }
import com.keepit.model.Invitation
import com.keepit.social.UserIdentity
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.performance._
import scala.concurrent.Future
import com.keepit.heimdal.{ UserEvent, UserEventTypes, HeimdalContext, HeimdalServiceClient, HeimdalContextBuilderFactory }
import com.keepit.inject.FortyTwoConfig
import com.keepit.common.core._

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
    userEmailAddressRepo: UserEmailAddressRepo,
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
    heimdal: HeimdalServiceClient,
    creditRewardCommander: CreditRewardCommander,
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

  def handleEmailPasswordSuccessForm(emailAddress: EmailAddress, passwordOpt: Option[String])(implicit request: MaybeUserRequest[_]): Result = timing(s"handleEmailPasswordSuccess($emailAddress)") {
    val hasher = Registry.hashers.currentHasher
    val session = request.session
    val home = com.keepit.controllers.website.routes.HomeController.home()
    UserService.find(IdentityId(emailAddress.address, SocialNetworks.EMAIL.authProvider)) match {
      case Some(identity @ UserIdentity(Some(userId), socialUser)) => {
        // User exists with these credentials
        val matchesOpt = passwordOpt.map(p => hasher.matches(identity.passwordInfo.get, p))
        if (matchesOpt.exists(p => p)) {
          Authenticator.create(identity).fold(
            error => Status(INTERNAL_SERVER_ERROR)("0"),
            authenticator => {
              val finalized = db.readOnlyMaster { implicit session =>
                userRepo.get(userId).state != UserStates.INCOMPLETE_SIGNUP
              }
              if (finalized) {
                Ok(Json.obj("uri" -> session.get(SecureSocial.OriginalUrlKey).getOrElse(home.url).asInstanceOf[String])) // todo(ray): uri not relevant for mobile
                  .withSession((session - SecureSocial.OriginalUrlKey).setUserId(userId))
                  .withCookies(authenticator.toCookie)
              } else {
                Ok(Json.obj("success" -> true))
                  .withSession(session.setUserId(userId))
                  .withCookies(authenticator.toCookie)
              }
            }
          )
        } else {
          Forbidden(Json.obj("error" -> "user_exists_failed_auth"))
        }
      }

      case _ => {
        val pInfo = passwordOpt.map(p => hasher.hash(p))
        val (newIdentity, userId) = authCommander.saveUserPasswordIdentity(None, emailAddress, pInfo, firstName = "", lastName = "", isComplete = false)
        Authenticator.create(newIdentity).fold(
          error => Status(INTERNAL_SERVER_ERROR)("0"),
          authenticator =>
            Ok(Json.obj("success" -> true))
              .withSession(session.setUserId(userId))
              .withCookies(authenticator.toCookie)
        )
      }
    }
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
  case class ApplyCreditCode(creditCode: CreditCode) extends PostRegIntent
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
    isFinalizedImmediately: Boolean)(implicit request: MaybeUserRequest[_], context: HeimdalContext): Result = {

    // This is a Big ball of mud. Hopefully we can restore a bit of sanity.
    // This function does some end-of-the-line wiring after registration. We support registrations directly from an API,
    // oauth tokens from social networks, and a several-step HTTP flow.
    // Right now, we need to automatically do tasks based on an `intent` (ie, auto-follow library, auto-friend a user, etc)

    // You'll notice that the signature of this function is weird. Sigh. Some callers don't have cookies, so we can't read
    // the `intent` from the cookies. Hence, ambiguity. In practice, we should find an intent in a cookie, or libraryPublicId is set
    // (meaning, "follow" intent), or nothing. Below is an attempt to unify state:

    val intent: PostRegIntent = {
      val intentFromCookie: Option[PostRegIntent] = request.cookies.get("intent").map(_.value).flatMap {
        case "applyCredit" =>
          request.cookies.get("creditCode").map(_.value).map {
            case code if code.nonEmpty =>
              ApplyCreditCode(CreditCode.normalize(code))
          }
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

      intentFromCookie.orElse {
        (libraryPublicId, libAuthToken, orgPublicId, orgAuthToken) match { // assumes only one intent exists per request
          case (Some(libPubId), authTokenOpt, _, _) =>
            Some(AutoFollowLibrary(libPubId, authTokenOpt))
          case (_, _, Some(orgPubId), Some(authToken)) =>
            Some(AutoJoinOrganization(orgPubId, authToken))
          case _ => None
        }
      }.getOrElse(NoIntent)
    }

    // Sorry, refactoring this is exceeding my mental stack, so keeping some original behavior:

    intent match {
      case JoinTwitterWaitlist => // Nothing for now
      case _ => // Anything BUT twitter waitlist
        db.readWrite(attempts = 3) { implicit session =>
          userEmailAddressRepo.getByAddressAndUser(user.id.get, emailAddress) foreach { emailAddr =>
            userEmailAddressCommander.setAsPrimaryEmail(emailAddr)
          }
        }
        if (mode == Prod) {
          // Do not sent the email in dev
          SafeFuture { userCommander.sendWelcomeEmail(user.id.get, withVerification = !emailConfirmedAlready, Some(emailAddress)) }
        }
        val completedSignupEvent = UserEvent(user.id.get, context, UserEventTypes.COMPLETED_SIGNUP)
        heimdal.trackEvent(completedSignupEvent)
    }

    type IntentAction = PostRegIntent ?=> Unit

    val createUserValues: IntentAction = {
      case o: AutoJoinOrganization =>
      case l: AutoFollowLibrary =>
      case _ =>
      // This enables the FTUI for new users
      //        db.readWrite { implicit session =>
      //          userValueRepo.setValue(user.id.get, UserValueName.HAS_SEEN_FTUE, false)
      //        }
    }

    val performPrimaryIntentAction: IntentAction = {
      case ApplyCreditCode(creditCode) =>
        db.readWrite(attempts = 3) { implicit session =>
          userValueRepo.setValue(user.id.get, UserValueName.STORED_CREDIT_CODE, creditCode.value)
        }
      case AutoFollowLibrary(libId, authTokenOpt) =>
        authCommander.autoJoinLib(user.id.get, libId, authTokenOpt)
      case AutoJoinOrganization(orgPubId, authToken) =>
        authCommander.autoJoinOrg(user.id.get, orgPubId, authToken)
    }

    Seq(createUserValues, performPrimaryIntentAction)
      .map(_.lift)
      .foreach(i => i(intent))

    val uri = intent match {
      case ApplyCreditCode(creditCode) =>
        "/teams/new"
      case AutoFollowLibrary(libId, authTokenOpt) =>
        val url = Library.decodePublicId(libId).map { libraryId =>
          val library = db.readOnlyMaster { implicit session => libraryRepo.get(libraryId) }
          libPathCommander.getPathForLibrary(library)
        }.getOrElse("/")
        url
      case AutoJoinOrganization(orgPubId, authToken) =>
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
    val discardedCookies = Seq("publicLibraryId", "intent", "libraryAuthToken", "inv", "publicOrgId", "orgAuthToken", "creditCode").map(n => DiscardingCookie(n))

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
        val existing = userEmailAddressRepo.getByAddress(email) // todo(Léo / Andrew): only enforce this for verified emails?
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

    log.info(s"Handling SocialFinalizeInfo: $sfi")

    val identity = request.identityOpt.get
    if (identity.identityId.userId.trim.isEmpty) {
      throw new Exception(s"empty social id for $identity joining library $libraryPublicId with $sfi")
    }
    val inviteExtIdOpt: Option[ExternalId[Invitation]] = request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value))
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val (user, emailPassIdentity) = authCommander.finalizeSocialAccount(sfi, identity, inviteExtIdOpt)
    val emailConfirmedBySocialNetwork = identity.email.map(EmailAddress.validate).collect { case Success(validEmail) => validEmail.copy(address = validEmail.address.trim) }.exists(_.equalsIgnoreCase(sfi.email))
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
        finishSignup(user, email, newIdentity, emailConfirmedAlready = verifiedEmail, libraryPublicId = libraryPublicId, libAuthToken = libAuthToken, orgPublicId, orgAuthToken, isFinalizedImmediately = false)
    }
  }

  private def verifySignupEmail(userId: Id[User], email: EmailAddress, libraryPublicId: Option[PublicId[Library]], libAuthToken: Option[String]): Boolean = {
    libAuthToken.exists { authToken =>
      libraryPublicId.exists { publicLibId =>
        Library.decodePublicId(publicLibId).toOption.exists { libId =>
          db.readWrite(attempts = 3) { implicit session =>
            libraryInviteRepo.getByLibraryIdAndAuthToken(libId, authToken).exists { libraryInvite =>
              libraryInvite.emailAddress.exists { sentTo =>
                (sentTo == email) && userEmailAddressRepo.getByAddressAndUser(userId, email).exists { emailRecord =>
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
      userEmailAddressRepo.getByAddress(email).map(_.userId) map { userId =>
        (userId, Try(userEmailAddressRepo.getByUser(userId)).toOption)
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
          val emailsF = Future.sequence(emailAddresses.map { email => resetPasswordEmailSender.sendToUser(userId, email) })
          emailsF.map { e =>
            Ok(Json.obj("addresses" -> emailAddresses.toSeq.sortBy(_.address).map {
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
      val ip = IpAddress.fromRequest(request)
      userCommander.resetPassword(code, ip, password) match {
        case Right(userId) => db.readOnlyMaster { implicit session =>
          authenticateUser(userId, onError = { error =>
            throw error
          }, onSuccess = { authenticator =>
            Ok(Json.obj("uri" -> com.keepit.controllers.website.routes.HomeController.home.url))
              .withSession(request.session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
              .withCookies(authenticator.toCookie)
          })
        }
        case Left(errorCode) => Ok(Json.obj("error" -> errorCode))
      }
    }) getOrElse BadRequest("0")
  }

  private def authenticateUser[T](userId: Id[User], onError: Error => T, onSuccess: Authenticator => T)(implicit session: RSession) = {
    val user = userRepo.get(userId)
    val emailAddress = userEmailAddressRepo.getByUser(userId)
    val userCred = userCredRepo.findByUserIdOpt(userId)
    val identity = UserIdentity(user, emailAddress, userCred)
    Authenticator.create(identity).fold(onError, onSuccess)
  }

  def doVerifyEmail(code: EmailVerificationCode)(implicit request: MaybeUserRequest[_]): Result = {
    db.readOnlyMaster { implicit session => userEmailAddressRepo.getByCode(code) } match {
      case Some(email) =>
        verifyEmailForMaybeUser(email)
      case None =>
        BadRequest(views.html.website.verifyEmailError(error = "invalid_code", secureSocialClientIds)) //#verifymail case 1
    }
  }

  private def verifyEmailForMaybeUser(email: UserEmailAddress)(implicit request: MaybeUserRequest[_]): Result = request match {
    case userRequest: UserRequest[_] => verifyEmailForUser(email, userRequest)
    case nonUserRequest: NonUserRequest[_] => verifyEmailForNonUser(email, nonUserRequest)
  }

  private def verifyEmailForUser(email: UserEmailAddress, request: UserRequest[_]): Result = {
    if (request.userId == email.userId) {
      unsafeVerifyEmail(email)
      val userId = email.userId
      val installations = db.readOnlyReplica { implicit s => kifiInstallationRepo.all(userId) }
      if (!installations.exists { installation => installation.platform == KifiInstallationPlatform.Extension }) {
        Redirect("/install") //#verifymail case 2
      } else {
        Redirect(s"/?m=1") //#verifymail case 3
      }
    } else BadRequest(views.html.website.verifyEmailError(error = "invalid_user", secureSocialClientIds)) //#verifymail case 4
  }

  private def verifyEmailForNonUser(email: UserEmailAddress, request: NonUserRequest[_]): Result = request.userAgentOpt match {
    case Some(agent) if agent.isMobile => //let it pass ...
      unsafeVerifyEmail(email)
      Ok(views.html.mobile.mobileAppRedirect("/email/verified")) //#verifymail case 5
    case _ =>
      val newSession = request.session + (SecureSocial.OriginalUrlKey -> request.path)
      Redirect("/login").withSession(newSession) //#verifymail case 6
  }

  private def unsafeVerifyEmail(email: UserEmailAddress): Unit = {
    db.readWrite(attempts = 3) { implicit s => userEmailAddressCommander.saveAsVerified(email) }
    userEmailAddressCommander.autoJoinOrgViaEmail(email)
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
              airbrake.notify(s"[accessTokenSignup($providerName)] Caught Exception($t) during token exchange; token=$oauth2InfoOrig; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}")
              OAuth2TokenInfo.fromOAuth2Info(oauth2InfoOrig)
          }
          longTermTokenInfoF map { oauth2InfoNew =>
            authCommander.signupWithTrustedSocialUser(providerName, filledUser.copy(oAuth2Info = Some(oauth2InfoNew)), signUpUrl)
          }
        } recover {
          case t: Throwable =>
            airbrake.notify(s"[accessTokenSignup($providerName)] Caught Exception($t) during getUserProfileInfo; token=$oauth2InfoOrig; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}")
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
            airbrake.notify(s"[accessTokenSignup($providerName)] Caught Exception($t) during getUserProfileInfo; token=$oauth1Info; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}")
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
            log.error(s"[accessTokenLogin($providerName)] Caught Exception($t) during getUserProfileInfo; token=$oAuth2Info; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}")
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
            log.error(s"[doOAuth1TokenLogin($providerName)] Caught Exception($t) during getUserProfileInfo; token=$oauth1Info; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}")
            BadRequest(Json.obj("error" -> "invalid_token"))
        }

    }
  }

}
