package com.keepit.social

import com.keepit.common.performance._

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ State, ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.store.S3ImageStore
import com.keepit.inject.AppScoped
import com.keepit.model._
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.common.core._
import com.keepit.model.view.UserSessionView

import play.api.Play.current
import play.api.{ Application, Play }
import securesocial.core._
import securesocial.core.providers.UsernamePasswordProvider
import securesocial.core.IdentityId
import com.keepit.model.UserEmailAddress
import securesocial.core.providers.Token
import scala.Some
import securesocial.core.PasswordInfo
import com.keepit.model.UserExperiment
import com.keepit.model.UserCred
import com.keepit.commanders.{ UserCommander, LocalUserExperimentCommander }
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.mail.EmailAddress

import scala.util.Try

@Singleton
class SecureSocialUserPluginImpl @Inject() (
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  userRepo: UserRepo,
  userCredRepo: UserCredRepo,
  imageStore: S3ImageStore,
  airbrake: AirbrakeNotifier,
  emailRepo: UserEmailAddressRepo,
  socialGraphPlugin: SocialGraphPlugin,
  userCommander: UserCommander,
  userExperimentCommander: LocalUserExperimentCommander,
  clock: Clock)
    extends UserService with SecureSocialUserPlugin with Logging {

  private def reportExceptions[T](f: => T): T = try f catch {
    case ex: Throwable =>
      airbrake.notify("[SecureSocialUserPluginImpl]" + ex)
      throw ex
  }

  def find(id: IdentityId): Option[UserIdentity] = reportExceptions {
    db.readOnlyMaster { implicit s =>
      socialUserInfoRepo.getOpt(SocialId(id.userId), SocialNetworkType(id.providerId))
    } match {
      case None if id.providerId == SocialNetworks.FORTYTWO.authProvider =>
        // Email social accounts are only tied to one email address
        // Since we support multiple email addresses, if we do not
        // find a SUI with the correct email address, we go searching.
        val email = EmailAddress(id.userId)
        db.readOnlyMaster { implicit session =>
          emailRepo.getByAddressOpt(email).flatMap { emailAddr =>
            socialUserInfoRepo.getByUser(emailAddr.userId).find(_.networkType == SocialNetworks.FORTYTWO).flatMap { sui =>
              sui.credentials map { creds =>
                UserIdentity(Some(emailAddr.userId), creds)
              }
            }
          }
        } tap { res =>
          log.info(s"No immediate SocialUserInfo found for $id, found $res")
        }
      case None =>
        log.info(s"No SocialUserInfo found for $id")
        None
      case Some(sui) =>
        log.info(s"User found: $sui for $id")
        sui.credentials map { UserIdentity(sui.userId, _) }
    }
  }

  def save(identity: Identity): SocialUser = reportExceptions {
    val (userId, socialUser, allowSignup) = getUserIdAndSocialUser(identity)
    log.info(s"[save] persisting (social|42) user $socialUser")
    val socialUserInfo = internUser(
      SocialId(socialUser.identityId.userId),
      SocialNetworkType(socialUser.identityId.providerId),
      socialUser,
      userId, allowSignup
    )
    require(socialUserInfo.credentials.isDefined, s"social user info's credentials is not defined: $socialUserInfo")
    if (!socialUser.identityId.providerId.equals("userpass"))
      socialGraphPlugin.asyncFetch(socialUserInfo)
    log.info(s"[save] persisting $socialUser into $socialUserInfo")
    socialUserInfo.userId.foreach(updateExperimentIfTestUser)
    socialUser
  }

  private def updateExperimentIfTestUser(userId: Id[User]): Unit = try {
    timing(s"updateExperimentIfTestUser $userId") {
      @inline def setExp(exp: ExperimentType) {
        val marked = userExperimentCommander.userHasExperiment(userId, exp)
        if (marked)
          log.debug(s"test user $userId is already marked as $exp")
        else {
          log.debug(s"setting test user $userId as $exp")
          userExperimentCommander.addExperimentForUser(userId, exp)
        }
      }
      val emailAddresses = db.readOnlyMaster(attempts = 3) { implicit rw => emailRepo.getAllByUser(userId) }
      val experiments = emailAddresses.flatMap(UserEmailAddress.getExperiments)
      experiments.foreach(setExp)
    }
  } catch {
    case e: Exception => airbrake.notify(s"error updating experiment if test user for user $userId")
  }

  private def getUserIdAndSocialUser(identity: Identity): (Option[Id[User]], SocialUser, Boolean) = {
    identity match {
      case UserIdentity(userId, socialUser, allowSignup) => (userId, socialUser, allowSignup)
      case ident =>
        airbrake.notify(s"using an identity $ident should not be possible at this point!")
        (None, SocialUser(ident), true)
    }
  }

  private def createUser(identity: Identity): User = timing(s"create user ${identity.identityId}") {
    val u = userCommander.createUser(
      identity.firstName,
      identity.lastName,
      identity.email.map(EmailAddress.apply),
      state = UserStates.ACTIVE
    )
    log.info(s"[createUser] new user: name=${u.firstName + " " + u.lastName} state=${u.state}")
    u
  }

  private def saveVerifiedEmail(userId: Id[User], socialUser: SocialUser)(implicit session: RWSession): Unit = timing(s"saveVerifiedEmail $userId") {
    for (emailString <- socialUser.email if socialUser.authMethod != AuthenticationMethod.UserPassword) {
      val email = EmailAddress.validate(emailString).get
      val emailAddress = emailRepo.getByAddressOpt(address = email) match {
        case Some(e) if e.state == UserEmailAddressStates.VERIFIED && e.verifiedAt.isEmpty =>
          emailRepo.save(e.copy(verifiedAt = Some(clock.now))) // we didn't originally set this
        case Some(e) if e.state == UserEmailAddressStates.VERIFIED => e
        case Some(e) => emailRepo.save(e.withState(UserEmailAddressStates.VERIFIED).copy(verifiedAt = Some(clock.now)))
        case None => emailRepo.save(
          UserEmailAddress(userId = userId, address = email, state = UserEmailAddressStates.VERIFIED, verifiedAt = Some(clock.now)))
      }
      log.info(s"[save] Saved email is $emailAddress")
      emailAddress
    }
  }

  private def internUser(
    socialId: SocialId, socialNetworkType: SocialNetworkType, socialUser: SocialUser,
    userId: Option[Id[User]], allowSignup: Boolean): SocialUserInfo = timing(s"intern user $socialId") {

    /*
      This is responsible for SocialUserInfo and User creation.
      socialId is the external ID from socialNetworkType.
      socialUser is data we get from the social network, including oauth credentials.
      userId is possibly the existing Kifi user — I'm not sure when this is Some and when it's None, and if it's accurate.
      allowSignup is a weird hack that marks if the user should be created.
     */

    log.debug(s"[internUser] socialId=$socialId snType=$socialNetworkType socialUser=$socialUser userId=$userId")

    // Get existing SocialUserInfo and User records, if they exist
    val (suiOpt, existingUserOpt) = db.readOnlyMaster { implicit session =>
      val suiOpt = socialUserInfoRepo.getOpt(socialId, socialNetworkType)
      val existingUserOpt = userId orElse {
        // Automatically connect accounts with existing emails
        socialUser.email.map(EmailAddress(_)).flatMap { emailAddress =>
          emailRepo.getVerifiedOwner(emailAddress) tap {
            _.foreach { existingUserId =>
              log.info(s"[internUser] Found existing user $existingUserId with email address $emailAddress.")
            }
          }
        }
      } flatMap { existingUserId =>
        scala.util.Try(userRepo.get(existingUserId)).toOption.filterNot { existingUser =>
          val isInactive = existingUser.state == UserStates.INACTIVE
          if (isInactive) { log.warn(s"[internUser] User $existingUserId is inactive!") }
          isInactive
        }
      }

      (suiOpt, existingUserOpt)
    }

    suiOpt.map(_.withCredentials(socialUser)) match {
      // This social user info is already associated with an existing Kifi user
      case Some(socialUserInfo) if socialUserInfo.userId.isDefined =>
        db.readWrite(attempts = 3) { implicit session =>
          // Weird. Attempt to save user's email as verified if it's a social account.
          Try(saveVerifiedEmail(socialUserInfo.userId.get, socialUserInfo.credentials.get))

          if (!socialUserInfo.credentials.exists(_ == socialUser)) { // The credentials have changed
            val updatedSui = socialUserInfoRepo.save(socialUserInfo.copy(state = SocialUserInfoStates.CREATED)) // save updated sui

            // This is weird and should be removed when the functionality is verified elsewhere.
            // This is so that users who were incomplete (email signup, half way) and then complete signup
            // get marked as active.
            val user = userRepo.get(socialUserInfo.userId.get)
            if (user.state == UserStates.INCOMPLETE_SIGNUP) {
              userRepo.save(user.withName(socialUser.firstName, socialUser.lastName).withState(UserStates.ACTIVE))
            }
            updatedSui
          } else socialUserInfo
        }

      case Some(socialUserInfo) if socialUserInfo.userId.isEmpty =>
        // socialUserInfo exists, but has no user associated
        val userOpt = if (allowSignup) {
          Some(existingUserOpt getOrElse createUser(socialUser))
        } else None

        // Before we simply connect the user to this socialUserInfo, we want to make sure
        // that the user doesn't have this network already.
        userOpt.foreach { user =>
          db.readOnlyMaster { implicit session =>
            socialUserInfoRepo.getByUser(user.id.get).find(su => su.networkType == socialUserInfo.networkType && su.id.get != socialUserInfo.id.get)
          }.foreach { existingSocialAccountTiedToUser =>
            // This is most certainly not a good idea, but...
            throw new IllegalStateException(
              s"Can't connect $socialUserInfo to user ${user.id.get}. " +
                s"Social user for network ${existingSocialAccountTiedToUser.networkType} is already connected to user ${user.id.get}: $existingSocialAccountTiedToUser")
          }
        }

        val suiWithUserOpt = for (user <- userOpt) yield {
          db.readWrite(attempts = 3) { implicit session =>
            val updatedSui = socialUserInfoRepo.save(socialUserInfo.copy(userId = Some(user.id.get), state = SocialUserInfoStates.CREATED))

            if (socialUserInfo.networkType != SocialNetworks.FORTYTWO) {
              // Not sure why we don't trust these calls, but...
              Try(imageStore.uploadPictureFromSocialNetwork(updatedSui, user.externalId, setDefault = false))
              Try(saveVerifiedEmail(updatedSui.userId.get, updatedSui.credentials.get))
            }

            updatedSui
          }
        }

        suiWithUserOpt getOrElse socialUserInfo

      case None =>
        // We've never seen this socialUserInfo before. Create it, possibly the user, etc
        val userOpt = if (allowSignup) {
          Some(existingUserOpt getOrElse createUser(socialUser))
        } else None
        log.info(s"creating new SocialUserInfo for $userOpt, $socialUser")

        val socialUserInfo = SocialUserInfo(userId = userOpt.flatMap(_.id), //verify saved
          socialId = socialId, networkType = socialNetworkType, pictureUrl = socialUser.avatarUrl,
          fullName = socialUser.fullName, credentials = Some(socialUser), state = SocialUserInfoStates.CREATED)
        log.info(s"SocialUserInfo created is $socialUserInfo")
        db.readWrite(attempts = 3) { implicit session =>
          // We fairly often hit race conditions here. So we'll re-look up any possibly existing SUI
          val newSui = socialUserInfoRepo.getOpt(socialId, socialNetworkType).map { existingSui =>
            airbrake.notify(s"[internUser] Wanted to create new SUI, but found an existing. $socialNetworkType/$socialId, $socialUserInfo vs existing $existingSui")
            existingSui
          }.getOrElse {
            socialUserInfoRepo.save(socialUserInfo)
          }

          for (user <- userOpt if allowSignup) {
            // If the user exists, we just created it.
            if (socialUser.authMethod == AuthenticationMethod.UserPassword) {
              val email = EmailAddress(socialUser.email.getOrElse(throw new IllegalStateException("user has no email")))

              // Why would there ever be one here? If there is, it's almost certainly not what we want.
              emailRepo.getByAddressOpt(address = email) getOrElse {
                emailRepo.save(UserEmailAddress(userId = user.id.get, address = email))
              }

              val cred = UserCred(
                userId = user.id.get,
                loginName = email.address,
                provider = "bcrypt",
                credentials = socialUser.passwordInfo.get.password,
                salt = socialUser.passwordInfo.get.salt.getOrElse("")
              )
              userCredRepo.save(cred)
            } else {
              imageStore.uploadPictureFromSocialNetwork(newSui, user.externalId, setDefault = false)
            }
          }
          newSui
        }
    }
  }

  // Not used
  def findByEmailAndProvider(email: String, providerId: String): Option[SocialUser] = None
  def save(token: Token): Unit = {}
  def findToken(token: String): Option[Token] = None
  def deleteToken(uuid: String): Unit = {}
  def deleteExpiredTokens(): Unit = {}
}

