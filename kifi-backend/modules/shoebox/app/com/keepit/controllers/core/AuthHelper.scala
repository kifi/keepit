package com.keepit.controllers.core

import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.http._
import com.keepit.commanders.emails.ResetPasswordEmailSender
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.net.UserAgent
import com.google.inject.Inject
import com.keepit.common.oauth._
import com.keepit.common.service.IpAddress
import com.keepit.controllers.core.PostRegIntent._
import com.keepit.payments.CreditCode
import com.keepit.slack.models.{SlackAuthScope, SlackAuthorizationResponse, SlackTeamMembershipRepo, SlackTeamId}
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

sealed abstract class PostRegIntent
object PostRegIntent {
  case class AutoFollowLibrary(libraryId: Id[Library], authToken: Option[String]) extends PostRegIntent
  object AutoFollowLibrary {
    // strings that represent Cookie keys and values
    val intentValue = "follow"
    val libIdKey = "publicLibraryId"
    val authKey = "libAuthToken"
    val cookieKeys = Set(libIdKey, authKey)
  }
  case class AutoJoinOrganization(organizationId: Id[Organization], authToken: String) extends PostRegIntent
  object AutoJoinOrganization {
    val intentValue = "joinOrg"
    val orgIdKey = "publicOrgId"
    val authKey = "orgAuthToken"
    val cookieKeys = Set(orgIdKey, authKey)
  }
  case class AutoJoinKeep(keepId: Id[Keep], authTokenOpt: Option[String]) extends PostRegIntent
  object AutoJoinKeep {
    val intentValue = "joinKeep"
    val keepIdKey = "publicKeepId"
    val authKey = "keepAuthToken"
    val cookieKeys = Set(keepIdKey, authKey)
  }
  case class Slack(slackTeamId: Option[SlackTeamId], slackExtraScopes: Option[Set[SlackAuthScope]]) extends PostRegIntent
  object Slack {
    val intentValue = "slack"
    val slackTeamIdKey = "slackTeamId"
    val extraScopesKey = "slackExtraScopes"
    val cookieKeys = Set(slackTeamIdKey, extraScopesKey)
  }
  case class ApplyCreditCode(creditCode: CreditCode) extends PostRegIntent
  object ApplyCreditCode {
    val intentValue = "applyCredit"
    val creditCodeKey = "creditCode"
    val cookieKeys = Set(creditCodeKey)
  }
  case object JoinTwitterWaitlist extends PostRegIntent { val intentValue = "waitlist" }
  case object NoIntent extends PostRegIntent

  val intentKey = "intent"
  val onFailUrlKey = "onFailUrl"
  val discardingCookies = Set(Set(intentKey, onFailUrlKey), AutoFollowLibrary.cookieKeys, AutoJoinOrganization.cookieKeys, AutoJoinKeep.cookieKeys, Slack.cookieKeys, ApplyCreditCode.cookieKeys).flatten.map(DiscardingCookie(_)).toSeq

