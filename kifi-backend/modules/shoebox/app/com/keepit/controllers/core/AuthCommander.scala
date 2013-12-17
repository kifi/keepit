package com.keepit.controllers.core

import com.google.inject.Inject
import play.api.mvc._
import play.api.http.{Status, HeaderNames}
import play.api.libs.json.{JsNumber, Json, JsValue}
import securesocial.core._
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.commanders.InviteCommander
import com.keepit.social._
import securesocial.core.providers.utils.GravatarHelper
import com.keepit.common.controller.ActionAuthenticator._
import com.keepit.common.store.{ImageCropAttributes, S3ImageStore}
import com.keepit.common._
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.time._
import play.api.Play._
import securesocial.core.IdentityId
import scala.Some
import play.api.mvc.SimpleResult
import play.api.mvc.DiscardingCookie
import securesocial.core.PasswordInfo
import play.api.mvc.Cookie
import com.keepit.common.mail.GenericEmailAddress
import com.keepit.social.SocialId
import com.keepit.model.Invitation
import com.keepit.social.UserIdentity
import play.api.data._
import play.api.data.Forms._
import com.keepit.common.controller.AuthenticatedRequest
import scala.util.{Failure, Success}
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}

class AuthCommander @Inject() (
  db: Database,
  clock: Clock,
  airbrakeNotifier:AirbrakeNotifier,
  userRepo: UserRepo,
  userCredRepo: UserCredRepo,
  socialRepo: SocialUserInfoRepo,
  emailAddressRepo: EmailAddressRepo,
  userValueRepo: UserValueRepo,
  s3ImageStore: S3ImageStore,
  postOffice: LocalPostOffice,
  inviteCommander: InviteCommander
) extends HeaderNames with Results with Status with Logging {

  def authHandler(request:Request[_], res:SimpleResult[_])(f : => (Seq[Cookie], Session) => Result) = {
    val resCookies = res.header.headers.get(SET_COOKIE).map(Cookies.decode).getOrElse(Seq.empty)
    val resSession = Session.decodeFromCookie(resCookies.find(_.name == Session.COOKIE_NAME))
    f(resCookies, resSession)
  }

  private def saveUserPasswordIdentity(userIdOpt: Option[Id[User]], identityOpt: Option[Identity],
                                       email: String, passwordInfo: PasswordInfo,
                                       firstName: String = "", lastName: String = "", isComplete: Boolean): (UserIdentity, Id[User]) = {
    val fName = User.sanitizeName(if (isComplete || firstName.nonEmpty) firstName else email)
    val lName = User.sanitizeName(lastName)
    val newIdentity = UserIdentity(
      userId = userIdOpt,
      socialUser = SocialUser(
        identityId = IdentityId(email, SocialNetworks.FORTYTWO.authProvider),
        firstName = fName,
        lastName = lName,
        fullName = s"$fName $lName",
        email = Some(email),
        avatarUrl = GravatarHelper.avatarFor(email),
        authMethod = AuthenticationMethod.UserPassword,
        passwordInfo = Some(passwordInfo)
      ),
      allowSignup = true,
      isComplete = isComplete)

    UserService.save(newIdentity) // Kifi User is created here if it doesn't exist

    val userIdFromEmailIdentity = for {
      identity <- identityOpt
      socialUserInfo <- db.readOnly { implicit s =>
        socialRepo.getOpt(SocialId(newIdentity.identityId.userId), SocialNetworks.FORTYTWO)
      }
      userId <- socialUserInfo.userId
    } yield {
      UserService.save(UserIdentity(userId = Some(userId), socialUser = SocialUser(identity)))
      userId
    }

    val user = userIdFromEmailIdentity.orElse {
      db.readOnly { implicit s =>
        socialRepo.getOpt(SocialId(newIdentity.identityId.userId), SocialNetworks.FORTYTWO).map(_.userId).flatten
      }
    }

    (newIdentity, user.get)
  }

  private def parseCropForm(picHeight: Option[Int], picWidth: Option[Int], cropX: Option[Int], cropY: Option[Int], cropSize: Option[Int]) = {
    for {
      h <- picHeight
      w <- picWidth
      x <- cropX
      y <- cropY
      s <- cropSize
    } yield ImageCropAttributes(w = w, h = h, x = x, y = y, s = s)
  }

  private val url = current.configuration.getString("application.baseUrl").get

  def finishSignup(user: User, emailAddress: String, newIdentity: Identity, emailConfirmedAlready: Boolean)(implicit request: Request[JsValue]): Result = {
    if (!emailConfirmedAlready) {
      db.readWrite { implicit s =>
        val emailAddrStr = newIdentity.email.getOrElse(emailAddress)
        val emailAddr = emailAddressRepo.save(emailAddressRepo.getByAddressOpt(emailAddrStr).get.withVerificationCode(clock.now))
        val verifyUrl = s"$url${routes.AuthController.verifyEmail(emailAddr.verificationCode.get)}"
        userValueRepo.setValue(user.id.get, "pending_primary_email", emailAddr.address)

        if (user.state != UserStates.ACTIVE) {
          postOffice.sendMail(ElectronicMail(
            from = EmailAddresses.NOTIFICATIONS,
            to = Seq(GenericEmailAddress(emailAddrStr)),
            subject = "Kifi.com | Please confirm your email address",
            htmlBody = views.html.email.verifyEmail(newIdentity.firstName, verifyUrl).body,
            category = PostOffice.Categories.User.EMAIL_CONFIRMATION
          ))
        } else {
          postOffice.sendMail(ElectronicMail(
            from = EmailAddresses.NOTIFICATIONS,
            to = Seq(GenericEmailAddress(emailAddrStr)),
            subject = "Welcome to Kifi! Please confirm your email address",
            htmlBody = views.html.email.verifyAndWelcomeEmail(user, verifyUrl).body,
            category = PostOffice.Categories.User.EMAIL_CONFIRMATION
          ))
        }
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

  private case class SocialFinalizeInfo(
    email: String,
    password: String,
    firstName: String,
    lastName: String,
    picToken: Option[String],
    picHeight: Option[Int], picWidth: Option[Int],
    cropX: Option[Int], cropY: Option[Int],
    cropSize: Option[Int])
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
    )
      (SocialFinalizeInfo.apply)
      (SocialFinalizeInfo.unapply)
  )
  def doSocialFinalizeAccountAction(implicit request: Request[JsValue]): Result = {
    socialFinalizeAccountForm.bindFromRequest.fold(
    formWithErrors => BadRequest(Json.obj("error" -> formWithErrors.errors.head.message)),
    { case SocialFinalizeInfo(emailAddress, firstName, lastName, password, picToken, picHeight, picWidth, cropX, cropY, cropSize) =>
      val pInfo = Registry.hashers.currentHasher.hash(password)

      val (emailPassIdentity, userId) = saveUserPasswordIdentity(request.userIdOpt, request.identityOpt,
        email = emailAddress, passwordInfo = pInfo, firstName = firstName, lastName = lastName, isComplete = true)

      inviteCommander.markPendingInvitesAsAccepted(userId, request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value)))

      val user = db.readOnly { implicit session =>
        userRepo.get(userId)
      }

      val cropAttributes = parseCropForm(picHeight, picWidth, cropX, cropY, cropSize) tap (r => log.info(s"Cropped attributes for ${user.id.get}: " + r))
      picToken.map { token =>
        s3ImageStore.copyTempFileToUserPic(user.id.get, user.externalId, token, cropAttributes)
      }

      val emailConfirmedBySocialNetwork = request.identityOpt.flatMap(_.email).exists(_.trim == emailAddress.trim)

      finishSignup(user, emailAddress, emailPassIdentity, emailConfirmedAlready = emailConfirmedBySocialNetwork)
    })
  }

  private case class EmailPassFinalizeInfo(
    firstName: String,
    lastName: String,
    picToken: Option[String],
    picWidth: Option[Int],
    picHeight: Option[Int],
    cropX: Option[Int],
    cropY: Option[Int],
    cropSize: Option[Int])
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
    { case EmailPassFinalizeInfo(firstName, lastName, picToken, picHeight, picWidth, cropX, cropY, cropSize) =>
      val identity = db.readOnly { implicit session =>
        socialRepo.getByUser(request.userId).find(_.networkType == SocialNetworks.FORTYTWO).flatMap(_.credentials)
      } getOrElse(request.identityOpt.get)
      val passwordInfo = identity.passwordInfo.get
      val email = identity.email.get
      val (newIdentity, userId) = saveUserPasswordIdentity(request.userIdOpt, request.identityOpt, email = email, passwordInfo = passwordInfo,
        firstName = firstName, lastName = lastName, isComplete = true)

      inviteCommander.markPendingInvitesAsAccepted(userId, request.cookies.get("inv").flatMap(v => ExternalId.asOpt[Invitation](v.value)))

      val user = db.readOnly(userRepo.get(userId)(_))

      val cropAttributes = parseCropForm(picHeight, picWidth, cropX, cropY, cropSize) tap (r => log.info(s"Cropped attributes for ${request.user.id.get}: " + r))
      picToken.map { token =>
        s3ImageStore.copyTempFileToUserPic(request.user.id.get, request.user.externalId, token, cropAttributes)
      }.orElse {
        s3ImageStore.getPictureUrl(None, user, "0")
        None
      }

      finishSignup(user, email, newIdentity, emailConfirmedAlready = false)
    })
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
