package com.keepit.controllers.core

import com.keepit.common.net.UserAgent
import com.keepit.common.performance._
import com.google.inject.Inject
import play.api.mvc._
import play.api.http.{ Status, HeaderNames }
import play.api.libs.json.{ Json, JsValue }
import securesocial.core._
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.commanders._
import com.keepit.social._
import securesocial.core.providers.utils.PasswordHasher
import com.keepit.common.controller.ActionAuthenticator._
import com.keepit.common.store.S3ImageStore
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.time._
import play.api.Play._
import play.api.data._
import play.api.data.Forms._
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.controller.ActionAuthenticator.MaybeAuthenticatedRequest
import scala.util.Failure
import scala.Some
import play.api.mvc.SimpleResult
import play.api.libs.json.JsNumber
import play.api.mvc.DiscardingCookie
import scala.util.Success
import play.api.mvc.Cookie
import com.keepit.common.mail.EmailAddress
import com.keepit.social.SocialId
import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.model.Invitation
import com.keepit.social.UserIdentity
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.performance._
import scala.concurrent.Future
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.inject.FortyTwoConfig

object AuthHelper {
  val PWD_MIN_LEN = 7
  def validatePwd(pwd: Array[Char]) = (pwd.nonEmpty && pwd.length >= PWD_MIN_LEN)
}

