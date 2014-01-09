package com.keepit.controllers.core

import com.keepit.common.performance._
import com.google.inject.Inject
import play.api.mvc._
import play.api.http.{Status, HeaderNames}
import play.api.libs.json.{Json, JsValue}
import securesocial.core._
import com.keepit.common.db.{Id, ExternalId}
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
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.controller.ActionAuthenticator.MaybeAuthenticatedRequest
import scala.util.Failure
import scala.Some
import play.api.mvc.SimpleResult
import play.api.libs.json.JsNumber
import play.api.mvc.DiscardingCookie
import scala.util.Success
import play.api.mvc.Cookie
import com.keepit.common.mail.GenericEmailAddress
import com.keepit.social.SocialId
import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.model.Invitation
import com.keepit.social.UserIdentity
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.performance._
import scala.concurrent.Future

object AuthHelper {
  val PWD_MIN_LEN = 7
  def validatePwd(pwd:Array[Char]) = (pwd.nonEmpty && pwd.length >= PWD_MIN_LEN)
}

class AuthHelper @Inject() (
  db: Database,
  clock: Clock,
  airbrakeNotifier:AirbrakeNotifier,
  authCommander: AuthCommander,
  userRepo: UserRepo,
  userCredRepo: UserCredRepo,
  socialRepo: SocialUserInfoRepo,
  emailAddressRepo: EmailAddressRepo,
  userValueRepo: UserValueRepo,
  passwordResetRepo: PasswordResetRepo,
  kifiInstallationRepo: KifiInstallationRepo, // todo: factor out
  s3ImageStore: S3ImageStore,
  postOffice: LocalPostOffice,
  inviteCommander: InviteCommander,
  userCommander: UserCommander
) extends HeaderNames with Results with Status with Logging {

  def authHandler(request:Request[_], res:SimpleResult[_])(f : => (Seq[Cookie], Session) => Result) = {
    val resCookies = res.header.headers.get(SET_COOKIE).map(Cookies.decode).getOrElse(Seq.empty)
    val resSession = Session.decodeFromCookie(resCookies.find(_.name == Session.COOKIE_NAME))
    f(resCookies, resSession)
  }

  private def checkForExistingUser(emailAddress: String): Option[(Boolean, SocialUserInfo)] = timing("existing user") {
    db.readOnly { implicit s =>
      socialRepo.getOpt(SocialId(emailAddress), SocialNetworks.FORTYTWO).map(s => (true, s)) orElse {
        emailAddressRepo.getByAddressOpt(emailAddress).map {
          case emailAddr if emailAddr.state == EmailAddressStates.VERIFIED =>
            (true, socialRepo.getByUser(emailAddr.userId).find(_.networkType == SocialNetworks.FORTYTWO).headOption)
          case emailAddr =>
            // Someone is trying to register with someone else's unverified + non-login email address.
            (false, socialRepo.getByUser(emailAddr.userId).find(_.networkType == SocialNetworks.FORTYTWO).headOption)
        }
        None
      }
    }
  }

  def handleEmailPasswordSuccessForm(emailAddress: String, password:Array[Char])(implicit request:Request[_]) = timing(s"handleEmailPasswordSuccess($emailAddress)") {
    require(AuthHelper.validatePwd(password), "invalid password")
    val hasher = Registry.hashers.currentHasher
    val tupleOpt: Option[(Boolean, SocialUserInfo)] = checkForExistingUser(emailAddress)
    val session = request.session
    val home = com.keepit.controllers.website.routes.HomeController.home()
    val res: PlainResult = tupleOpt collect {
      case (emailIsVerifiedOrPrimary, sui) if sui.credentials.isDefined && sui.userId.isDefined =>
        // Social user exists with these credentials
        val identity = sui.credentials.get
        val matches = timing(s"[handleEmailPasswordSuccessForm($emailAddress)] hash") { hasher.matches(identity.passwordInfo.get, new String(password)) }
        if (matches) {
          Authenticator.create(identity).fold(
            error => Status(INTERNAL_SERVER_ERROR)("0"),
            authenticator => {
              val finalized = db.readOnly { implicit session =>
                userRepo.get(sui.userId.get).state != UserStates.INCOMPLETE_SIGNUP
              }
              if (finalized) {
                Ok(Json.obj("uri" -> session.get(SecureSocial.OriginalUrlKey).getOrElse(home.url).asInstanceOf[String]))  // todo(ray): uri not relevant for mobile
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
      "email" -> email,
      "password" -> text.verifying("password_too_short", pw => AuthHelper.validatePwd(pw.toCharArray))
    )
      ((email, pwd) => EmailPassword(email, pwd.toCharArray))
      ((ep:EmailPassword) => Some(ep.email, new String(ep.password)))
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

  private val url = current.configuration.getString("application.baseUrl").get

  def finishSignup(user: User, emailAddress: String, newIdentity: Identity, emailConfirmedAlready: Boolean)(implicit request: Request[JsValue]): Result = timing(s"[finishSignup(${user.id}, $emailAddress}]") {
    if (!emailConfirmedAlready) {
      val emailAddrStr = newIdentity.email.getOrElse(emailAddress)
      SafeFuture { userCommander.sendWelcomeEmail(user, withVerification=true, Some(GenericEmailAddress(emailAddrStr))) }
    } else {
      db.readWrite { implicit session =>
        emailAddressRepo.getByAddressOpt(emailAddress) map { emailAddr =>
          userRepo.save(user.copy(primaryEmailId = Some(emailAddr.id.get)))
        }
        userValueRepo.clearValue(user.id.get, "pending_primary_email")
      }
      SafeFuture { userCommander.sendWelcomeEmail(user, withVerification=false) }
    }

    val uri = request.session.get(SecureSocial.OriginalUrlKey).getOrElse("/install")

    Authenticator.create(newIdentity).fold(
      error => Status(INTERNAL_SERVER_ERROR)("0"),
      authenticator => Ok(Json.obj("uri" -> uri)).withNewSession.withCookies(authenticator.toCookie).discardingCookies(DiscardingCookie("inv")) // todo: uri not relevant for mobile
    )
  }

  private val socialFinalizeAccountForm = Form[SocialFinalizeInfo](
    mapping(
      "email" -> email.verifying("known_email_address", email => db.readOnly { implicit s =>
        userCredRepo.findByEmailOpt(email).isEmpty
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
    ) ((email, fName, lName, pwd, picToken, picH, picW, cX, cY, cS) =>
        SocialFinalizeInfo(email = email, firstName = fName, lastName = lName, password = pwd.toCharArray, picToken = picToken, picHeight = picH, picWidth = picW, cropX = cX, cropY = cY, cropSize = cS))
      ((sfi:SocialFinalizeInfo) =>
        Some(sfi.email, sfi.firstName, sfi.lastName, new String(sfi.password), sfi.picToken, sfi.picHeight, sfi.picWidth, sfi.cropX, sfi.cropY, sfi.cropSize))
  )
  def doSocialFinalizeAccountAction(implicit request: Request[JsValue]): Result = {
    socialFinalizeAccountForm.bindFromRequest.fold(
    formWithErrors => BadRequest(Json.obj("error" -> formWithErrors.errors.head.message)),
    { case sfi:SocialFinalizeInfo =>
      val inviteExtIdOpt: Option[ExternalId[Invitation]] = request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value))
      val (user, emailPassIdentity) = authCommander.finalizeSocialAccount(sfi, request.userIdOpt, request.identityOpt, inviteExtIdOpt)
      val emailConfirmedBySocialNetwork = request.identityOpt.flatMap(_.email).exists(_.trim == sfi.email.trim)
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
  def doUserPassFinalizeAccountAction(implicit request: AuthenticatedRequest[JsValue]): Result = Async {
    userPassFinalizeAccountForm.bindFromRequest.fold(
      formWithErrors => Future.successful(Forbidden(Json.obj("error" -> "user_exists_failed_auth"))),
      { case efi:EmailPassFinalizeInfo =>
        val inviteExtIdOpt: Option[ExternalId[Invitation]] = request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value))
        val sw = new Stopwatch(s"[finalizeEmailPasswordAcct(${request.userId})]")
        authCommander.finalizeEmailPassAccount(efi, request.userId, request.user.externalId, request.identityOpt, inviteExtIdOpt).map { case (user, email, newIdentity) =>
          sw.stop()
          sw.logTime()
          finishSignup(user, email, newIdentity, emailConfirmedAlready = false)
        }
      }
    )
  }

  private def getResetEmailAddresses(emailAddrStr: String): Option[(Id[User], Option[EmailAddressHolder])] = {
    db.readOnly { implicit s =>
      val emailAddrOpt = emailAddressRepo.getByAddressOpt(emailAddrStr, excludeState = None)  // TODO: exclude INACTIVE records
      emailAddrOpt.map(_.userId) orElse socialRepo.getOpt(SocialId(emailAddrStr), SocialNetworks.FORTYTWO).flatMap(_.userId) map { userId =>
        emailAddrOpt.filter(_.verified) map { _ =>
          (userId, None)
        } getOrElse {
          // TODO: use user's primary email address once hooked up
          (userId, emailAddressRepo.getAllByUser(userId).find(_.verified))
        }
      }
    }
  }

  def doForgotPassword(implicit request: Request[JsValue]): Result = {
    (request.body \ "email").asOpt[String] map { emailAddrStr =>
      db.readOnly { implicit session =>
        getResetEmailAddresses(emailAddrStr)
      } match {
        case Some((userId, verifiedEmailAddressOpt)) =>
          val emailAddresses = Set(GenericEmailAddress(emailAddrStr)) ++ verifiedEmailAddressOpt
          db.readWrite { implicit session =>
            emailAddresses.map { resetEmailAddress =>
            // TODO: Invalidate both reset tokens the first time one is used.
              val reset = passwordResetRepo.createNewResetToken(userId, resetEmailAddress)
              val resetUrl = s"$url${routes.AuthController.setPasswordPage(reset.token)}"
              postOffice.sendMail(ElectronicMail(
                fromName = Some("Kifi Support"),
                from = EmailAddresses.SUPPORT,
                to = Seq(resetEmailAddress),
                subject = "Kifi.com | Password reset requested",
                htmlBody = views.html.email.resetPassword(resetUrl).body,
                category = PostOffice.Categories.User.RESET_PASSWORD
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
              UserService.save(UserIdentity(
                userId = sui.userId,
                socialUser = sui.credentials.get.copy(
                  passwordInfo = Some(current.plugin[PasswordHasher].get.hash(password))
                )
              ))
              authenticateUser(sui.userId.get, onError = { error =>
                throw error
              }, onSuccess = { authenticator =>
                Ok(Json.obj("uri" -> com.keepit.controllers.website.routes.HomeController.home.url))
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
    val identity = db.readOnly { implicit session =>
      val suis = socialRepo.getByUser(userId)
      val sui = socialRepo.getByUser(userId).find(_.networkType == SocialNetworks.FORTYTWO).getOrElse(suis.head)
      sui.credentials.get
    }
    Authenticator.create(identity).fold(onError, onSuccess)
  }

  def doVerifyEmail(code: String)(implicit request: MaybeAuthenticatedRequest): Result = {
    db.readWrite { implicit s =>
      emailAddressRepo.getByCode(code).map { address =>
        val user = userRepo.get(address.userId)
        val verification = emailAddressRepo.verify(address.userId, code)

        if (verification._2) { // code is being used for the first time
          if (user.primaryEmailId.isEmpty) {
            userRepo.save(user.copy(primaryEmailId = Some(address.id.get)))
            userValueRepo.clearValue(user.id.get, "pending_primary_email")
          } else {
            val pendingEmail = userValueRepo.getValue(user.id.get, "pending_primary_email")
            if (pendingEmail.isDefined && address.address == pendingEmail.get) {
              userValueRepo.clearValue(user.id.get, "pending_primary_email")
              userRepo.save(user.copy(primaryEmailId = Some(address.id.get)))
            }
          }
        }

        verification match {
          case (true, _) if user.state == UserStates.PENDING =>
            Redirect(s"/?m=3&email=${address.address}")
          case (true, true) if request.userIdOpt.isEmpty || (request.userIdOpt.isDefined && request.userIdOpt.get.id == user.id.get.id) =>
            // first time being used, not logged in OR logged in as correct user
            authenticateUser(user.id.get,
              error => throw error,
              authenticator => {
                val resp = if (kifiInstallationRepo.all(user.id.get, Some(KifiInstallationStates.INACTIVE)).isEmpty) { // todo: factor out
                  // user has no installations
                  Redirect("/install")
                } else {
                  Redirect(s"/?m=3&email=${address.address}")
                }
                resp.withSession(request.request.session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
                  .withCookies(authenticator.toCookie)
              }
            )
          case (true, _) =>
            Ok(views.html.website.verifyEmailThanks(address.address, user.firstName))
        }
      }.getOrElse {
        BadRequest(views.html.website.verifyEmailError(error = "invalid_code"))
      }
    }
  }

  def doUploadBinaryPicture(implicit request: Request[play.api.libs.Files.TemporaryFile]): Result = {
    request.userOpt.orElse(request.identityOpt) match {
      case Some(_) =>
        s3ImageStore.uploadTemporaryPicture(request.body.file) match {
          case Success((token, pictureUrl)) =>
            Ok(Json.obj("token" -> token, "url" -> pictureUrl))
          case Failure(ex) =>
            airbrakeNotifier.notify(AirbrakeError(ex, Some("Couldn't upload temporary picture (xhr direct)")))
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
