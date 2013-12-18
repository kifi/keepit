package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.time.Clock
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.common.store.{ImageCropAttributes, S3ImageStore}
import com.keepit.common.mail._
import com.keepit.common.db.{ExternalId, Id}
import securesocial.core._
import com.keepit.social._
import securesocial.core.providers.utils.GravatarHelper
import securesocial.core.IdentityId
import scala.Some
import securesocial.core.PasswordInfo
import com.keepit.social.UserIdentity
import com.keepit.social.SocialId
import com.keepit.common._
import com.keepit.common.logging.Logging
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class SocialFinalizeInfo(
  email: String,
  password: String,
  firstName: String,
  lastName: String,
  picToken: Option[String],
  picHeight: Option[Int], picWidth: Option[Int],
  cropX: Option[Int], cropY: Option[Int],
  cropSize: Option[Int])

object SocialFinalizeInfo {
  implicit val format = (
      (__ \ 'email).format[String] and
      (__ \ 'password).format[String] and
      (__ \ 'firstName).format[String] and
      (__ \ 'lastName).format[String] and
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
  inviteCommander: InviteCommander
) extends Logging {

  def saveUserPasswordIdentity(userIdOpt: Option[Id[User]], identityOpt: Option[Identity],
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

  def finalizeSocialAccount(sfi:SocialFinalizeInfo, userIdOpt: Option[Id[User]], identityOpt:Option[Identity], inviteExtIdOpt:Option[ExternalId[Invitation]]) = {

    val pInfo = Registry.hashers.currentHasher.hash(sfi.password)

    val (emailPassIdentity, userId) = saveUserPasswordIdentity(userIdOpt, identityOpt,
      email = sfi.email, passwordInfo = pInfo, firstName = sfi.firstName, lastName = sfi.lastName, isComplete = true)

    inviteCommander.markPendingInvitesAsAccepted(userId, inviteExtIdOpt)

    val user = db.readOnly { implicit session =>
      userRepo.get(userId)
    }

    val cropAttributes = parseCropForm(sfi.picHeight, sfi.picWidth, sfi.cropX, sfi.cropY, sfi.cropSize) tap (r => log.info(s"Cropped attributes for ${user.id.get}: " + r))
    sfi.picToken.map { token =>
      s3ImageStore.copyTempFileToUserPic(user.id.get, user.externalId, token, cropAttributes)
    }

    (user, emailPassIdentity)
  }

  def finalizeEmailPassAccount(efi:EmailPassFinalizeInfo, userId:Id[User], externalUserId:ExternalId[User], identityOpt:Option[Identity], inviteExtIdOpt:Option[ExternalId[Invitation]]) = {
    require(userId != null && externalUserId != null, "userId and externalUserId cannot be null")

    val identity = db.readOnly { implicit session =>
      socialRepo.getByUser(userId).find(_.networkType == SocialNetworks.FORTYTWO).flatMap(_.credentials)
    } getOrElse (identityOpt.get)

    val passwordInfo = identity.passwordInfo.get
    val email = identity.email.get
    val (newIdentity, _) = saveUserPasswordIdentity(Some(userId), identityOpt, email = email, passwordInfo = passwordInfo, firstName = efi.firstName, lastName = efi.lastName, isComplete = true)

    inviteCommander.markPendingInvitesAsAccepted(userId, inviteExtIdOpt)

    val user = db.readOnly(userRepo.get(userId)(_))

    val cropAttributes = parseCropForm(efi.picHeight, efi.picWidth, efi.cropX, efi.cropY, efi.cropSize) tap (r => log.info(s"Cropped attributes for ${userId}: " + r))
    efi.picToken.map { token =>
      s3ImageStore.copyTempFileToUserPic(userId, externalUserId, token, cropAttributes)
    }.orElse {
      s3ImageStore.getPictureUrl(None, user, "0")
      None
    }

    (user, email, newIdentity)
  }

}