  def fromCookies(cookies: Set[Cookie])(implicit config: PublicIdConfiguration): PostRegIntent = {
    val cookieByName = cookies.groupBy(_.name).mapValuesStrict(_.head)
    cookieByName.get(intentKey).map(_.value).collect {
      case AutoFollowLibrary.intentValue => {
        import AutoFollowLibrary._
        val libId = cookieByName.get(libIdKey)
          .map(c => PublicId[Library](c.value))
          .flatMap(Library.decodePublicId(_).toOption)
        val authTokenOpt = cookieByName.get(authKey).map(_.value)
        libId.map(AutoFollowLibrary(_, authTokenOpt))
      }
      case AutoJoinOrganization.intentValue => {
        import AutoJoinOrganization._
        val orgIdOpt = cookieByName.get(orgIdKey)
          .map(c => PublicId[Organization](c.value))
          .flatMap(Organization.decodePublicId(_).toOption)
        val authTokenOpt = cookieByName.get(authKey).map(_.value)
        (orgIdOpt, authTokenOpt) match {
          case (Some(orgId), Some(authToken)) => Some(AutoJoinOrganization(orgId, authToken))
          case _ => None
        }
      }
      case AutoJoinKeep.intentValue => {
        import AutoJoinKeep._
        val keepIdOpt = cookieByName.get(keepIdKey)
          .map(c => PublicId[Keep](c.value))
          .flatMap(Keep.decodePublicId(_).toOption)
        val authTokenOpt = cookieByName.get(authKey).map(_.value)
        keepIdOpt.map(AutoJoinKeep(_, authTokenOpt))
      }
      case Slack.intentValue => {
        val slackTeamIdOpt = cookieByName.get(Slack.slackTeamIdKey).map(c => SlackTeamId(c.value))
        val extraScopesOpt = cookieByName.get(Slack.extraScopesKey).map(c => SlackAuthScope.setFromString(c.value))
        Some(Slack(slackTeamIdOpt, extraScopesOpt))
      }
      case ApplyCreditCode.intentValue => cookieByName.get(ApplyCreditCode.creditCodeKey).map(c => ApplyCreditCode(CreditCode(c.value)))
      case JoinTwitterWaitlist.intentValue => Some(JoinTwitterWaitlist)
    }.flatten.getOrElse(NoIntent)
  }
  def fromParams(
    libPubId: Option[PublicId[Library]], libAuthToken: Option[String],
    orgPubId: Option[PublicId[Organization]], orgAuthToken: Option[String],
    keepPubId: Option[PublicId[Keep]], keepAuthToken: Option[String])(implicit config: PublicIdConfiguration): PostRegIntent = {

    val libIntent = libPubId.flatMap(Library.decodePublicId(_).toOption).map(AutoFollowLibrary(_, libAuthToken))
    def orgIntent = (orgPubId.flatMap(Organization.decodePublicId(_).toOption), orgAuthToken) match {
      case (Some(orgId), Some(authToken)) => Some(AutoJoinOrganization(orgId, authToken))
      case _ => None
    }
    def keepIntent = keepPubId.flatMap(Keep.decodePublicId(_).toOption).map(AutoJoinKeep(_, keepAuthToken))

    Stream(libIntent, orgIntent, keepIntent).flatten.headOption.getOrElse(NoIntent)
  }
  def requestToCookies(request: Request[_]): Seq[Cookie] = {
    val intentKeys = Set(AutoFollowLibrary.cookieKeys, AutoJoinOrganization.cookieKeys, AutoJoinKeep.cookieKeys, Slack.cookieKeys, ApplyCreditCode.cookieKeys).flatten + this.intentKey
    intentKeys.flatMap(key => request.getQueryString(key).map(Cookie(key, _))).toSeq
  }
}

