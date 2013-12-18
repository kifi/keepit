package com.keepit.controllers.core

import com.google.inject.Inject
import play.api.mvc._
import play.api.http.{Status, HeaderNames}
import play.api.libs.json.{Json, JsValue}
import securesocial.core._
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.commanders.{EmailPassFinalizeInfo, SocialFinalizeInfo, AuthCommander, InviteCommander}
import com.keepit.social._
import securesocial.core.providers.utils.PasswordHasher
import com.keepit.common.controller.ActionAuthenticator._
import com.keepit.common.store.{ImageCropAttributes, S3ImageStore}
import com.keepit.common._
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.time._
import play.api.Play._
import play.api.data._
import play.api.data.Forms._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import scala.util.Failure
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
  inviteCommander: InviteCommander
) extends HeaderNames with Results with Status with Logging {

  def authHandler(request:Request[_], res:SimpleResult[_])(f : => (Seq[Cookie], Session) => Result) = {
    val resCookies = res.header.headers.get(SET_COOKIE).map(Cookies.decode).getOrElse(Seq.empty)
    val resSession = Session.decodeFromCookie(resCookies.find(_.name == Session.COOKIE_NAME))
    f(resCookies, resSession)
  }

  def handleEmailPasswordSuccessForm(emailAddress:String, password:String)(implicit request:Request[_]) = {
    val hasher = Registry.hashers.currentHasher
    val tupleOpt: Option[(Boolean, SocialUserInfo)] = db.readOnly { implicit s =>
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
    val session = request.session
    val home = com.keepit.controllers.website.routes.HomeController.home()
    val res: PlainResult = tupleOpt collect {
      case (emailIsVerifiedOrPrimary, sui) if sui.credentials.isDefined && sui.userId.isDefined =>
        // Social user exists with these credentials
        val identity = sui.credentials.get
        if (hasher.matches(identity.passwordInfo.get, password)) {
          Authenticator.create(identity).fold(
            error => Status(INTERNAL_SERVER_ERROR)("0"),
            authenticator => {
              val finalized = db.readOnly { implicit session =>
                userRepo.get(sui.userId.get).state != UserStates.INCOMPLETE_SIGNUP
              }
              if (finalized) {
                Ok(Json.obj("uri" -> session.get(SecureSocial.OriginalUrlKey).getOrElse(home.url).asInstanceOf[String]))
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
      val pInfo = hasher.hash(password)
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

  private val url = current.configuration.getString("application.baseUrl").get

  def finishSignup(user: User, emailAddress: String, newIdentity: Identity, emailConfirmedAlready: Boolean)(implicit request: Request[JsValue]): Result = {
    if (!emailConfirmedAlready) {
      db.readWrite { implicit s =>
        val emailAddrStr = newIdentity.email.getOrElse(emailAddress)
        val emailAddr = emailAddressRepo.save(emailAddressRepo.getByAddressOpt(emailAddrStr).get.withVerificationCode(clock.now))
        val verifyUrl = s"$url${routes.AuthController.verifyEmail(emailAddr.verificationCode.get)}" // todo: remove
        userValueRepo.setValue(user.id.get, "pending_primary_email", emailAddr.address)

        val (subj, body) = if (user.state != UserStates.ACTIVE) {
          ("Kifi.com | Please confirm your email address",
            views.html.email.verifyEmail(newIdentity.firstName, verifyUrl).body)
        } else {
          ("Welcome to Kifi! Please confirm your email address",
            views.html.email.verifyAndWelcomeEmail(user, verifyUrl).body)
        }
        val mail = ElectronicMail(
          from = EmailAddresses.NOTIFICATIONS,
          to = Seq(GenericEmailAddress(emailAddrStr)),
          category = PostOffice.Categories.User.EMAIL_CONFIRMATION,
          subject = subj,
          htmlBody = body)
        postOffice.sendMail(mail)
      }
    } else {
      db.readWrite { implicit session =>
        emailAddressRepo.getByAddressOpt(emailAddress) map { emailAddr =>
          userRepo.save(user.copy(primaryEmailId = Some(emailAddr.id.get)))
        }
        userValueRepo.clearValue(user.id.get, "pending_primary_email")
      }
    }

    val uri = request.session.get(SecureSocial.OriginalUrlKey).getOrElse("/")

    Authenticator.create(newIdentity).fold(
      error => Status(INTERNAL_SERVER_ERROR)("0"),
      authenticator => Ok(Json.obj("uri" -> uri)).withNewSession.withCookies(authenticator.toCookie).discardingCookies(DiscardingCookie("inv"))
    )
  }

  private val socialFinalizeAccountForm = Form[SocialFinalizeInfo](
    mapping(
      "email" -> email.verifying("known_email_address", email => db.readOnly { implicit s =>
        userCredRepo.findByEmailOpt(email).isEmpty
      }),
      "firstName" -> nonEmptyText,
      "lastName" -> nonEmptyText,
      "password" -> text.verifying("password_too_short", pw => pw.length >= 7),
      "picToken" -> optional(text),
      "picHeight" -> optional(number),
      "picWidth" -> optional(number),
      "cropX" -> optional(number),
      "cropY" -> optional(number),
      "cropSize" -> optional(number)
    ) ((email, fName, lName, pwd, picToken, picH, picW, cX, cY, cS) =>
        SocialFinalizeInfo(email = email, firstName = fName, lastName = lName, password = pwd.toCharArray, picToken = picToken, picHeight = picH, picWidth = picW, cropX = cX, cropY = cY, cropSize = cS))
      ((sfi:SocialFinalizeInfo) =>
        Some(sfi.email, sfi.firstName, sfi.lastName, sfi.password.toString, sfi.picToken, sfi.picHeight, sfi.picWidth, sfi.cropX, sfi.cropY, sfi.cropSize))
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
  def doUserPassFinalizeAccountAction(implicit request: AuthenticatedRequest[JsValue]): Result = {
    userPassFinalizeAccountForm.bindFromRequest.fold(
    formWithErrors => Forbidden(Json.obj("error" -> "user_exists_failed_auth")),
    { case efi:EmailPassFinalizeInfo =>
      val inviteExtIdOpt: Option[ExternalId[Invitation]] = request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value))
      val (user, email, newIdentity) = authCommander.finalizeEmailPassAccount(efi, request.userId, request.user.externalId, request.identityOpt, inviteExtIdOpt)
      finishSignup(user, email, newIdentity, emailConfirmedAlready = false)
    })
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
                from = EmailAddresses.NOTIFICATIONS,
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
            for (sui <- socialRepo.getByUser(pr.userId) if sui.networkType == SocialNetworks.FORTYTWO) {
              UserService.save(UserIdentity(
                userId = sui.userId,
                socialUser = sui.credentials.get.copy(
                  passwordInfo = Some(current.plugin[PasswordHasher].get.hash(password))
                )
              ))
            }
            Ok(Json.obj("uri" -> com.keepit.controllers.website.routes.HomeController.home.url)) // TODO: create session
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
            Redirect("/?m=1")
          case (true, true) if request.userIdOpt.isEmpty || (request.userIdOpt.isDefined && request.userIdOpt.get.id == user.id.get.id) =>
            // first time being used, not logged in OR logged in as correct user
            authenticateUser(user.id.get,
              error => throw error,
              authenticator => {
                val resp = if (kifiInstallationRepo.all(user.id.get, Some(KifiInstallationStates.INACTIVE)).isEmpty) { // todo: factor out
                  // user has no installations
                  Redirect("/install")
                } else {
                  Redirect("/profile?m=1")
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