class AuthHelper @Inject() (
    db: Database,
    clock: Clock,
    airbrakeNotifier: AirbrakeNotifier,
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
    userCommander: UserCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    secureSocialClientIds: SecureSocialClientIds,
    fortytwoConfig: FortyTwoConfig) extends HeaderNames with Results with Status with Logging {

  def authHandler(request: Request[_], res: SimpleResult)(f: => (Seq[Cookie], Session) => SimpleResult) = {
    val resCookies = res.header.headers.get(SET_COOKIE).map(Cookies.decode).getOrElse(Seq.empty)
    val resSession = Session.decodeFromCookie(resCookies.find(_.name == Session.COOKIE_NAME))
    f(resCookies, resSession)
  }

  private def checkForExistingUser(email: EmailAddress): Option[(Boolean, SocialUserInfo)] = timing("existing user") {
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

  def handleEmailPasswordSuccessForm(emailAddress: EmailAddress, password: Array[Char])(implicit request: Request[_]) = timing(s"handleEmailPasswordSuccess($emailAddress)") {
    require(AuthHelper.validatePwd(password), "invalid password")
    val hasher = Registry.hashers.currentHasher
    val tupleOpt: Option[(Boolean, SocialUserInfo)] = checkForExistingUser(emailAddress)
    val session = request.session
    val home = com.keepit.controllers.website.routes.KifiSiteRouter.home()
    val res: SimpleResult = tupleOpt collect {
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
                  .withSession(session - SecureSocial.OriginalUrlKey + (FORTYTWO_USER_ID -> sui.userId.get.toString))
                  .withCookies(authenticator.toCookie)
              } else {
                Ok(Json.obj("success" -> true))
                  .withSession(session + (FORTYTWO_USER_ID -> sui.userId.get.toString))
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
            .withSession(session + (FORTYTWO_USER_ID -> userId.toString))
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
  def userPasswordSignupAction(implicit request: Request[JsValue]) = emailPasswordForm.bindFromRequest.fold(
    hasErrors = formWithErrors => Forbidden(Json.obj("error" -> formWithErrors.errors.head.message)),
    success = {
      case EmailPassword(emailAddress, password) => handleEmailPasswordSuccessForm(emailAddress, password)
    }
  )

  private val url = fortytwoConfig.applicationBaseUrl

  def finishSignup(user: User, emailAddress: EmailAddress, newIdentity: Identity, emailConfirmedAlready: Boolean)(implicit request: Request[JsValue]): SimpleResult = timing(s"[finishSignup(${user.id}, $emailAddress}]") {
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
      request.request.headers.get(USER_AGENT).flatMap { agentString =>
        val agent = UserAgent.fromString(agentString)
        if (agent.canRunExtensionIfUpToDate) Some("/install") else None
      } getOrElse "/" // In case the user signs up on a browser that doesn't support the extension
    }

    Authenticator.create(newIdentity).fold(
      error => Status(INTERNAL_SERVER_ERROR)("0"),
      authenticator => Ok(Json.obj("uri" -> uri)).withNewSession.withCookies(authenticator.toCookie).discardingCookies(DiscardingCookie("inv")) // todo: uri not relevant for mobile
    )
  }

  private val socialFinalizeAccountForm = Form[SocialFinalizeInfo](
    mapping(
      "email" -> EmailAddress.formMapping.verifying("known_email_address", email => db.readOnlyMaster { implicit s =>
        userCredRepo.findByEmailOpt(email.address).isEmpty
      }),
      "firstName" -> nonEmptyText,
      "lastName" -> nonEmptyText,
      "password" -> text.verifying("password_too_short", pw => AuthHelper.validatePwd(pw.toCharArray)),
      "picToken" -> optional(text),
      "picHeight" -> optional(number),
      "picWidth" -> optional(number),
      "cropX" -> optional(number),
      "cropY" -> optional(number),
      "cropSize" -> optional(number)
    )((email, fName, lName, pwd, picToken, picH, picW, cX, cY, cS) =>
        SocialFinalizeInfo(email = email, firstName = fName, lastName = lName, password = pwd.toCharArray, picToken = picToken, picHeight = picH, picWidth = picW, cropX = cX, cropY = cY, cropSize = cS))((sfi: SocialFinalizeInfo) =>
        Some(sfi.email, sfi.firstName, sfi.lastName, new String(sfi.password), sfi.picToken, sfi.picHeight, sfi.picWidth, sfi.cropX, sfi.cropY, sfi.cropSize))
  )
  def doSocialFinalizeAccountAction(implicit request: Request[JsValue]): SimpleResult = {
    socialFinalizeAccountForm.bindFromRequest.fold(
      formWithErrors => BadRequest(Json.obj("error" -> formWithErrors.errors.head.message)),
      {
        case sfi: SocialFinalizeInfo =>
          require(request.identityOpt.isDefined, "A social identity should be available in order to finalize social account")
          val identity = request.identityOpt.get
          val inviteExtIdOpt: Option[ExternalId[Invitation]] = request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value))
          implicit val context = heimdalContextBuilder.withRequestInfo(request).build
          val (user, emailPassIdentity) = authCommander.finalizeSocialAccount(sfi, identity, inviteExtIdOpt)
          val emailConfirmedBySocialNetwork = identity.email.map(EmailAddress.validate).collect { case Success(validEmail) => validEmail }.exists(_.equalsIgnoreCase(sfi.email))
          finishSignup(user, sfi.email, emailPassIdentity, emailConfirmedAlready = emailConfirmedBySocialNetwork)
      })
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
  def doUserPassFinalizeAccountAction(implicit request: AuthenticatedRequest[JsValue]): Future[SimpleResult] = {
    userPassFinalizeAccountForm.bindFromRequest.fold(
      formWithErrors => Future.successful(Forbidden(Json.obj("error" -> "user_exists_failed_auth"))),
      {
        case efi: EmailPassFinalizeInfo =>
          val inviteExtIdOpt: Option[ExternalId[Invitation]] = request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value))
          implicit val context = heimdalContextBuilder.withRequestInfo(request).build
          val sw = new Stopwatch(s"[finalizeEmailPasswordAcct(${request.userId})]")
          authCommander.finalizeEmailPassAccount(efi, request.userId, request.user.externalId, request.identityOpt, inviteExtIdOpt).map {
            case (user, email, newIdentity) =>
              sw.stop()
              sw.logTime()
              finishSignup(user, email, newIdentity, emailConfirmedAlready = false)
          }
      }
    )
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

  def doForgotPassword(implicit request: Request[JsValue]): SimpleResult = {
    (request.body \ "email").asOpt[String] map { emailAddrStr =>
      db.readOnlyMaster { implicit session =>
        getResetEmailAddresses(emailAddrStr)
      } match {
        case Some((userId, verifiedEmailAddressOpt)) =>
          val emailAddresses = Set(EmailAddress(emailAddrStr)) ++ verifiedEmailAddressOpt
          db.readWrite { implicit session =>
            emailAddresses.map { resetEmailAddress =>
              // TODO: Invalidate both reset tokens the first time one is used.
              val reset = passwordResetRepo.createNewResetToken(userId, resetEmailAddress)
              val resetUrl = s"$url${routes.AuthController.setPasswordPage(reset.token)}"
              postOffice.sendMail(ElectronicMail(
                fromName = Some("Kifi Support"),
                from = SystemEmailAddress.SUPPORT,
                to = Seq(resetEmailAddress),
                subject = "Kifi.com | Password reset requested",
                htmlBody = views.html.email.resetPassword(resetUrl).body,
                category = NotificationCategory.User.RESET_PASSWORD
              ))
            }
          }
          Ok(Json.obj("addresses" -> emailAddresses.map { email =>
            if (email.address == emailAddrStr) emailAddrStr else AuthController.obscureEmailAddress(email.address)
          }))
        case _ =>
          log.warn(s"Could not reset password because supplied email address $emailAddrStr not found.")
          BadRequest(Json.obj("error" -> "no_account"))
      }
    } getOrElse BadRequest("0")
  }

  def doSetPassword(implicit request: Request[JsValue]): SimpleResult = {
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
                Ok(Json.obj("uri" -> com.keepit.controllers.website.routes.KifiSiteRouter.home.url))
                  .withSession(request.request.session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
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

  def doVerifyEmail(code: String)(implicit request: MaybeAuthenticatedRequest): SimpleResult = {
    db.readWrite { implicit s =>
      emailAddressRepo.getByCode(code).map { address =>
        lazy val isPendingPrimaryEmail = {
          val pendingEmail = userValueRepo.getValueStringOpt(address.userId, UserValueName.PENDING_PRIMARY_EMAIL).map(EmailAddress(_))
          pendingEmail.isDefined && address.address == pendingEmail.get
        }
        val user = userRepo.get(address.userId)
        val (verifiedEmailOpt, isVerifiedForTheFirstTime) = emailAddressRepo.verify(address.userId, code)
        verifiedEmailOpt.collect {
          case verifiedEmail if isVerifiedForTheFirstTime && (user.primaryEmail.isEmpty || isPendingPrimaryEmail) =>
            userCommander.updateUserPrimaryEmail(verifiedEmail)
        }

        (verifiedEmailOpt.isDefined, isVerifiedForTheFirstTime) match {
          case (true, _) if user.state == UserStates.PENDING =>
            Redirect(s"/?m=3&email=${address.address}")
          case (true, true) if request.userIdOpt.isEmpty || (request.userIdOpt.isDefined && request.userIdOpt.get.id == address.userId) =>
            // first time being used, not logged in OR logged in as correct user
            authenticateUser(address.userId,
              error => throw error,
              authenticator => {
                val resp = if (kifiInstallationRepo.all(address.userId, Some(KifiInstallationStates.INACTIVE)).isEmpty) { // todo: factor out
                  // user has no installations
                  Redirect("/install")
                } else {
                  Redirect(s"/?m=3&email=${address.address}")
                }
                resp.withSession(request.request.session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
                  .withCookies(authenticator.toCookie)
              }
            )
          case (true, false) if request.userIdOpt.isDefined && request.userIdOpt.get.id == address.userId =>
            Redirect(s"/?m=3&email=${address.address}")
          case (true, _) =>
            Ok(views.html.website.verifyEmailThanks(address.address.address, user.firstName, secureSocialClientIds))
        }
      }.getOrElse {
        BadRequest(views.html.website.verifyEmailError(error = "invalid_code", secureSocialClientIds))
      }
    }
  }

  def doUploadBinaryPicture(implicit request: Request[play.api.libs.Files.TemporaryFile]): SimpleResult = {
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

  def doUploadFormEncodedPicture(implicit request: Request[MultipartFormData[play.api.libs.Files.TemporaryFile]]) = {
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

}