@AppScoped
class SecureSocialAuthenticatorPluginImpl @Inject() (
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  sessionRepo: UserSessionRepo,
  airbrake: AirbrakeNotifier,
  app: Application)
    extends AuthenticatorStore(app) with SecureSocialAuthenticatorPlugin with Logging {

  private def reportExceptionsAndTime[T](tag: String)(f: => T): Either[Error, T] = timing(tag) {
    try Right(f) catch {
      case ex: Throwable =>
        airbrake.notify(ex)
        log.error("error while using secure social plugin", ex)
        Left(new Error(ex))
    }
  }

  private def sessionFromAuthenticator(authenticator: Authenticator): UserSession = timing(s"sessionFromAuthenticator ${authenticator.identityId.userId}") {
    val snType = SocialNetworkType(authenticator.identityId.providerId) // userpass -> fortytwo
    val (socialId, provider) = (SocialId(authenticator.identityId.userId), snType)
    log.debug(s"[sessionFromAuthenticator] auth=$authenticator socialId=$socialId, provider=$provider")
    val userId = db.readOnlyMaster {
      implicit s => socialUserInfoRepo.get(socialId, provider).userId // another dependency on socialUserInfo
    }
    UserSession(
      userId = userId,
      externalId = ExternalId[UserSession](authenticator.id),
      socialId = socialId,
      provider = provider,
      expires = authenticator.expirationDate,
      state = if (authenticator.isValid) UserSessionStates.ACTIVE else UserSessionStates.INACTIVE
    )
  }

  private def authenticatorFromSession(session: UserSessionView, externalId: ExternalId[UserSession]): Authenticator = Authenticator(
    id = externalId.id,
    identityId = IdentityId(session.socialId.id, session.provider.name),
    creationDate = session.createdAt,
    lastUsed = session.updatedAt,
    expirationDate = session.expires
  )

  def save(authenticator: Authenticator): Either[Error, Unit] = reportExceptionsAndTime(s"save authenticator ${authenticator.identityId.userId}") {
    val sessionFromCookie = sessionFromAuthenticator(authenticator)
    val (session, externalId) = internSession(sessionFromCookie)
    authenticatorFromSession(session, externalId)
  }

  private def internSession(newSession: UserSession) = {
    loadSession(newSession) getOrElse persistSession(newSession)
  }

  private def loadSession(newSession: UserSession) = timing(s"loadSession ${newSession.socialId}") {
    db.readOnlyMaster { implicit s => //from cache
      sessionRepo.getViewOpt(newSession.externalId) map (_ -> newSession.externalId)
    }
  }

  private def persistSession(newSession: UserSession) = timing(s"persistSession ${newSession.socialId}") {
    db.readWrite(attempts = 3) { implicit s =>
      val sessionFromCookie = sessionRepo.save(newSession)
      log.debug(s"[save] newSession=$sessionFromCookie")
      sessionFromCookie.toUserSessionView -> sessionFromCookie.externalId
    }
  }

  def find(id: String): Either[Error, Option[Authenticator]] = reportExceptionsAndTime(s"find authenticator $id") {
    val externalIdOpt = try {
      Some(ExternalId[UserSession](id))
    } catch {
      case ex: Throwable =>
        /**
         * We sometime get an empty string instead of an id.
         * Not sure how we got to it, probably an empty cookie or expired session.
         * For now it seems to be harmless since we create a new session from scratch.
         * Not worth investigating more since we want to kill secure social soon.
         */
        // ↑ Behold, a comment from 2014/1/9 ↑
        log.warn(s"error parsing external id[$id]", ex)
        None
    }

    val res = externalIdOpt flatMap { externalId =>
      db.readOnlyMaster { implicit s =>
        val sess = sessionRepo.getViewOpt(externalId)
        log.debug(s"[find] sessionRepo.get($externalId)=$sess")
        sess
      } collect {
        case s if s.valid =>
          authenticatorFromSession(s, externalId)
      }
    }
    log.debug(s"[find] id=$id res=$res")
    res
  }

  def delete(id: String): Either[Error, Unit] = reportExceptionsAndTime(s"delete $id") {
    db.readWrite(attempts = 3) { implicit s =>
      sessionRepo.getOpt(ExternalId[UserSession](id)).foreach { session =>
        sessionRepo.save(session.invalidated)
      }
    }
  }
}
