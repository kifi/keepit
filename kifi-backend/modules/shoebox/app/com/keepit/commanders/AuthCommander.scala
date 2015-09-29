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
import com.keepit.common.oauth.{ ProviderIds, OAuth2ProviderRegistry, OAuth2Configuration }
import com.keepit.common.performance.timing
import com.keepit.common.store.{ ImageCropAttributes, S3ImageStore }
import com.keepit.common.time.Clock
import com.keepit.common.logging.Logging
import com.keepit.controllers.core.{ AuthHelper }
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
    oauth2Config: OAuth2Configuration,
    oauth2ProviderRegistry: OAuth2ProviderRegistry,
    userRepo: UserRepo,
    userCredRepo: UserCredRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    emailAddressRepo: UserEmailAddressRepo,
    userValueRepo: UserValueRepo,
    passwordResetRepo: PasswordResetRepo,
    s3ImageStore: S3ImageStore,
    postOffice: LocalPostOffice,
    inviteCommander: InviteCommander,
    libraryMembershipCommander: LibraryMembershipCommander,
    orgMembershipCommander: OrganizationMembershipCommander,
    orgInviteCommander: OrganizationInviteCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext,
    userExperimentCommander: LocalUserExperimentCommander,
    userCommander: UserCommander,
    handleCommander: HandleCommander,
    heimdalServiceClient: HeimdalServiceClient,
    amazonSimpleMailProvider: AmazonSimpleMailProvider) extends Logging {

  def emailAddressMatchesSomeKifiUser(addr: EmailAddress): Boolean = {
    db.readOnlyMaster { implicit s =>
      emailAddressRepo.getByAddress(addr).isDefined
    }
  }

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
      socialUser = SocialUser(
        identityId = IdentityId(email.address, SocialNetworks.FORTYTWO.authProvider),
        firstName = fName,
        lastName = lName,
        fullName = s"$fName $lName",
        email = Some(email.address),
        avatarUrl = GravatarHelper.avatarFor(email.address),
        authMethod = AuthenticationMethod.UserPassword,
        passwordInfo = Some(passwordInfo)
      )
    )

    val savedIdentity = UserService.save(newIdentity) // Kifi User is created here if it doesn't exist

    savedIdentity match {
      case userIdentity @ UserIdentity(Some(userId), _) => {
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

  def finalizeSocialAccount(sfi: SocialFinalizeInfo, socialIdentity: Identity, inviteExtIdOpt: Option[ExternalId[Invitation]])(implicit existingContext: HeimdalContext) =
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

      UserService.save(UserIdentity(Some(userId), SocialUser(socialIdentity))) // SocialUserInfo is claimed here

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
          from = SystemEmailAddress.ENG,
          to = Seq(SystemEmailAddress.SALES),
          subject = s"New user from company: $trimmed",
          htmlBody = informationalMessage,
          category = NotificationCategory.toElectronicMailCategory(NotificationCategory.System.LEADS)
        ))

      }
    }
  }

  def finalizeEmailPassAccount(efi: EmailPassFinalizeInfo, userId: Id[User], externalUserId: ExternalId[User], identityOpt: Option[Identity], inviteExtIdOpt: Option[ExternalId[Invitation]])(implicit context: HeimdalContext): Future[(User, EmailAddress, Identity)] = {
    log.info(s"[finalizeEmailPassAccount] efi=$efi, userId=$userId, extUserId=$externalUserId, identity=$identityOpt, inviteExtId=$inviteExtIdOpt")

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

  def fillUserProfile(provider: IdentityProvider, oauth2Info: OAuth2Info): Try[SocialUser] =
    Try {
      provider.fillProfile(SocialUser(IdentityId("", provider.id), "", "", "", None, None, provider.authMethod, oAuth2Info = Some(oauth2Info)))
    }

  def fillUserProfile(provider: IdentityProvider, oauth1Info: OAuth1Info): Try[SocialUser] =
    Try {
      provider.fillProfile(SocialUser(IdentityId("", provider.id), "", "", "", None, None, provider.authMethod, oAuth1Info = Some(oauth1Info)))
    }

  def getSocialUserOpt(identityId: IdentityId): Option[Identity] = UserService.find(identityId)

  def exchangeLongTermToken(provider: IdentityProvider, oauth2Info: OAuth2Info): Future[OAuth2Info] = {
    oauth2ProviderRegistry.get(ProviderIds.toProviderId(provider.id)) match {
      case None =>
        log.warn(s"[exchangeLongTermToken(${provider.id})] provider not found")
        Future.successful(oauth2Info)
      case Some(oauthProvider) =>
        oauthProvider.exchangeLongTermToken(oauth2Info).map { tokenInfo =>
          log.info(s"[exchangeLongTermToken(${provider.id}) orig=${oauth2Info.accessToken.take(5)}... new=${tokenInfo.accessToken} isIdentical=${tokenInfo.accessToken.token.equals(oauth2Info.accessToken)}")
          OAuth2TokenInfo.toOAuth2Info(tokenInfo)
        }
    }
  }

  def signupWithTrustedSocialUser(providerName: String, socialUser: SocialUser, signUpUrl: String)(implicit request: Request[_]): Result = {
    getSocialUserOpt(socialUser.identityId) match { // *note* checks for social users with credentials
      case None =>
        db.readWrite { implicit rw =>
          // userId must not be set in this case
          val socialId = SocialId(socialUser.identityId.userId)
          val networkType = SocialNetworkType(socialUser.identityId.providerId)

          socialUserInfoRepo.getOpt(socialId, networkType) match { // double check if social user already exists in DB
            case None =>
              log.info(s"[signupWithTrustedSocialUser] NO social user found in DB for ($socialId, $networkType)")
              socialUserInfoRepo.save(SocialUserInfo(
                fullName = socialUser.fullName,
                pictureUrl = socialUser.avatarUrl,
                state = SocialUserInfoStates.FETCHED_USING_SELF,
                socialId = socialId,
                networkType = networkType,
                credentials = Some(socialUser)
              ))
            case Some(sui) => // update all fields to latest field anyway
              log.info(s"[signupWithTrustedSocialUser] social user found in DB for ($socialId, $networkType), $sui")
              socialUserInfoRepo.save(sui.copy(
                fullName = socialUser.fullName,
                pictureUrl = socialUser.avatarUrl,
                state = SocialUserInfoStates.FETCHED_USING_SELF,
                socialId = socialId,
                networkType = networkType,
                credentials = Some(socialUser)
              ))
          }

        } tap { sui => log.info(s"[doAccessTokenSignup] created socialUserInfo(${sui.id}) $socialUser") }
        val payload = if (socialUser.email.exists(e => emailAddressMatchesSomeKifiUser(EmailAddress(e))))
          Json.obj("code" -> "connect_option", "uri" -> signUpUrl)
        else
          Json.obj("code" -> "continue_signup")
        log.info(s"[accessTokenSignup($providerName)] created social user(${socialUser.identityId}) email=${socialUser.email}; payload=$payload")
        Authenticator.create(socialUser).fold(
          error => throw error,
          authenticator =>
            Ok(payload ++ Json.obj("sessionId" -> authenticator.id))
              .withSession(request.session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
              .withCookies(authenticator.toCookie)
        )
      case Some(identity) => // social user exists
        db.readOnlyMaster(attempts = 2) { implicit s =>
          socialUserInfoRepo.getOpt(SocialId(identity.identityId.userId), SocialNetworkType(identity.identityId.providerId)) flatMap (_.userId)
        } match {
          case None => // kifi user does not exist
            val payload = if (socialUser.email.exists(e => emailAddressMatchesSomeKifiUser(EmailAddress(e))))
              Json.obj("code" -> "connect_option", "uri" -> signUpUrl)
            else
              Json.obj("code" -> "continue_signup")
            log.info(s"[accessTokenSignup($providerName)] no kifi user associated with ${socialUser.identityId} email=${socialUser.email}; payload=$payload")
            Authenticator.create(identity).fold(
              error => throw error,
              authenticator =>
                Ok(payload ++ Json.obj("sessionId" -> authenticator.id))
                  .withSession(request.session - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
                  .withCookies(authenticator.toCookie)
            )
          case Some(userId) =>
            val newSession = Events.fire(new LoginEvent(identity)).getOrElse(request.session)
            Authenticator.create(identity).fold(
              error => throw error,
              authenticator =>
                Ok(Json.obj("code" -> "user_logged_in", "sessionId" -> authenticator.id))
                  .withSession(newSession - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
                  .withCookies(authenticator.toCookie)
            )
        }
    }
  }

  def loginWithTrustedSocialIdentity(identityId: IdentityId)(implicit request: RequestHeader): Result = {
    log.info(s"[loginWithTrustedSocialIdentity(${identityId})]")
    // more fine-grained error handling required
    val resOpt = for {
      identity <- UserService.find(identityId)
      sui <- db.readOnlyMaster { implicit s => socialUserInfoRepo.getOpt(SocialId(identity.identityId.userId), SocialNetworkType(identity.identityId.providerId)) }
      userId <- sui.userId
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

  def autoJoinLib(userId: Id[User], libPubId: PublicId[Library], authToken: Option[String]): Boolean = { // true for success, false for failure
    // Abstracting away errors and manually reporting. If someone needs the specific error, feel free to change the signature.
    Library.decodePublicId(libPubId).map { libId =>
      implicit val context = HeimdalContext(Map())
      libraryMembershipCommander.joinLibrary(userId, libId, authToken).fold(
        { libFail =>
          airbrake.notify(s"[finishSignup] lib-auto-join failed. $libFail")
          false
        },
        { library =>
          log.info(s"[finishSignup] user(id=$userId) has successfully joined library $library")
          true
        }
      )
    }.getOrElse(false)
  }

  def autoJoinOrg(userId: Id[User], orgPubId: PublicId[Organization], authToken: String): Boolean = {
    Organization.decodePublicId(orgPubId).map { orgId =>
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
    }.getOrElse(false)
  }

}
