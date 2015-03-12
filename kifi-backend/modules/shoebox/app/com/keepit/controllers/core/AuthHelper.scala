package com.keepit.controllers.core

import com.keepit.common.http._
import com.keepit.commanders.emails.ResetPasswordEmailSender
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.net.UserAgent
import com.google.inject.Inject
import com.keepit.common.oauth.adaptor.SecureSocialAdaptor
import com.keepit.common.oauth.{ OAuth1ProviderRegistry, OAuth2AccessToken, ProviderIds, OAuth2ProviderRegistry }
import org.apache.commons.lang3.RandomStringUtils
import play.api.libs.oauth.RequestToken
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
import com.keepit.common.controller.{ SecureSocialHelper, MaybeUserRequest, UserRequest, KifiSession }
import com.keepit.model.Invitation
import com.keepit.social.UserIdentity
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.performance._
import scala.concurrent.{ Await, Future }
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.inject.FortyTwoConfig
import com.keepit.common.core._
import scala.concurrent.duration._

object AuthHelper {
  val PWD_MIN_LEN = 7
  def validatePwd(pwd: Array[Char]) = (pwd.nonEmpty && pwd.length >= PWD_MIN_LEN)
}

class AuthHelper @Inject() (
    db: Database,
    clock: Clock,
    airbrakeNotifier: AirbrakeNotifier,
    oauth1ProviderRegistry: OAuth1ProviderRegistry,
    oauth2ProviderRegistry: OAuth2ProviderRegistry,
    authCommander: AuthCommander,
    userRepo: UserRepo,
    userCredRepo: UserCredRepo,
    socialRepo: SocialUserInfoRepo,
    emailAddressRepo: UserEmailAddressRepo,
    userValueRepo: UserValueRepo,
    passwordResetRepo: PasswordResetRepo,
    kifiInstallationRepo: KifiInstallationRepo, // todo: factor out
    s3ImageStore: S3ImageStore,
    postOffice: LocalPostOffice,
    inviteCommander: InviteCommander,
    libraryCommander: LibraryCommander,
    userCommander: UserCommander,
    twitterWaitlistCommander: TwitterWaitlistCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    implicit val secureSocialClientIds: SecureSocialClientIds,
    resetPasswordEmailSender: ResetPasswordEmailSender,
    fortytwoConfig: FortyTwoConfig) extends HeaderNames with Results with Status with Logging {

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
        emailAddressRepo.getByAddressOpt(email).map {
          case emailAddr if emailAddr.state == UserEmailAddressStates.VERIFIED =>
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

  def handleEmailPasswordSuccessForm(emailAddress: EmailAddress, password: Array[Char])(implicit request: MaybeUserRequest[_]) = timing(s"handleEmailPasswordSuccess($emailAddress)") {
    require(AuthHelper.validatePwd(password), "invalid password")
    val hasher = Registry.hashers.currentHasher
    val tupleOpt: Option[(Boolean, SocialUserInfo)] = checkForExistingUser(emailAddress)
    val session = request.session
    val home = com.keepit.controllers.website.routes.HomeController.home()
    val res: Result = tupleOpt collect {
      case (emailIsVerifiedOrPrimary, sui) if sui.credentials.isDefined && sui.userId.isDefined =>
        // Social user exists with these credentials
        val identity = sui.credentials.get
        val matches = timing(s"[handleEmailPasswordSuccessForm($emailAddress)] hash") { hasher.matches(identity.passwordInfo.get, new String(password)) }
        if (matches) {
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
      val pInfo = timing(s"[handleEmailPasswordSuccessForm($emailAddress)] hash") { hasher.hash(new String(password)) } // see SecureSocial
      val (newIdentity, userId) = authCommander.saveUserPasswordIdentity(None, request.identityOpt, emailAddress, pInfo, isComplete = false)
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
      "password" -> text.verifying("password_too_short", pw => AuthHelper.validatePwd(pw.toCharArray))
    )((validEmail, pwd) => EmailPassword(validEmail, pwd.toCharArray))((ep: EmailPassword) => Some(ep.email, new String(ep.password)))
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
    case t: Throwable => airbrakeNotifier.notify(s"fail to save Kifi Campaign Id for user $userId where kcid = $kcid", t)
  }

  def finishSignup(user: User, emailAddress: EmailAddress, newIdentity: Identity, emailConfirmedAlready: Boolean, libraryPublicId: Option[PublicId[Library]], isFinalizedImmediately: Boolean)(implicit request: MaybeUserRequest[_]): Result = {
    if (!emailConfirmedAlready) {
      val unverifiedEmail = newIdentity.email.map(EmailAddress(_)).getOrElse(emailAddress)
      SafeFuture { userCommander.sendWelcomeEmail(user, withVerification = true, Some(unverifiedEmail)) }
    } else {
      db.readWrite { implicit session =>
        emailAddressRepo.getByAddressOpt(emailAddress) map { emailAddr =>
          userRepo.save(user.copy(primaryEmail = Some(emailAddr.address)))
        }
        userValueRepo.clearValue(user.id.get, UserValueName.PENDING_PRIMARY_EMAIL)
      }
      SafeFuture { userCommander.sendWelcomeEmail(user, withVerification = false) }
    }

    val uri = request.session.get(SecureSocial.OriginalUrlKey) getOrElse {
      request.headers.get(USER_AGENT).flatMap { agentString =>
        val agent = UserAgent(agentString)
        if (agent.canRunExtensionIfUpToDate) Some("/install") else None
      } getOrElse "/" // In case the user signs up on a browser that doesn't support the extension
    }

    libraryCommander.convertPendingInvites(emailAddress, user.id.get)
    libraryPublicId.foreach(authCommander.autoJoinLib(user.id.get, _))

    request.session.get("kcid").map(saveKifiCampaignId(user.id.get, _))

    Authenticator.create(newIdentity).fold(
      error => Status(INTERNAL_SERVER_ERROR)("0"),
      authenticator => {
        val cookieTargetLibId = request.cookies.get("publicLibraryId")
        val cookieIntent = request.cookies.get("intent")
        val discardedCookies = Seq(cookieTargetLibId, cookieIntent).flatten.map(c => DiscardingCookie(c.name)) :+ DiscardingCookie("inv")

        val result = if (isFinalizedImmediately) {
          Redirect(uri)
        } else if (cookieIntent.isDefined && cookieIntent.get.value == "waitlist") {
          db.readOnlyMaster { implicit session =>
            socialRepo.getByUser(user.id.get).find(_.networkType == SocialNetworks.TWITTER).flatMap {
              _.getProfileUrl.map(url => url.substring(url.lastIndexOf('/') + 1))
            }
          }.map { handle =>
            twitterWaitlistCommander.addEntry(user.id.get, handle)
          }
          // todo: use real url:
          Redirect(s"${com.keepit.controllers.website.routes.TwitterWaitlistController.getFakeWaitlistPosition().url}").discardingCookies(discardedCookies: _*)
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
        val existing = userCredRepo.findByEmailOpt(email.address.trim)
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
        val allowedPassword = if (pwd.exists(p => AuthHelper.validatePwd(p.toCharArray))) {
          pwd.get
        } else {
          log.warn(s"[social-finalize] Rejected social password, generating one instead. Supplied password was ${pwd.map(_.length).getOrElse(0)} chars.")
          RandomStringUtils.random(20)
        }
        SocialFinalizeInfo(email = email.copy(address = email.address.trim), firstName = fName, lastName = lName.getOrElse(""), password = allowedPassword.toCharArray, picToken = picToken, picHeight = picH, picWidth = picW, cropX = cX, cropY = cY, cropSize = cS)
      })((sfi: SocialFinalizeInfo) =>
        Some((sfi.email, sfi.firstName, Option(sfi.lastName), Option(new String(sfi.password)), sfi.picToken, sfi.picHeight, sfi.picWidth, sfi.cropX, sfi.cropY, sfi.cropSize)))
  )
  def doSocialFinalizeAccountAction(implicit request: MaybeUserRequest[JsValue]): Result = {
    socialFinalizeAccountForm.bindFromRequest.fold(
      formWithErrors => {
        log.warn("[social-finalize] Rejected social finalize because of form errors: " + formWithErrors.errors + " from request " + request.body + " from " + request.userAgentOpt)
        BadRequest(Json.obj("error" -> formWithErrors.errors.head.message))
      }, {
        case sfi: SocialFinalizeInfo =>
          handleSocialFinalizeInfo(sfi, None, isFinalizedImmediately = false)
      })
  }

  def doTokenFinalizeAccountAction(implicit request: MaybeUserRequest[JsValue]): Result = {
    request.body.asOpt[TokenFinalizeInfo] match {
      case None => BadRequest(Json.obj("error" -> "invalid_arguments"))
      case Some(info) => handleSocialFinalizeInfo(info, info.libraryPublicId, isFinalizedImmediately = false)
    }
  }

  def handleSocialFinalizeInfo(sfi: SocialFinalizeInfo, libraryPublicId: Option[PublicId[Library]], isFinalizedImmediately: Boolean)(implicit request: MaybeUserRequest[_]): Result = {
    require(request.identityOpt.isDefined, "A social identity should be available in order to finalize social account")

    val identity = request.identityOpt.get
    val inviteExtIdOpt: Option[ExternalId[Invitation]] = request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value))
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val (user, emailPassIdentity) = authCommander.finalizeSocialAccount(sfi, identity, inviteExtIdOpt)
    val emailConfirmedBySocialNetwork = identity.email.map(EmailAddress.validate).collect { case Success(validEmail) => validEmail.copy(address = validEmail.address.trim) }.exists(_.equalsIgnoreCase(sfi.email))
    finishSignup(user, sfi.email, emailPassIdentity, emailConfirmedAlready = emailConfirmedBySocialNetwork, libraryPublicId = libraryPublicId, isFinalizedImmediately = isFinalizedImmediately)
  }

  private val userPassFinalizeAccountForm = Form[EmailPassFinalizeInfo](mapping(
    "firstName" -> nonEmptyText,
    "lastName" -> nonEmptyText,
    "picToken" -> optional(text),
    "picWidth" -> optional(number),
    "picHeight" -> optional(number),
    "cropX" -> optional(number),
    "cropY" -> optional(number),
    "cropSize" -> optional(number)
  )(EmailPassFinalizeInfo.apply)(EmailPassFinalizeInfo.unapply))
  def doUserPassFinalizeAccountAction(implicit request: UserRequest[JsValue]): Future[Result] = {
    userPassFinalizeAccountForm.bindFromRequest.fold(
      formWithErrors => {
        log.warn("[userpass-finalize] Rejected user pass finalize because of form errors: " + formWithErrors.errors)
        Future.successful(Forbidden(Json.obj("error" -> "user_exists_failed_auth")))
      }, {
        case efi: EmailPassFinalizeInfo =>
          handleEmailPassFinalizeInfo(efi, None)
      }
    )
  }

  def handleEmailPassFinalizeInfo(efi: EmailPassFinalizeInfo, libraryPublicId: Option[PublicId[Library]])(implicit request: UserRequest[JsValue]): Future[Result] = {
    val inviteExtIdOpt: Option[ExternalId[Invitation]] = request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value))
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    authCommander.finalizeEmailPassAccount(efi, request.userId, request.user.externalId, request.identityOpt, inviteExtIdOpt).map {
      case (user, email, newIdentity) =>
        finishSignup(user, email, newIdentity, emailConfirmedAlready = false, libraryPublicId = libraryPublicId, isFinalizedImmediately = false)
    }
  }

  private def getResetEmailAddresses(emailAddrStr: String): Option[(Id[User], Option[EmailAddress])] = {
    val email = EmailAddress(emailAddrStr)
    db.readOnlyMaster { implicit s =>
      val emailAddrOpt = emailAddressRepo.getByAddressOpt(email, excludeState = None) // TODO: exclude INACTIVE records
      emailAddrOpt.map(_.userId) orElse socialRepo.getOpt(SocialId(emailAddrStr), SocialNetworks.FORTYTWO).flatMap(_.userId) map { userId =>
        emailAddrOpt.filter(_.verified) map { _ =>
          (userId, None)
        } getOrElse {
          // TODO: use user's primary email address once hooked up
          (userId, emailAddressRepo.getAllByUser(userId).find(_.verified).map(_.address))
        }
      }
    }
  }

  def doForgotPassword(implicit request: Request[JsValue]): Future[Result] = {
    (request.body \ "email").asOpt[String] map { emailAddrStr =>
      db.readOnlyMaster { implicit session =>
        getResetEmailAddresses(emailAddrStr)
      } match {
        case Some((userId, verifiedEmailAddressOpt)) =>
          val emailAddresses = Set(EmailAddress(emailAddrStr)) ++ verifiedEmailAddressOpt
          val emailsF = Future.sequence(emailAddresses.map { email => resetPasswordEmailSender.sendToUser(userId, email) }.toSeq)
          emailsF.map { e =>
            Ok(Json.obj("addresses" -> emailAddresses.map { email =>
              if (email.address == emailAddrStr) emailAddrStr else AuthController.obscureEmailAddress(email.address)
            }))
          }
        case _ =>
          log.warn(s"Could not reset password because supplied email address $emailAddrStr not found.")
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
          case Some(pr) if passwordResetRepo.tokenIsNotExpired(pr) =>
            val email = passwordResetRepo.useResetToken(code, request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress))
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
              log.info(s"[doSetPassword] UserCreds updated=${updated.map(c => s"id=${c.id} userId=${c.userId} login=${c.loginName}")}")
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
      emailAddressRepo.getByCode(code).map { address =>
        lazy val isPendingPrimaryEmail = {
          val pendingEmail = userValueRepo.getValueStringOpt(address.userId, UserValueName.PENDING_PRIMARY_EMAIL).map(EmailAddress(_))
          pendingEmail.isDefined && address.address == pendingEmail.get
        }
        val user = userRepo.get(address.userId)
        val (verifiedEmailOpt, isVerifiedForTheFirstTime) = emailAddressRepo.verify(address.userId, code)
        verifiedEmailOpt.collect {
          case verifiedEmail if (user.primaryEmail.isEmpty || isPendingPrimaryEmail) =>
            userCommander.updateUserPrimaryEmail(verifiedEmail)
        }

        (verifiedEmailOpt.isDefined, isVerifiedForTheFirstTime) match {
          case (true, _) if user.state == UserStates.PENDING =>
            Redirect(s"/?m=1")
          case (true, true) if request.userIdOpt.isEmpty || (request.userIdOpt.isDefined && request.userIdOpt.get.id == address.userId) =>
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
          case (true, false) if request.userIdOpt.isDefined && request.userIdOpt.get.id == address.userId =>
            Redirect(s"/?m=1")
          case (true, _) =>
            Ok(views.html.website.verifyEmailThanks(address.address.address, user.firstName, secureSocialClientIds))
        }
      }.getOrElse {
        BadRequest(views.html.website.verifyEmailError(error = "invalid_code", secureSocialClientIds))
      }
    }
  }

  def doUploadBinaryPicture(implicit request: MaybeUserRequest[play.api.libs.Files.TemporaryFile]): Result = {
    request.userOpt.orElse(request.identityOpt) match {
      case Some(userInfo) =>
        s3ImageStore.uploadTemporaryPicture(request.body.file) match {
          case Success((token, pictureUrl)) =>
            Ok(Json.obj("token" -> token, "url" -> pictureUrl))
          case Failure(ex) =>
            airbrakeNotifier.notify("Couldn't upload temporary picture (xhr direct) for $userInfo", ex)
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
              airbrakeNotifier.notify(AirbrakeError(ex, Some("Couldn't upload temporary picture (form encoded)")))
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
              airbrakeNotifier.notify(s"[accessTokenSignup($providerName)] Caught Exception($t) during token exchange; token=${oauth2InfoOrig}; Cause:${t.getCause}; StackTrace: ${t.getStackTraceString}")
              OAuth2TokenInfo.fromOAuth2Info(oauth2InfoOrig)
          }
          longTermTokenInfoF map { oauth2InfoNew =>
            authCommander.signupWithTrustedSocialUser(providerName, filledUser.copy(oAuth2Info = Some(oauth2InfoNew)), signUpUrl)
          }
        } recover {
          case t: Throwable =>
            airbrakeNotifier.notify(s"[accessTokenSignup($providerName)] Caught Exception($t) during getUserProfileInfo; token=${oauth2InfoOrig}; Cause:${t.getCause}; StackTrace: ${t.getStackTraceString}")
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
            airbrakeNotifier.notify(s"[accessTokenSignup($providerName)] Caught Exception($t) during getUserProfileInfo; token=${oauth1Info}; Cause:${t.getCause}; StackTrace: ${t.getStackTraceString}")
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
            log.error(s"[accessTokenLogin($providerName)] Caught Exception($t) during getUserProfileInfo; token=${oAuth2Info}; Cause:${t.getCause}; StackTrace: ${t.getStackTraceString}")
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
            log.error(s"[doOAuth1TokenLogin($providerName)] Caught Exception($t) during getUserProfileInfo; token=${oauth1Info}; Cause:${t.getCause}; StackTrace: ${t.getStackTraceString}")
            BadRequest(Json.obj("error" -> "invalid_token"))
        }

    }
  }

}
