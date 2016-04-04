package com.keepit.commanders

import java.util.UUID

import com.google.inject.Inject
import com.keepit.common.controller.KifiSession

import com.keepit.common.core._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail._
import com.keepit.common.oauth._
import com.keepit.common.performance.timing
import com.keepit.common.store.{ ImageCropAttributes, S3ImageStore }
import com.keepit.common.time.Clock
import com.keepit.common.logging.Logging
import com.keepit.controllers.core.{ AuthHelper }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.social._
import org.apache.commons.lang3.RandomStringUtils
import play.api.http.Status

import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.libs.oauth.RequestToken
import play.api.libs.ws.WS
import play.api.mvc.BodyParsers.parse
import play.api.mvc.{ Request, RequestHeader, Result }
import play.api.mvc.Results.{ NotFound, Ok }

import scala.Some
import scala.concurrent.{ ExecutionContext, Future }

import securesocial.core._
import securesocial.core.providers.utils.GravatarHelper

import scala.util.{ Failure, Success, Try }
import KifiSession._

case class EmailPassword(email: EmailAddress, password: Option[String])
object EmailPassword {
  implicit val format = (
    (__ \ 'email).format[EmailAddress] and
    (__ \ 'password).formatNullable[String]
  )(EmailPassword.apply, unlift(EmailPassword.unapply))
}

case class SocialFinalizeInfo(
  email: EmailAddress,
  firstName: String,
  lastName: String,
  password: Option[String],
  picToken: Option[String],
  picHeight: Option[Int],
  picWidth: Option[Int],
  cropX: Option[Int],
  cropY: Option[Int],
  cropSize: Option[Int])

object SocialFinalizeInfo {
  implicit val format = (
    (__ \ 'email).format[EmailAddress] and
    (__ \ 'firstName).format[String] and
    (__ \ 'lastName).format[String] and
    (__ \ 'password).formatNullable[String] and
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
  cropSize: Option[Int],
  companyName: Option[String])

object EmailPassFinalizeInfo {
  implicit val format = (
    (__ \ 'firstName).format[String] and
    (__ \ 'lastName).format[String] and
    (__ \ 'picToken).formatNullable[String] and
    (__ \ 'picHeight).formatNullable[Int] and
    (__ \ 'picWidth).formatNullable[Int] and
    (__ \ 'cropX).formatNullable[Int] and
    (__ \ 'cropY).formatNullable[Int] and
    (__ \ 'cropSize).formatNullable[Int] and
    (__ \ 'companyName).formatNullable[String]
  )(EmailPassFinalizeInfo.apply, unlift(EmailPassFinalizeInfo.unapply))
}

class AuthCommander @Inject() (
    db: Database,
    clock: Clock,
    airbrake: AirbrakeNotifier,
    oauth2ProviderRegistry: OAuth2ProviderRegistry,
    userRepo: UserRepo,
    userCredRepo: UserCredRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    emailAddressRepo: UserEmailAddressRepo,
    emailAddressCommander: UserEmailAddressCommander,
    keepRepo: KeepRepo,
    ktuCommander: KeepToUserCommander,
    keepCommander: KeepCommander,
    userValueRepo: UserValueRepo,
    s3ImageStore: S3ImageStore,
    inviteCommander: InviteCommander,
    libraryMembershipCommander: LibraryMembershipCommander,
    orgInviteCommander: OrganizationInviteCommander,
    permissionCommander: PermissionCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext,
    userExperimentCommander: LocalUserExperimentCommander,
    userCommander: UserCommander,
    handleCommander: HandleCommander,
    heimdalServiceClient: HeimdalServiceClient,
    eliza: ElizaServiceClient,
    amazonSimpleMailProvider: AmazonSimpleMailProvider) extends Logging {

  def saveUserPasswordIdentity(userIdOpt: Option[Id[User]], email: EmailAddress, passwordInfoOpt: Option[PasswordInfo],
    firstName: String, lastName: String, isComplete: Boolean): (UserIdentity, Id[User]) = {
    if (email.address.trim.isEmpty) {
      throw new Exception(s"email address is empty for user $userIdOpt name $firstName, $lastName")
    }
    log.info(s"[saveUserPassIdentity] userId=$userIdOpt email=$email pInfo=$passwordInfoOpt isComplete=$isComplete")
    val fName = User.sanitizeName(if (isComplete || firstName.nonEmpty) firstName else email.address)
    val lName = User.sanitizeName(lastName)

    val (passwordInfo, usedAutoAssignedPassword) = passwordInfoOpt.map(p => (p, false)).getOrElse((Registry.hashers.currentHasher.hash(UUID.randomUUID.toString), true))

    val newIdentity = NewUserIdentity(
      userId = userIdOpt,
      identity = EmailPasswordIdentity(fName, lName, email, Some(passwordInfo))
    )

    val savedIdentity = saveUserIdentity(newIdentity) // Kifi User is created here if it doesn't exist

    savedIdentity match {
      case userIdentity @ UserIdentity(_, Some(userId)) => {
        db.readWrite { implicit rw =>
          if (!isComplete) {
            // fix-up: with UserIdentity.isComplete gone, UserService.save creates user in ACTIVE state by default
            val user = userRepo.get(userId)
            if (user.state != UserStates.INCOMPLETE_SIGNUP) {
              userRepo.save(user.copy(state = UserStates.INCOMPLETE_SIGNUP))
            }
          }
          userValueRepo.setValue(userId, UserValueName.HAS_NO_PASSWORD, usedAutoAssignedPassword)
        }
        (userIdentity, userId)
      }
      case _ => {
        val error = new IllegalStateException(s"[saveUserPasswordIdentity] Kifi User for ${email} has not been created. savedIdentity=$savedIdentity")
        airbrake.notify(error)
        throw error
      }
    }
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

  def finalizeSocialAccount(sfi: SocialFinalizeInfo, socialIdentity: UserIdentity, inviteExtIdOpt: Option[ExternalId[Invitation]])(implicit existingContext: HeimdalContext) =
    timing(s"[finalizeSocialAccount(${socialIdentity.identityId.providerId + "#" + socialIdentity.identityId.userId})]") {
      log.info(s"[finalizeSocialAccount] sfi=$sfi identity=$socialIdentity extId=$inviteExtIdOpt")
      val currentHasher = Registry.hashers.currentHasher
      val email = if (sfi.email.address.trim.isEmpty) {
        val alternative = EmailAddress(s"NoMailUser+${socialIdentity.identityId.providerId}_${socialIdentity.identityId.userId}@kifi.com")
        airbrake.notify(s"generated alternative email $alternative for SFI $sfi of social identity $socialIdentity with invite $inviteExtIdOpt")
        alternative
      } else sfi.email

      val pInfo = sfi.password.map { p =>
        currentHasher.hash(p)
      }

      val (emailPassIdentity, userId) = saveUserPasswordIdentity(None, email = email, passwordInfoOpt = pInfo, firstName = sfi.firstName, lastName = sfi.lastName, isComplete = true)

      saveUserIdentity(socialIdentity.withUserId(userId)) // SocialUserInfo is claimed here

      val user = db.readWrite { implicit session =>
        val userPreUsername = userRepo.get(userId)
        handleCommander.autoSetUsername(userPreUsername) getOrElse userPreUsername
      }

      reportUserRegistration(user, inviteExtIdOpt)
      val cropAttributes = parseCropForm(sfi.picHeight, sfi.picWidth, sfi.cropX, sfi.cropY, sfi.cropSize) tap (r => log.info(s"Cropped attributes for ${user.id.get}: " + r))
      sfi.picToken.map { token =>
        s3ImageStore.copyTempFileToUserPic(user.id.get, user.externalId, token, cropAttributes)
      }

      SafeFuture { inviteCommander.markPendingInvitesAsAccepted(userId, inviteExtIdOpt) }

      (user, emailPassIdentity)
    }

  def processCompanyNameFromSignup(user: User, companyNameOpt: Option[String]): Unit = {
    companyNameOpt.foreach { companyName =>
      val trimmed = companyName.trim
      if (trimmed.nonEmpty) {
        userCommander.updateUserBiography(user.id.get, s"Works at $trimmed");
        db.readWrite { implicit session =>
          userValueRepo.setValue(user.id.get, UserValueName.COMPANY_NAME, trimmed)
        }

        val informationalMessage = s"""
          UserId:<a href="https://admin.kifi.com/admin/user/${user.id.get}">${user.id.get}</a>, Name: ${user.firstName} ${user.lastName}""
        """
        amazonSimpleMailProvider.sendMail(ElectronicMail(
          from = SystemEmailAddress.ENG42,
          to = Seq(SystemEmailAddress.SALES),
          subject = s"New user from company: $trimmed",
          htmlBody = informationalMessage,
          category = NotificationCategory.toElectronicMailCategory(NotificationCategory.System.LEADS)
        ))

      }
    }
  }

  def finalizeEmailPassAccount(efi: EmailPassFinalizeInfo, userId: Id[User], externalUserId: ExternalId[User], identityIdOpt: Option[IdentityId], inviteExtIdOpt: Option[ExternalId[Invitation]])(implicit context: HeimdalContext): Future[(User, EmailAddress, Identity)] = {
    log.info(s"[finalizeEmailPassAccount] efi=$efi, userId=$userId, extUserId=$externalUserId, identityId=$identityIdOpt, inviteExtId=$inviteExtIdOpt")

    val identityOpt = identityIdOpt.flatMap(getUserIdentity)
    val resultFuture = SafeFuture {
      val (passwordInfo, email) = db.readOnlyMaster { implicit session =>
        val passwordInfo = userCredRepo.findByUserIdOpt(userId).map { userCred =>
          PasswordInfo(hasher = "bcrypt", password = userCred.credentials)
        } getOrElse identityOpt.flatMap(_.passwordInfo).get

        val emailAddress = Try(emailAddressRepo.getByUser(userId)).toOption getOrElse identityOpt.flatMap(_.email.flatMap(EmailAddress.validate(_).toOption)).get
        (passwordInfo, emailAddress)
      }

      val (newIdentity, _) = saveUserPasswordIdentity(Some(userId), email = email, passwordInfoOpt = Some(passwordInfo), firstName = efi.firstName, lastName = efi.lastName, isComplete = true)

      val user = db.readWrite { implicit session =>
        val userPreUsername = userRepo.get(userId)
        handleCommander.autoSetUsername(userPreUsername) getOrElse userPreUsername
      }

      SafeFuture {
        processCompanyNameFromSignup(user, efi.companyName)
      }

      reportUserRegistration(user, inviteExtIdOpt)

      (user, email, newIdentity)
    }

    val imageFuture = SafeFuture {
      val user = db.readOnlyMaster(userRepo.get(userId)(_))
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
    val experiments = userExperimentCommander.getExperimentsByUser(userId)
    contextBuilder.addExperiments(experiments)
    inviteExtIdOpt.foreach { invite => contextBuilder += ("acceptedInvite", invite.id) }

    heimdalServiceClient.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.JOINED, user.createdAt))
  }

  def getUserIdentity(identityId: IdentityId): Option[UserIdentity] = UserService.find(identityId).map {
    case userIdentity: UserIdentity => userIdentity
    case unexpectedIdentity => throw new IllegalStateException(s"Unexpected identity: $unexpectedIdentity")
  }

  def saveUserIdentity(identity: MaybeUserIdentity): UserIdentity = UserService.save(identity) match {
    case userIdentity: UserIdentity => userIdentity
    case unexpectedIdentity => throw new IllegalStateException(s"Unexpected identity: $unexpectedIdentity")
  }

  def isEmailAddressAlreadyOwned(identity: Identity): Boolean = IdentityHelpers.parseEmailAddress(identity).toOption.exists { address =>
    getUserIdentity(IdentityHelpers.toIdentityId(address)).exists(_.userId.isDefined)
  }

  def signupWithTrustedSocialUser(socialUser: UserIdentity, signUpUrl: String)(implicit request: Request[_]): Result = {
    val userIdentity = saveUserIdentity(socialUser)
    val (payload, newSession) = userIdentity.userId match {
      case Some(userId) => (
        Json.obj("code" -> "user_logged_in"),
        Events.fire(new LoginEvent(userIdentity))
      )
      case None => (
        if (isEmailAddressAlreadyOwned(socialUser)) Json.obj("code" -> "connect_option", "uri" -> signUpUrl) else Json.obj("code" -> "continue_signup"),
        None
      )
    }
    Authenticator.create(userIdentity).fold(
      error => throw error,
      authenticator =>
        Ok(payload ++ Json.obj("sessionId" -> authenticator.id))
          .withSession(newSession.getOrElse(request.session) - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
          .withCookies(authenticator.toCookie)
    )
  }

  def loginWithTrustedSocialIdentity(identityId: IdentityId)(implicit request: RequestHeader): Result = {
    log.info(s"[loginWithTrustedSocialIdentity(${identityId})]")
    // more fine-grained error handling required
    val resOpt = for {
      identity <- getUserIdentity(identityId)
      userId <- identity.userId
    } yield {
      log.info(s"[loginWithTrustedSocialIdentity($identityId)] kifi user $userId")
      val newSession = Events.fire(new LoginEvent(identity)).getOrElse(request.session)
      Authenticator.create(identity) fold (
        error => throw error,
        authenticator =>
          Ok(Json.obj("code" -> "user_logged_in", "sessionId" -> authenticator.id))
            .withSession((newSession - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey).setUserId(userId))
            .withCookies(authenticator.toCookie)
      )
    }
    resOpt getOrElse {
      log.info(s"[loginWithTrustedSocialIdentity($identityId})] user not found")
      NotFound(Json.obj("error" -> "user_not_found"))
    }
  }

  def autoJoinLib(userId: Id[User], libId: Id[Library], authToken: Option[String]): Boolean = { // true for success, false for failure
    // Abstracting away errors and manually reporting. If someone needs the specific error, feel free to change the signature.
    implicit val context = HeimdalContext(Map())
    libraryMembershipCommander.joinLibrary(userId, libId, authToken).fold(
      { libFail =>
        airbrake.notify(s"[finishSignup] lib-auto-join failed. $libFail")
        false
      },
      { library =>
        true
      }
    )
  }

  def autoJoinOrg(userId: Id[User], orgId: Id[Organization], authToken: String): Boolean = {
    implicit val context = HeimdalContext.empty
    orgInviteCommander.acceptInvitation(orgId, userId, Some(authToken)).fold(
      { orgFail =>
        airbrake.notify(s"[finishSignup] org-auto-join failed. $orgFail")
        false
      },
      { org =>
        log.info(s"[finishSignup] user(id=$userId) has successfully joined organization $org")
        true
      }
    )
  }

  def autoJoinKeep(userId: Id[User], keepId: Id[Keep], accessTokenOpt: Option[String]): Boolean = {
    implicit val context = HeimdalContext.empty
    val hasPermission = db.readOnlyMaster(implicit s => permissionCommander.getKeepPermissions(keepId, Some(userId)).contains(KeepPermission.ADD_MESSAGE))
    if (hasPermission) {
      db.readWrite { implicit s =>
        val keep = keepRepo.get(keepId)
        keepCommander.unsafeModifyKeepConnections(keep, KeepConnectionsDiff.addUser(userId), userAttribution = None)
      }
    }
    // user may not have explicit permission to be added, but implicit via access token from email participation. add them.
    accessTokenOpt.foreach { accessToken =>
      eliza.convertNonUserThreadToUserThread(userId, accessToken).foreach {
        case (emailOpt, addedByOpt) =>
          db.readWrite { implicit s =>
            emailOpt.map(email => emailAddressCommander.saveAsVerified(UserEmailAddress.create(userId = userId, address = email)))
            val keep = keepRepo.get(keepId)
            keepCommander.unsafeModifyKeepConnections(keep, KeepConnectionsDiff.addUser(userId), userAttribution = None)
          }
      }
    }
    true
  }

}
