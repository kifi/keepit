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
      airbrake.notify(ex)
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
            // todo(andrew): Don't let unverified people log in. For now, we are, but come up with something better.
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
      case Some(user) =>
        log.info(s"User found: $user for $id")
        user.credentials map { UserIdentity(user.userId, _) }
    }
  }

  private def updateState(socialUserInfo: SocialUserInfo): SocialUserInfo = {
    if (socialUserInfo.state == SocialUserInfoStates.APP_NOT_AUTHORIZED || socialUserInfo.state == SocialUserInfoStates.INACTIVE) {
      //assuming that at this point the user re-authenticated so we can alter the satate
      log.info(s"updating $socialGraphPlugin state to CREATED")
      db.readWrite { implicit session =>
        socialUserInfoRepo.save(socialUserInfo.copy(state = SocialUserInfoStates.CREATED))
      }
    } else socialUserInfo
  }

  def save(identity: Identity): SocialUser = reportExceptions {
    val (userId, socialUser, allowSignup) = getUserIdAndSocialUser(identity)
    log.info(s"[save] persisting (social|42) user $socialUser")
    val socialId = SocialId(socialUser.identityId.userId)
    if (socialId.id.trim.isEmpty) {
      airbrake.notify(s"empty social id for social user $socialUser userId $userId and identity $identity")
    }
    val toUpdate = internUser(
      socialId,
      SocialNetworkType(socialUser.identityId.providerId),
      socialUser,
      userId, allowSignup)
    val socialUserInfo = updateState(toUpdate)
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

  private def getOrCreateUser(existingUserOpt: Option[User], allowSignup: Boolean, socialUser: SocialUser): Option[User] = existingUserOpt orElse {
    if (allowSignup) {
      val userOpt: Option[User] = Some(createUser(socialUser))
      userOpt
    } else None
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

    log.info(s"[internUser] socialId=$socialId snType=$socialNetworkType socialUser=$socialUser userId=$userId")

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
          val isInactive = (existingUser.state == UserStates.INACTIVE)
          if (isInactive) { log.warn(s"[internUser] User $existingUserId is inactive!") }
          isInactive
        }
      }
      log.info(s"social user $suiOpt for $socialId, $socialNetworkType leads to existing user $existingUserOpt")
      (suiOpt, existingUserOpt)
    }

    val sui: SocialUserInfo = suiOpt.map(_.withCredentials(socialUser)) match {

      case Some(socialUserInfo) if socialUserInfo.userId.isDefined =>
        val sui = db.readWrite(attempts = 3) { implicit session =>
          // TODO(greg): handle case where user id in socialUserInfo is different from the one in the session
          val sui = if (suiOpt == Some(socialUserInfo)) socialUserInfo else {
            val user = userRepo.get(socialUserInfo.userId.get)
            if (user.state == UserStates.INCOMPLETE_SIGNUP) {
              userRepo.save(user.withName(socialUser.firstName, socialUser.lastName).withState(UserStates.ACTIVE))
            }
            socialUserInfoRepo.save(socialUserInfo)
          }
          sui

        }
        try {
          db.readWrite(attempts = 3) { implicit session =>
            saveVerifiedEmail(sui.userId.get, sui.credentials.get)
          }
        } catch {
          case e: Exception => airbrake.notify(s"on saving email of $sui", e)
        }
        sui

      case Some(socialUserInfo) if socialUserInfo.userId.isEmpty =>
        val userOpt = getOrCreateUser(existingUserOpt, allowSignup, socialUser)
        log.info(s"existing user $existingUserOpt with allowSignup $allowSignup of socialUser $socialUser leads to userOpt $userOpt")

        //social user info with user must be FETCHED_USING_SELF, so setting user should trigger a pull
        //todo(eishay): send a direct fetch request
        userOpt.foreach { user =>
          val socialUsers = db.readOnlyMaster { implicit session =>
            socialUserInfoRepo.getByUser(user.id.get)
          }
          socialUsers foreach { su =>
            if (su.networkType == socialUserInfo.networkType && su.id.get != socialUserInfo.id.get) {
              throw new IllegalStateException(
                s"Can't connect $socialUserInfo to user ${user.id.get}. " +
                  s"Social user for network ${su.networkType} is already connected to user ${user.id.get}: $su. " +
                  s"overall found ${socialUsers.map(_.id)} connected to this user")
            }
          }
        }

        val sui = db.readWrite(attempts = 3) { implicit session =>
          socialUserInfoRepo.save(userOpt map socialUserInfo.withUser getOrElse socialUserInfo)
        }
        for (user <- userOpt) {
          if (socialUserInfo.networkType != SocialNetworks.FORTYTWO) {
            try {
              imageStore.uploadPictureFromSocialNetwork(sui, user.externalId, setDefault = false)
            } catch {
              case e: Exception => airbrake.notify(s"on user $user", e, user)
            }
          }
          try {
            db.readWrite(attempts = 3) { implicit session =>
              saveVerifiedEmail(sui.userId.get, sui.credentials.get)
            }
          } catch {
            case e: Exception => airbrake.notify(s"on saving mail of $sui", e, user)
          }
        }
        sui

      case None =>
        val userOpt = getOrCreateUser(existingUserOpt, allowSignup, socialUser)
        log.info(s"creating new SocialUserInfo for $userOpt, $socialUser")

        val userInfo = SocialUserInfo(userId = userOpt.flatMap(_.id), //verify saved
          socialId = socialId, networkType = socialNetworkType, pictureUrl = socialUser.avatarUrl,
          fullName = socialUser.fullName, credentials = Some(socialUser))
        log.info(s"SocialUserInfo created is $userInfo")
        val sui = db.readWrite(attempts = 3) { implicit session =>
          socialUserInfoRepo.save(userInfo)
        }
        try {
          db.readWrite(attempts = 3) { implicit session =>
            for (user <- userOpt) {
              if (socialUser.authMethod == AuthenticationMethod.UserPassword) {
                val email = EmailAddress(socialUser.email.getOrElse(throw new IllegalStateException("user has no email")))
                val emailAddress = emailRepo.getByAddressOpt(address = email) getOrElse {
                  emailRepo.save(UserEmailAddress(userId = user.id.get, address = email))
                }
                val cred =
                  UserCred(
                    userId = user.id.get,
                    loginName = email.address,
                    provider = "bcrypt" /* hard-coded */ ,
                    credentials = socialUser.passwordInfo.get.password,
                    salt = socialUser.passwordInfo.get.salt.getOrElse(""))
                log.info(s"[save(userpass)] Saved email is $emailAddress")
                val savedCred = userCredRepo.save(cred)
                log.info(s"[save(userpass)] Persisted $cred into userCredRepo as $savedCred")
              } else {
                imageStore.uploadPictureFromSocialNetwork(sui, user.externalId, setDefault = false)
              }
            }
          }
        } catch {
          case e: Exception => airbrake.notify(s"on user $sui", e)
        }
        sui
    }
    sui
  }

  def findByEmailAndProvider(email: String, providerId: String): Option[SocialUser] = reportExceptions {
    db.readOnlyMaster { implicit s =>
      providerId match {
        case UsernamePasswordProvider.UsernamePassword =>
          val cred = userCredRepo.findByEmailOpt(email)
          log.info(s"[findByEmail] $email provider=$providerId cred=$cred")
          cred match {
            case Some(c: UserCred) => {
              val user = userRepo.get(c.userId)
              val res = Some(SocialUser(IdentityId(email, providerId), user.firstName, user.lastName, user.firstName + " " + user.lastName, Some(email), None, AuthenticationMethod.UserPassword, None, None, Some(PasswordInfo(c.provider, c.credentials, Some(c.salt)))))
              log.info(s"[findByEmail] user=$user socialUser=$res")
              res
            }
            case None => {
              log.info(s"[findByEmail] email=$email not found")
              None
            }
          }
        case _ => None
      }
    }
  }

  val tokenMap = collection.mutable.Map.empty[String, Token] // REMOVEME

  def save(token: Token) {
    log.info(s"[save] token=(${token.email}, ${token.uuid}, ${token.isSignUp}, ${token.isExpired}")
    tokenMap += (token.uuid -> token)
  }

  def findToken(token: String): Option[Token] = {
    val res = tokenMap.get(token)
    log.info(s"[findToken] token=$token res=$res")
    res
  }
  def deleteToken(uuid: String) {}
  def deleteExpiredTokens() {}
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