class AuthHelper @Inject() (
    db: Database,
    clock: Clock,
    airbrake: AirbrakeNotifier,
    oauth1ProviderRegistry: OAuth1ProviderRegistry,
    oauth2ProviderRegistry: OAuth2ProviderRegistry,
    slackOAuthProvider: SlackOAuthProvider,
    authCommander: AuthCommander,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    libraryInviteRepo: LibraryInviteRepo,
    userCredRepo: UserCredRepo,
    userEmailAddressRepo: UserEmailAddressRepo,
    userValueRepo: UserValueRepo,
    kifiInstallationRepo: KifiInstallationRepo, // todo: factor out
    orgRepo: OrganizationRepo,
    orgInviteRepo: OrganizationInviteRepo,
    slackMembershipRepo: SlackTeamMembershipRepo,
    ktlRepo: KeepToLibraryRepo,
    s3ImageStore: S3ImageStore,
    libPathCommander: PathCommander,
    userEmailAddressCommander: UserEmailAddressCommander,
    userCommander: UserCommander,
    orgDomainOwnershipCommander: OrganizationDomainOwnershipCommander,
    orgMembershipCommander: OrganizationMembershipCommander,
    permissionCommander: PermissionCommander,
    pathCommander: PathCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    heimdal: HeimdalServiceClient,
    implicit val secureSocialClientIds: SecureSocialClientIds,
    implicit val config: PublicIdConfiguration,
    resetPasswordEmailSender: ResetPasswordEmailSender,
    fortytwoConfig: FortyTwoConfig,
    twitterWaitlistCommander: TwitterWaitlistCommander,
    mode: Mode) extends HeaderNames with Results with Status with Logging {

  private def hasSeenInstall(userId: Id[User]): Boolean = db.readOnlyMaster { implicit s =>
    userValueRepo.getValue(userId, UserValues.hasSeenInstall)
  }

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

  private def saveKifiCampaignId(userId: Id[User], kcid: String): Unit = try {
    db.readWrite(attempts = 2) { implicit session =>
      userValueRepo.save(UserValue(userId = userId, name = UserValueName.KIFI_CAMPAIGN_ID, value = kcid))
    }
  } catch {
    case t: Throwable => airbrake.notify(s"fail to save Kifi Campaign Id for user $userId where kcid = $kcid", t)
  }

  def processIntent(userId: Id[User], intent: PostRegIntent)(implicit request: MaybeUserRequest[_]): String = {
    import com.keepit.controllers.website.HomeControllerRoutes
    val homeOrInstall = request match {
      case ur: UserRequest[_] if request.userAgentOpt.exists(_.canRunExtensionIfUpToDate) && !hasSeenInstall(userId) => HomeControllerRoutes.install
      case _ => HomeControllerRoutes.home
    }

    intent match {
      case AutoFollowLibrary(libId, authTokenOpt) =>
        val joined = authCommander.autoJoinLib(userId, libId, authTokenOpt)
        if (joined) db.readOnlyMaster(implicit s => pathCommander.libraryPageById(libId).relativeWithLeadingSlash) else homeOrInstall
      case AutoJoinOrganization(orgId, authToken) =>
        val joined = authCommander.autoJoinOrg(userId, orgId, authToken)
        if (joined) db.readOnlyMaster(implicit s => pathCommander.orgPageById(orgId).relativeWithLeadingSlash) else homeOrInstall
      case AutoJoinKeep(keepId, authTokenOpt) =>
        authCommander.autoJoinKeep(userId, keepId, authTokenOpt)
        homeOrInstall
      case Slack(slackTeamIdOpt, extraScopesOpt) =>
        com.keepit.controllers.core.routes.AuthController.startWithSlack(slackTeamIdOpt, extraScopesOpt.filter(_.nonEmpty).map(SlackAuthScope.stringifySet)).url
      case ApplyCreditCode(creditCode) =>
        db.readWrite(attempts = 3) { implicit session =>
          userValueRepo.setValue(userId, UserValueName.STORED_CREDIT_CODE, creditCode.value)
        }
        "/teams/new"
      case JoinTwitterWaitlist =>
        twitterWaitlistCommander.createSyncOrWaitlist(userId, SyncTarget.Tweets) match {
          case Right(sync) => userValueRepo.setValue(userId, UserValueName.TWITTER_SYNC_PROMO, "in_progress")
          case Left(err) => userValueRepo.setValue(userId, UserValueName.TWITTER_SYNC_PROMO, "show_sync")
        }
        "/twitter/thanks"
      case NoIntent => homeOrInstall
    }
  }

  def finishSignup(user: User,
    emailAddress: EmailAddress,
    newIdentity: Identity,
    emailConfirmedAlready: Boolean,
    intent: PostRegIntent,
    isFinalizedImmediately: Boolean)(implicit request: MaybeUserRequest[_], context: HeimdalContext): Result = {

    intent match {
      case JoinTwitterWaitlist => // Nothing for now
      case _ => // Anything BUT twitter waitlist
        if (mode == Prod) { // Do not sent the email in dev
          SafeFuture {
            userCommander.sendWelcomeEmail(user.id.get, withVerification = !emailConfirmedAlready, Some(emailAddress))
          }
        }
    }

    db.readWrite(attempts = 3) { implicit session =>
      userEmailAddressRepo.getByAddressAndUser(user.id.get, emailAddress) foreach { emailAddr =>
        userEmailAddressCommander.setAsPrimaryEmail(emailAddr)
      }
    }
    heimdal.trackEvent(UserEvent(user.id.get, context, UserEventTypes.COMPLETED_SIGNUP))
    request.session.get("kcid").foreach(saveKifiCampaignId(user.id.get, _))

    val uri = processIntent(user.id.get, intent)
    log.info(s"[authCookies] remaining cookies=${request.cookies}")
    val discardedCookies = Seq("inv").map(n => DiscardingCookie(n)) ++ PostRegIntent.discardingCookies

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
          handleSocialFinalizeInfo(sfi, PostRegIntent.fromCookies(request.cookies.toSet), isFinalizedImmediately = false)
      })
  }

  def doTokenFinalizeAccountAction(implicit request: MaybeUserRequest[JsValue]): Result = {
    request.body.asOpt[TokenFinalizeInfo] match {
      case None => BadRequest(Json.obj("error" -> "invalid_arguments"))
      case Some(info) =>
        handleSocialFinalizeInfo(TokenFinalizeInfo.toSocialFinalizeInfo(info), TokenFinalizeInfo.toPostRegIntent(info), isFinalizedImmediately = false)
    }
  }

  def handleSocialFinalizeInfo(sfi: SocialFinalizeInfo, intent: PostRegIntent, isFinalizedImmediately: Boolean)(implicit request: MaybeUserRequest[_]): Result = {
    val identityOpt = request.identityId.flatMap(authCommander.getUserIdentity)
    require(identityOpt.isDefined, "A social identity should be available in order to finalize social account")

    log.info(s"Handling SocialFinalizeInfo: $sfi")

    val identity = identityOpt.get
    if (identity.identityId.userId.trim.isEmpty) {
      throw new Exception(s"empty social id for $identity with $sfi")
    }
    val inviteExtIdOpt: Option[ExternalId[Invitation]] = request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value))
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val (user, emailPassIdentity) = authCommander.finalizeSocialAccount(sfi, identity, inviteExtIdOpt)
    val emailConfirmedBySocialNetwork = identity.email.map(EmailAddress.validate).collect { case Success(validEmail) => validEmail.copy(address = validEmail.address.trim) }.exists(_.equalsIgnoreCase(sfi.email))
    finishSignup(user, sfi.email, emailPassIdentity, emailConfirmedAlready = emailConfirmedBySocialNetwork, intent, isFinalizedImmediately = isFinalizedImmediately)
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
          handleEmailPassFinalizeInfo(efi, PostRegIntent.fromCookies(request.cookies.toSet))
      }
    )
  }

  def handleEmailPassFinalizeInfo(efi: EmailPassFinalizeInfo, intent: PostRegIntent)(implicit request: UserRequest[JsValue]): Future[Result] = {
    val inviteExtIdOpt: Option[ExternalId[Invitation]] = request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value))
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    authCommander.finalizeEmailPassAccount(efi, request.userId, request.user.externalId, request.identityId, inviteExtIdOpt).map {
      case (user, email, newIdentity) =>
        val verifiedEmail = intent match {
          case AutoFollowLibrary(libId, Some(authToken)) => verifySignupEmailFromLibInvite(request.userId, email, libId, authToken)
          case AutoJoinOrganization(orgId, authToken) => verifySignupEmailFromOrgInvite(request.userId, email, orgId, authToken)
          case _ => false
        }
        finishSignup(user, email, newIdentity, emailConfirmedAlready = verifiedEmail, intent, isFinalizedImmediately = false)
    }
  }

  private def verifySignupEmailFromLibInvite(userId: Id[User], email: EmailAddress, libId: Id[Library], authToken: String): Boolean = {
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

  private def verifySignupEmailFromOrgInvite(userId: Id[User], email: EmailAddress, orgId: Id[Organization], authToken: String): Boolean = {
    db.readWrite(attempts = 3) { implicit session =>
      orgInviteRepo.getByOrgIdAndAuthToken(orgId, authToken).exists { orgInvite =>
        orgInvite.emailAddress.exists { sentTo =>
          (sentTo == email) && userEmailAddressRepo.getByAddressAndUser(userId, email).exists { emailRecord =>
            userEmailAddressCommander.saveAsVerified(emailRecord)
            true
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

  private def getOAuthTokenIdentityProvider(providerName: String, body: JsValue): Option[OAuthTokenIdentityProvider[_, _ <: RichIdentity]] = {
    Try(ProviderIds.toProviderId(providerName)).toOption.flatMap {
      case providerId @ (ProviderIds.Facebook | ProviderIds.LinkedIn) => for {
        oauth2InfoOrig <- body.asOpt[OAuth2TokenInfo]
        provider <- oauth2ProviderRegistry.get(providerId)
      } yield OAuthTokenIdentityProvider(provider, oauth2InfoOrig)
      case providerId @ ProviderIds.Twitter => for {
        oauth1InfoOrig <- body.asOpt[OAuth1TokenInfo]
        provider <- oauth1ProviderRegistry.get(providerId)
      } yield OAuthTokenIdentityProvider(provider, oauth1InfoOrig)
      case providerId @ ProviderIds.Slack => body.asOpt[SlackAuthorizationResponse].map { authToken => OAuthTokenIdentityProvider(slackOAuthProvider, authToken) }
      case _ => None
    }
  }

  def doAccessTokenSignup(providerName: String)(implicit request: Request[JsValue]): Future[Result] = {
    getOAuthTokenIdentityProvider(providerName, request.body) match {
      case None =>
        log.error(s"[accessTokenSignup($providerName)] Failed to retrieve provider; request=${request.body}")
        Future.successful(BadRequest(Json.obj("error" -> "invalid_arguments")))

      case Some(tokenIdentityProvider) => tokenIdentityProvider.getRichIdentity.map { identity =>
        authCommander.signupWithTrustedSocialUser(UserIdentity(identity), signUpUrl)
      } recover {
        case t: Throwable =>
          val message = s"[accessTokenSignup($providerName)] Caught Exception($t) during getUserProfileInfo; token=${tokenIdentityProvider.token}; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}"
          log.error(message)
          airbrake.notify(message)
          BadRequest(Json.obj("error" -> "invalid_token"))
      }
    }
  }

  def doAccessTokenLogin(providerName: String)(implicit request: Request[JsValue]): Future[Result] = {
    getOAuthTokenIdentityProvider(providerName, request.body) match {
      case None =>
        log.error(s"[accessTokenLogin($providerName)] Failed to retrieve provider; request=${request.body}")
        Future.successful(BadRequest(Json.obj("error" -> "invalid_arguments")))
      case Some(tokenIdentityProvider) =>
        tokenIdentityProvider.getIdentityId map { identityId =>
          authCommander.loginWithTrustedSocialIdentity(identityId)
        } recover {
          case t: Throwable =>
            log.error(s"[accessTokenLogin($providerName)] Caught Exception($t) during getUserProfileInfo; token=${tokenIdentityProvider.token}; Cause:${t.getCause}; StackTrace: ${t.getStackTrace.mkString("", "\n", "\n")}")
            BadRequest(Json.obj("error" -> "invalid_token"))
        }
    }
  }
}
