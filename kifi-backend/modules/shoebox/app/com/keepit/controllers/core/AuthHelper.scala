package com.keepit.controllers.core

import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.http._
import com.keepit.commanders.emails.ResetPasswordEmailSender
import com.keepit.common.crypto.{ PublicIdGenerator, PublicIdConfiguration, PublicId }
import com.keepit.common.net.UserAgent
import com.google.inject.Inject
import com.keepit.common.oauth.{ SlackIdentity, OAuth1ProviderRegistry, ProviderIds, OAuth2ProviderRegistry }
import com.keepit.common.service.IpAddress
import com.keepit.payments.CreditCode
import com.keepit.slack.models.{ SlackTeamMembershipRepo, SlackTeamId }
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
import com.keepit.common.time._
import play.api.data._
import play.api.data.Forms._
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import scala.util.{ Try, Failure, Success }
import play.api.mvc.Result
import play.api.libs.json.{ Json, JsNumber, JsValue }
import play.api.mvc.DiscardingCookie
import play.api.mvc.Cookie
import com.keepit.common.mail.EmailAddress
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
    userEmailAddressRepo: UserEmailAddressRepo,
    userValueRepo: UserValueRepo,
    kifiInstallationRepo: KifiInstallationRepo, // todo: factor out
    orgRepo: OrganizationRepo,
    slackMembershipRepo: SlackTeamMembershipRepo,
    ktlRepo: KeepToLibraryRepo,
    s3ImageStore: S3ImageStore,
    libPathCommander: PathCommander,
    userEmailAddressCommander: UserEmailAddressCommander,
    userCommander: UserCommander,
    orgDomainOwnershipCommander: OrganizationDomainOwnershipCommander,
    orgMembershipCommander: OrganizationMembershipCommander,
    permissionCommander: PermissionCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    heimdal: HeimdalServiceClient,
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
    authCommander.getUserIdentity(IdentityId(emailAddress.address, SocialNetworks.EMAIL.authProvider)) match {
      case Some(identity @ UserIdentity(_, Some(userId))) => {
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
                Ok(Json.obj("uri" -> session.get(SecureSocial.OriginalUrlKey).getOrElse(home.url).asInstanceOf[String]))
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
  case class AutoFollowLibrary(libraryId: Id[Library], authToken: Option[String]) extends PostRegIntent
  case class AutoJoinOrganization(organizationId: Id[Organization], authToken: String) extends PostRegIntent
  case class AutoJoinKeep(keepId: Id[Keep], authTokenOpt: Option[String]) extends PostRegIntent
  case object JoinTwitterWaitlist extends PostRegIntent
  case class Slack(slackTeamId: Option[SlackTeamId]) extends PostRegIntent
  case object NoIntent extends PostRegIntent

  def finishSignup(user: User,
    emailAddress: EmailAddress,
    newIdentity: Identity,
    emailConfirmedAlready: Boolean,
    modelPublicId: Option[String],
    authTokenFromQueryString: Option[String],
    isFinalizedImmediately: Boolean)(implicit request: MaybeUserRequest[_], context: HeimdalContext): Result = {

    // This is a Big ball of mud. Hopefully we can restore a bit of sanity.
    // This function does some end-of-the-line wiring after registration. We support registrations directly from an API,
    // oauth tokens from social networks, and a several-step HTTP flow.
    // Right now, we need to automatically do tasks based on an `intent` (ie, auto-follow library, auto-friend a user, etc)

    // You'll notice that the signature of this function is weird. Sigh. Some callers don't have cookies, so we can't read
    // the `intent` from the cookies. Hence, ambiguity. In practice, we should find an intent in a cookie, or libraryPublicId is set
    // (meaning, "follow" intent), or nothing. Below is an attempt to unify state:

    val modelPubIdFromCookie = request.cookies.get("modelPubId").map(_.value)
    val authTokenFromCookie = request.cookies.get("authToken").map(_.value)
    def generateRegIntent[T](pubIdOpt: Option[String], authTokenOpt: Option[String], generator: PublicIdGenerator[T]): Option[PostRegIntent] = {
      generator match {
        case Library => pubIdOpt.flatMap(Library.validatePublicId(_).flatMap(Library.decodePublicId).toOption).map(id => AutoFollowLibrary(id, authTokenOpt))
        case Organization =>
          for {
            pubId <- pubIdOpt
            orgId <- Organization.validatePublicId(pubId).flatMap(Organization.decodePublicId).toOption
            authToken <- authTokenOpt
          } yield AutoJoinOrganization(orgId, authToken)
        case Keep => pubIdOpt.flatMap(Keep.validatePublicId(_).flatMap(Keep.decodePublicId).toOption).map(id => AutoJoinKeep(id, authTokenOpt))
        case _ => None
      }
    }

    val slackTeamIdFromCookie = request.cookies.get("slackTeamId").map(_.value).map(SlackTeamId(_))

    val intent: PostRegIntent = {
      val intentFromCookie: Option[PostRegIntent] = request.cookies.get("intent").map(_.value).flatMap {
        case "applyCredit" =>
          request.cookies.get("creditCode").map(_.value).map {
            case code if code.nonEmpty =>
              ApplyCreditCode(CreditCode.normalize(code))
          }
        case "follow" => generateRegIntent[Library](modelPubIdFromCookie, authTokenFromCookie, Library)
        case "joinOrg" => generateRegIntent[Organization](modelPubIdFromCookie, authTokenFromCookie, Organization)
        case "joinKeep" => generateRegIntent[Keep](modelPubIdFromCookie, authTokenFromCookie, Keep)
        case "waitlist" => Some(JoinTwitterWaitlist)
        case "slack" => Some(Slack(slackTeamIdFromCookie))
        case _ => None
      }

      lazy val intentFromQueryString = {
        val generators: Stream[PublicIdGenerator[_]] = Stream(Library, Organization, Keep)
        generators.flatMap { generator => generateRegIntent(modelPublicId, authTokenFromQueryString, generator) }.headOption
      }

      intentFromCookie orElse intentFromQueryString getOrElse NoIntent

    }

    // Sorry, refactoring this is exceeding my mental stack, so keeping some original behavior:

    intent match {
      case JoinTwitterWaitlist => // Nothing for now
      case _ => // Anything BUT twitter waitlist
        db.readWrite(attempts = 3) { implicit session =>
          userEmailAddressRepo.getByAddressAndUser(user.id.get, emailAddress) foreach { emailAddr =>
            userEmailAddressCommander.setAsPrimaryEmail(emailAddr)
          }
          if (mode == Prod) {
            // Do not sent the email in dev
            SafeFuture {
              userCommander.sendWelcomeEmail(user.id.get, withVerification = !emailConfirmedAlready, Some(emailAddress))
            }
          }
          if (slackMembershipRepo.getByUserId(user.id.get).nonEmpty) userValueRepo.setValue(user.id.get, UserValueName.SHOW_SLACK_CREATE_TEAM_POPUP, true)
          val completedSignupEvent = UserEvent(user.id.get, context, UserEventTypes.COMPLETED_SIGNUP)
          heimdal.trackEvent(completedSignupEvent)
        }
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
      case AutoJoinKeep(keepId, authTokenOpt) =>
        authCommander.autoJoinKeep(user.id.get, keepId, authTokenOpt)
    }

    Seq(createUserValues, performPrimaryIntentAction)
      .map(_.lift)
      .foreach(i => i(intent))

    val uri = intent match {
      case Slack(slackTeamId) =>
        com.keepit.controllers.core.routes.AuthController.startWithSlack(slackTeamId).url
      case ApplyCreditCode(creditCode) =>
        "/teams/new"
      case AutoFollowLibrary(libId, authTokenOpt) =>
        val library = db.readOnlyMaster { implicit session => libraryRepo.get(libId) }
        libPathCommander.getPathForLibrary(library)
      case AutoJoinOrganization(orgId, authToken) =>
        val handle = db.readOnlyMaster { implicit session => orgRepo.get(orgId) }.handle
        s"/${handle.value}"
      case JoinTwitterWaitlist =>
        // Waitlist joining happens on this page, so nothing to do:
        "/twitter/thanks"
      case AutoJoinKeep(_, _) | NoIntent =>
        request.session.get(SecureSocial.OriginalUrlKey).getOrElse {
          request.headers.get(USER_AGENT).flatMap { agentString =>
            val agent = UserAgent(agentString)
            if (agent.canRunExtensionIfUpToDate) Some("/install") else None
          } getOrElse "/" // In case the user signs up on a browser that doesn't support the extension
        }
    }

    request.session.get("kcid").foreach(saveKifiCampaignId(user.id.get, _))
    val discardedCookies = Seq("intent", "modelPubId", "authToken", "inv", "creditCode").map(n => DiscardingCookie(n))

    Authenticator.create(newIdentity).fold(
      error => Status(INTERNAL_SERVER_ERROR)("0"),
      authenticator => {
        val result = if (isFinalizedImmediately) {
          Redirect(uri)
        } else {
          Ok(Json.obj("uri" -> uri))
        }
        result.withCookies(authenticator.toCookie).discardingCookies(discardedCookies: _*).withCookies(slackTeamIdFromCookie.map(id => Cookie("slackTeamId", id.value)).toSeq: _*)
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
          handleSocialFinalizeInfo(sfi, None, None, isFinalizedImmediately = false)
      })
  }

  def doTokenFinalizeAccountAction(implicit request: MaybeUserRequest[JsValue]): Result = {
    request.body.asOpt[TokenFinalizeInfo] match {
      case None => BadRequest(Json.obj("error" -> "invalid_arguments"))
      case Some(info) => handleSocialFinalizeInfo(TokenFinalizeInfo.toSocialFinalizeInfo(info), info.modelPublicId, info.authToken, isFinalizedImmediately = false)
    }
  }

  def handleSocialFinalizeInfo(sfi: SocialFinalizeInfo,
    modelPublicId: Option[String],
    authToken: Option[String],
    isFinalizedImmediately: Boolean)(implicit request: MaybeUserRequest[_]): Result = {
    val identityOpt = request.identityId.flatMap(authCommander.getUserIdentity)
    require(identityOpt.isDefined, "A social identity should be available in order to finalize social account")

    log.info(s"Handling SocialFinalizeInfo: $sfi")

    val identity = identityOpt.get
    if (identity.identityId.userId.trim.isEmpty) {
      throw new Exception(s"empty social id for $identity joining model id $modelPublicId with $sfi")
    }
    val inviteExtIdOpt: Option[ExternalId[Invitation]] = request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value))
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val (user, emailPassIdentity) = authCommander.finalizeSocialAccount(sfi, identity, inviteExtIdOpt)
    val emailConfirmedBySocialNetwork = identity.email.map(EmailAddress.validate).collect { case Success(validEmail) => validEmail.copy(address = validEmail.address.trim) }.exists(_.equalsIgnoreCase(sfi.email))
    finishSignup(user, sfi.email, emailPassIdentity, emailConfirmedAlready = emailConfirmedBySocialNetwork, modelPublicId, authToken, isFinalizedImmediately = isFinalizedImmediately)
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
          handleEmailPassFinalizeInfo(efi, None, None)
      }
    )
  }

  def handleEmailPassFinalizeInfo(efi: EmailPassFinalizeInfo, modelPublicId: Option[String], authToken: Option[String])(implicit request: UserRequest[JsValue]): Future[Result] = {
    val inviteExtIdOpt: Option[ExternalId[Invitation]] = request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value))
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    authCommander.finalizeEmailPassAccount(efi, request.userId, request.user.externalId, request.identityId, inviteExtIdOpt).map {
      case (user, email, newIdentity) =>
        val libraryPubId = modelPublicId.filter(_.startsWith("l")).map(pubId => PublicId[Library](pubId))
        val verifiedEmail = verifySignupEmail(request.userId, email, libraryPubId, authToken)
        finishSignup(user, email, newIdentity, emailConfirmedAlready = verifiedEmail, modelPublicId, authToken, isFinalizedImmediately = false)
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

  def doVerifyEmail(code: EmailVerificationCode, orgIdOpt: Option[Id[Organization]])(implicit request: MaybeUserRequest[_]): Result = {
    db.readOnlyMaster { implicit session => userEmailAddressRepo.getByCode(code) } match {
      case Some(email) =>
        verifyEmailForMaybeUser(email, orgIdOpt)
      case None =>
        BadRequest(views.html.website.verifyEmailError(error = "invalid_code", secureSocialClientIds)) //#verifymail case 1
    }
  }

  private def verifyEmailForMaybeUser(email: UserEmailAddress, orgIdOpt: Option[Id[Organization]])(implicit request: MaybeUserRequest[_]): Result = request match {
    case userRequest: UserRequest[_] => verifyEmailForUser(email, userRequest, orgIdOpt)
    case nonUserRequest: NonUserRequest[_] => verifyEmailForNonUser(email, nonUserRequest, orgIdOpt)
  }

  private def verifyEmailForUser(email: UserEmailAddress, request: UserRequest[_], orgIdOpt: Option[Id[Organization]]): Result = {
    val mobile = request.userAgentOpt.exists(_.isMobile)
    if (request.userId == email.userId || mobile) {
      unsafeVerifyEmail(email, orgIdOpt)
      val userId = email.userId
      val installations = db.readOnlyReplica { implicit s => kifiInstallationRepo.all(userId) }
      if (!mobile && !installations.exists { installation => installation.platform == KifiInstallationPlatform.Extension }) {
        Redirect("/install") //#verifymail case 2
      } else {
        Redirect(s"/?m=1") //#verifymail case 3
      }
    } else BadRequest(views.html.website.verifyEmailError(error = "invalid_user", secureSocialClientIds)) //#verifymail case 4
  }

  private def verifyEmailForNonUser(email: UserEmailAddress, request: NonUserRequest[_], orgIdOpt: Option[Id[Organization]]): Result = request.userAgentOpt match {
    case Some(agent) if agent.isMobile => //let it pass ...
      unsafeVerifyEmail(email, orgIdOpt)
      Ok(views.html.mobile.mobileAppRedirect("/email/verified")) //#verifymail case 5
    case _ =>
      val newSession = request.session + (SecureSocial.OriginalUrlKey -> request.path)
      Redirect("/login").withSession(newSession) //#verifymail case 6
  }

  private def unsafeVerifyEmail(email: UserEmailAddress, orgToAdd: Option[Id[Organization]]): Unit = {
    db.readWrite(attempts = 3)(implicit s => userEmailAddressCommander.saveAsVerified(email))

    orgToAdd.filter { orgId =>
      db.readOnlyReplica(implicit s => permissionCommander.getOrganizationPermissions(orgId, Some(email.userId)).contains(OrganizationPermission.JOIN_BY_VERIFYING))
    }.foreach { orgId =>
      val request = OrganizationMembershipAddRequest(orgId, requesterId = email.userId, targetId = email.userId)
      orgMembershipCommander.addMembership(request)
    }

    orgDomainOwnershipCommander.addOwnershipsForPendingOrganizations(email.userId, email.address).map(_ => ())
  }

  def doUploadBinaryPicture(implicit request: MaybeUserRequest[play.api.libs.Files.TemporaryFile]): Result = {
    request.userOpt.orElse(request.identityId) match {
      case Some(userInfo) =>
        s3ImageStore.uploadTemporaryPicture(request.body.file) match {
          case Success((token, pictureUrl)) =>
            Ok(Json.obj("token" -> token, "url" -> pictureUrl))
          case Failure(ex) =>
            airbrake.notify(s"Couldn't upload temporary picture (xhr direct) for $userInfo", ex)
            BadRequest(JsNumber(0))
        }
      case None => Forbidden(JsNumber(0))
    }
  }

  def doUploadFormEncodedPicture(implicit request: MaybeUserRequest[MultipartFormData[play.api.libs.Files.TemporaryFile]]) = {
    request.userOpt.orElse(request.identityId) match {
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
        provider.getRichIdentity(oauth2InfoOrig) map { identity =>
          authCommander.signupWithTrustedSocialUser(UserIdentity(identity), signUpUrl)
        } recover {
          case t: Throwable =>
            val message = s"[accessTokenSignup($providerName)] Caught Exception($t) during getUserProfileInfo; token=$oauth2InfoOrig; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}"
            log.error(message)
            airbrake.notify(message)
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
        provider.getRichIdentity(oauth1Info) map { identity =>
          authCommander.signupWithTrustedSocialUser(UserIdentity(identity), signUpUrl)
        } recover {
          case t: Throwable =>
            val message = s"[accessTokenSignup($providerName)] Caught Exception($t) during getUserProfileInfo; token=$oauth1Info; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}"
            log.error(message)
            airbrake.notify(message)
            BadRequest(Json.obj("error" -> "invalid_token"))
        }
    }
  }

  def doAccessTokenLogin(providerName: String, oAuth2Info: OAuth2Info)(implicit request: Request[JsValue]): Future[Result] = {
    oauth2ProviderRegistry.get(ProviderIds.toProviderId(providerName)) match {
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "invalid_arguments")))
      case Some(provider) =>
        provider.getIdentityId(oAuth2Info) map { identityId =>
          authCommander.loginWithTrustedSocialIdentity(identityId)
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
        provider.getIdentityId(oauth1Info) map { identityId =>
          authCommander.loginWithTrustedSocialIdentity(identityId)
        } recover {
          case t: Throwable =>
            log.error(s"[doOAuth1TokenLogin($providerName)] Caught Exception($t) during getUserProfileInfo; token=$oauth1Info; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}")
            BadRequest(Json.obj("error" -> "invalid_token"))
        }

    }
  }

}
