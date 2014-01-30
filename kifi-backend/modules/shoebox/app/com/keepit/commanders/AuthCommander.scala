package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.time.Clock
import com.keepit.common.healthcheck.{AirbrakeError, AirbrakeNotifier}
import com.keepit.model._
import com.keepit.common.store.{ImageCropAttributes, S3ImageStore}
import com.keepit.common.mail._
import com.keepit.common.db.{ExternalId, Id}
import securesocial.core._
import com.keepit.social._
import securesocial.core.providers.utils.{PasswordHasher, GravatarHelper}
import securesocial.core.IdentityId
import securesocial.core.PasswordInfo
import com.keepit.social.UserIdentity
import com.keepit.social.SocialId
import com.keepit.common.logging.Logging
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.controllers.core.AuthHelper
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common._
import com.keepit.common.performance.timing
import scala.concurrent.Future
import com.keepit.heimdal._
import securesocial.core.IdentityId
import scala.Some
import securesocial.core.PasswordInfo
import com.keepit.social.SocialId
import com.keepit.social.UserIdentity

case class EmailPassword(email: String, password: Array[Char])
object EmailPassword {
  implicit val format = (
      (__ \ 'email).format[String] and
      (__ \ 'password).format[String].inmap((s:String) => s.toCharArray, unlift((c:Array[Char]) => Some(c.toString)))
  )(EmailPassword.apply, unlift(EmailPassword.unapply))
}

case class SocialFinalizeInfo(
  email: String,
  firstName: String,
  lastName: String,
  password: Array[Char],
  picToken: Option[String],
  picHeight: Option[Int],
  picWidth: Option[Int],
  cropX: Option[Int],
  cropY: Option[Int],
  cropSize: Option[Int])

object SocialFinalizeInfo {
  implicit val format = (
      (__ \ 'email).format[String] and
      (__ \ 'firstName).format[String] and
      (__ \ 'lastName).format[String] and
      (__ \ 'password).format[String].inmap((s:String) => s.toCharArray, unlift((c:Array[Char]) => Some(c.toString))) and
      (__ \ 'picToken).formatNullable[String] and
      (__ \ 'picHeight).formatNullable[Int] and
      (__ \ 'picWidth).formatNullable[Int] and
      (__ \ 'cropX).formatNullable[Int] and
      (__ \ 'cropY).formatNullable[Int] and
      (__ \ 'cropSize).formatNullable[Int]
  )(SocialFinalizeInfo.apply, unlift(SocialFinalizeInfo.unapply))
}

case class EmailPassFinalizeInfo(
  firstName: String,
  lastName: String,
  picToken: Option[String],
  picWidth: Option[Int],
  picHeight: Option[Int],
  cropX: Option[Int],
  cropY: Option[Int],
  cropSize: Option[Int])

object EmailPassFinalizeInfo {
  implicit val format = (
      (__ \ 'firstName).format[String] and
      (__ \ 'lastName).format[String] and
      (__ \ 'picToken).formatNullable[String] and
      (__ \ 'picHeight).formatNullable[Int] and
      (__ \ 'picWidth).formatNullable[Int] and
      (__ \ 'cropX).formatNullable[Int] and
      (__ \ 'cropY).formatNullable[Int] and
      (__ \ 'cropSize).formatNullable[Int]
  )(EmailPassFinalizeInfo.apply, unlift(EmailPassFinalizeInfo.unapply))
}

class AuthCommander @Inject()(
  db: Database,
  clock: Clock,
  airbrakeNotifier: AirbrakeNotifier,
  userRepo: UserRepo,
  userCredRepo: UserCredRepo,
  socialRepo: SocialUserInfoRepo,
  emailAddressRepo: EmailAddressRepo,
  userValueRepo: UserValueRepo,
  passwordResetRepo: PasswordResetRepo,
  s3ImageStore: S3ImageStore,
  postOffice: LocalPostOffice,
  inviteCommander: InviteCommander,
  userExperimentRepo: UserExperimentRepo,
  heimdalServiceClient: HeimdalServiceClient
) extends Logging {

  def saveUserPasswordIdentity(userIdOpt: Option[Id[User]], identityOpt: Option[Identity],
                               email: String, passwordInfo: PasswordInfo,
                               firstName: String = "", lastName: String = "", isComplete: Boolean): (UserIdentity, Id[User]) = timing(s"[saveUserPasswordIdentity($userIdOpt, $email)]") {
    log.info(s"[saveUserPassIdentity] userId=$userIdOpt identityOpt=$identityOpt email=$email pInfo=$passwordInfo isComplete=$isComplete")
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
      isComplete = isComplete
    )

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

    val confusedCompilerUserId = userIdFromEmailIdentity getOrElse {
      val userIdOpt = for {
        socialUserInfo <- db.readOnly { implicit s =>socialRepo.getOpt(SocialId(newIdentity.identityId.userId), SocialNetworks.FORTYTWO) }
        userId <- socialUserInfo.userId
      } yield userId
      userIdOpt.get
    }

    (newIdentity, confusedCompilerUserId)
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

  def finalizeSocialAccount(sfi:SocialFinalizeInfo, socialIdentity: Identity, inviteExtIdOpt:Option[ExternalId[Invitation]])(implicit existingContext: HeimdalContext) =
    timing(s"[finalizeSocialAccount(${socialIdentity.identityId.providerId + "#" + socialIdentity.identityId.userId})]") {
      log.info(s"[finalizeSocialAccount] sfi=$sfi identity=$socialIdentity extId=$inviteExtIdOpt")
      require(AuthHelper.validatePwd(sfi.password), "invalid password")
      val currentHasher = Registry.hashers.currentHasher
      val pInfo = timing(s"[finalizeSocialAccount] hash") {
        currentHasher.hash(sfi.password.toString) // SecureSocial takes String only
      }

      val (emailPassIdentity, userId) = saveUserPasswordIdentity(None, Some(socialIdentity),
        email = sfi.email, passwordInfo = pInfo, firstName = sfi.firstName, lastName = sfi.lastName, isComplete = true)

      val user = db.readOnly { implicit session =>
        userRepo.get(userId)
      }

      reportUserRegistration(user, inviteExtIdOpt)

      val cropAttributes = parseCropForm(sfi.picHeight, sfi.picWidth, sfi.cropX, sfi.cropY, sfi.cropSize) tap (r => log.info(s"Cropped attributes for ${user.id.get}: " + r))
      sfi.picToken.map { token =>
        s3ImageStore.copyTempFileToUserPic(user.id.get, user.externalId, token, cropAttributes)
      }

      SafeFuture { inviteCommander.markPendingInvitesAsAccepted(userId, inviteExtIdOpt) }

      (user, emailPassIdentity)
    }

  def finalizeEmailPassAccount(efi:EmailPassFinalizeInfo, userId:Id[User], externalUserId:ExternalId[User], identityOpt:Option[Identity], inviteExtIdOpt:Option[ExternalId[Invitation]])(implicit context: HeimdalContext): Future[(User, String, Identity)] = {
    require(userId != null && externalUserId != null, "userId and externalUserId cannot be null")
    log.info(s"[finalizeEmailPassAccount] efi=$efi, userId=$userId, extUserId=$externalUserId, identity=$identityOpt, inviteExtId=$inviteExtIdOpt")

    val resultFuture = SafeFuture {
      val identity = db.readOnly { implicit session =>
        socialRepo.getByUser(userId).find(_.networkType == SocialNetworks.FORTYTWO).flatMap(_.credentials)
      } getOrElse (identityOpt.get)

      val passwordInfo = identity.passwordInfo.get
      val email = identity.email.get
      val (newIdentity, _) = saveUserPasswordIdentity(Some(userId), identityOpt, email = email, passwordInfo = passwordInfo, firstName = efi.firstName, lastName = efi.lastName, isComplete = true)
      val user = db.readOnly(userRepo.get(userId)(_))

      reportUserRegistration(user, inviteExtIdOpt)

      (user, email, newIdentity)
    }

    val imageFuture = SafeFuture {
      val user = db.readOnly(userRepo.get(userId)(_))
      val cropAttributes = parseCropForm(efi.picHeight, efi.picWidth, efi.cropX, efi.cropY, efi.cropSize) tap (r => log.info(s"Cropped attributes for ${userId}: " + r))
      efi.picToken.map { token =>
        s3ImageStore.copyTempFileToUserPic(userId, externalUserId, token, cropAttributes)
      }.orElse {
        s3ImageStore.getPictureUrl(None, user, "0")
        None
      }
    }

    SafeFuture { inviteCommander.markPendingInvitesAsAccepted(userId, inviteExtIdOpt) }

    for {
      image <- imageFuture
      result <- resultFuture
    } yield result
  }

  private def reportUserRegistration(user: User, inviteExtIdOpt: Option[ExternalId[Invitation]])(implicit existingContext: HeimdalContext): Unit = SafeFuture {
    val userId = user.id.get
    val contextBuilder = new HeimdalContextBuilder
    contextBuilder.data ++= existingContext.data
    contextBuilder += ("action", "registered")
    val experiments = db.readOnly() { implicit session => userExperimentRepo.getUserExperiments(userId) }
    contextBuilder.addExperiments(experiments)
    inviteExtIdOpt.foreach { invite => contextBuilder += ("acceptedInvite", invite.id) }

    heimdalServiceClient.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.JOINED, user.createdAt))
  }
}
