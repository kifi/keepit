package com.keepit.social

import com.google.inject.{Inject, Singleton}
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{State, ExternalId, Id}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.store.S3ImageStore
import com.keepit.inject.AppScoped
import com.keepit.model._
import com.keepit.common.time.{Clock, DEFAULT_DATE_TIME_ZONE}
import com.keepit.common.KestrelCombinator


import play.api.Play.current
import play.api.{Application, Play}
import securesocial.core._
import securesocial.core.providers.UsernamePasswordProvider
import securesocial.core.IdentityId
import com.keepit.model.EmailAddress
import securesocial.core.providers.Token
import scala.Some
import securesocial.core.PasswordInfo
import com.keepit.model.UserExperiment
import com.keepit.model.UserCred
import com.keepit.commanders.UserCommander

@Singleton
class SecureSocialUserPluginImpl @Inject() (
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  userRepo: UserRepo,
  userCredRepo: UserCredRepo,
  imageStore: S3ImageStore,
  airbrake: AirbrakeNotifier,
  emailRepo: EmailAddressRepo,
  socialGraphPlugin: SocialGraphPlugin,
  userCommander: UserCommander,
  userExperimentRepo: UserExperimentRepo,
  clock: Clock)
  extends UserService with SecureSocialUserPlugin with Logging {

  private def reportExceptions[T](f: => T): T =
    try f catch { case ex: Throwable =>
      airbrake.notify(ex)
      throw ex
    }

  def find(id: IdentityId): Option[SocialUser] = reportExceptions {
    db.readOnly { implicit s =>
      socialUserInfoRepo.getOpt(SocialId(id.userId), SocialNetworkType(id.providerId))
    } match {
      case None if id.providerId == SocialNetworks.FORTYTWO.authProvider =>
        // Email social accounts are only tied to one email address
        // Since we support multiple email addresses, if we do not
        // find a SUI with the correct email address, we go searching.
        db.readOnly { implicit session =>
          emailRepo.getByAddressOpt(id.userId).flatMap { emailAddr =>
            // todo(andrew): Don't let unverified people log in. For now, we are, but come up with something better.
            socialUserInfoRepo.getByUser(emailAddr.userId).find(_.networkType == SocialNetworks.FORTYTWO).flatMap { sui =>
              sui.credentials
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
        user.credentials
    }
  }

  def save(identity: Identity): SocialUser = reportExceptions {
    db.readWrite { implicit s =>
      val (userId, socialUser, allowSignup, isComplete) = getUserIdAndSocialUser(identity)
      log.info(s"[save] persisting (social|42) user $socialUser")
      val socialUserInfo = internUser(
        SocialId(socialUser.identityId.userId),
        SocialNetworkType(socialUser.identityId.providerId),
        socialUser,
        userId,
        allowSignup,
        isComplete)
      require(socialUserInfo.credentials.isDefined, s"social user info's credentials is not defined: $socialUserInfo")
      if (!socialUser.identityId.providerId.equals("userpass")) // FIXME
        socialGraphPlugin.asyncFetch(socialUserInfo)
      log.info(s"[save] persisting $socialUser into $socialUserInfo")
      socialUserInfo.userId.map { userId =>
        emailRepo.getAllByUser(userId) map { email =>
          updateExperimentIfTestUser(userId, email)
        }
      }
      socialUser
    }
  }

  private def updateExperimentIfTestUser(userId: Id[User], email: EmailAddress)(implicit session: RWSession): Unit = if(email.isTestEmail()) {
    if(userExperimentRepo.hasExperiment(userId, ExperimentType.FAKE)) {
      log.info(s"user $userId is already marked as FAKE")
    } else {
      log.info(s"setting user $userId as FAKE")
      userExperimentRepo.save(UserExperiment(userId = userId, experimentType = ExperimentType.FAKE))
    }
  }

  private def getUserIdAndSocialUser(identity: Identity): (Option[Id[User]], SocialUser, Boolean, Boolean) = {
    identity match {
      case UserIdentity(userId, socialUser, allowSignup, isComplete) => (userId, socialUser, allowSignup, isComplete)
      case ident => (None, SocialUser(ident), false, true)
    }
  }

  private def newUserState: State[User] = if (Play.isDev) UserStates.ACTIVE else UserStates.PENDING

  private def createUser(identity: Identity, isComplete: Boolean)(implicit session: RWSession): User = {
    val u = userCommander.createUser(
      firstName = identity.firstName,
      lastName = identity.lastName,
      state = if (isComplete) newUserState else UserStates.INCOMPLETE_SIGNUP
    )
    log.info(s"[createUser] new user: name=${u.firstName + " " + u.lastName} state=${u.state}")
    u
  }

  private def getOrCreateUser(existingUserOpt: Option[User], allowSignup: Boolean, isComplete: Boolean, socialUser: SocialUser)(implicit session: RWSession): Option[User] = existingUserOpt orElse {
    if (allowSignup) {
      val userOpt: Option[User] = Some(createUser(socialUser, isComplete))
      userOpt
    } else None
  }

  private def saveVerifiedEmail(userId: Id[User], socialUser: SocialUser)(implicit session: RWSession): Unit = {
    for (email <- socialUser.email if socialUser.authMethod != AuthenticationMethod.UserPassword) {
      val emailAddress = emailRepo.getByAddressOpt(address = email) match {
        case Some(e) if e.state == EmailAddressStates.VERIFIED && e.verifiedAt.isEmpty =>
          emailRepo.save(e.copy(verifiedAt = Some(clock.now))) // we didn't originally set this
        case Some(e) if e.state == EmailAddressStates.VERIFIED => e
        case Some(e) => emailRepo.save(e.withState(EmailAddressStates.VERIFIED).copy(verifiedAt = Some(clock.now)))
        case None => emailRepo.save(
          EmailAddress(userId = userId, address = email, state = EmailAddressStates.VERIFIED, verifiedAt = Some(clock.now)))
      }
      log.info(s"[save] Saved email is $emailAddress")
      emailAddress
    }
  }

  private def internUser(
      socialId: SocialId, socialNetworkType: SocialNetworkType, socialUser: SocialUser,
      userId: Option[Id[User]], allowSignup: Boolean, isComplete: Boolean)
      (implicit session: RWSession): SocialUserInfo = {
    log.info(s"[internUser] socialId=$socialId snType=$socialNetworkType socialUser=$socialUser userId=$userId isComplete=$isComplete")

    val suiOpt = socialUserInfoRepo.getOpt(socialId, socialNetworkType)
    val existingUserOpt = userId orElse {
      // Automatically connect accounts with existing emails
      socialUser.email flatMap (emailRepo.getByAddressOpt(_)) collect {
        case e if e.state == EmailAddressStates.VERIFIED => e.userId
      }
    } flatMap userRepo.getOpt

    val sui: SocialUserInfo = suiOpt.map(_.withCredentials(socialUser)) match {
      case Some(socialUserInfo) if !socialUserInfo.userId.isEmpty =>
        // TODO(greg): handle case where user id in socialUserInfo is different from the one in the session
        val sui = if (suiOpt == Some(socialUserInfo)) {
          socialUserInfo
        } else {
          val user = userRepo.get(socialUserInfo.userId.get)
          if (user.state == UserStates.INCOMPLETE_SIGNUP && isComplete) {
            userRepo.save(user.withName(socialUser.firstName, socialUser.lastName).withState(newUserState))
          }
          socialUserInfoRepo.save(socialUserInfo)
        }
        saveVerifiedEmail(sui.userId.get, sui.credentials.get)
        sui
      case Some(socialUserInfo) if socialUserInfo.userId.isEmpty =>
        val userOpt = getOrCreateUser(existingUserOpt, allowSignup, isComplete, socialUser)

        //social user info with user must be FETCHED_USING_SELF, so setting user should trigger a pull
        //todo(eishay): send a direct fetch request

        for (user <- userOpt; su <- socialUserInfoRepo.getByUser(user.id.get)
            if su.networkType == socialUserInfo.networkType && su.id.get != socialUserInfo.id.get) {
          throw new IllegalStateException(
            s"Can't connect $socialUserInfo to user ${user.id.get}. " +
            s"Social user for network ${su.networkType} is already connected to user ${user.id.get}: $su")
        }

        val sui = socialUserInfoRepo.save(userOpt map socialUserInfo.withUser getOrElse socialUserInfo)
        for (user <- userOpt) {
          if (socialUserInfo.networkType != SocialNetworks.FORTYTWO) imageStore.uploadPictureFromSocialNetwork(sui, user.externalId)
          saveVerifiedEmail(sui.userId.get, sui.credentials.get)
        }
        sui
      case None =>
        val userOpt = getOrCreateUser(existingUserOpt, allowSignup, isComplete, socialUser)
        log.info("creating new SocialUserInfo for %s".format(userOpt))

        val userInfo = SocialUserInfo(userId = userOpt.flatMap(_.id),//verify saved
          socialId = socialId, networkType = socialNetworkType, pictureUrl = socialUser.avatarUrl,
          fullName = socialUser.fullName, credentials = Some(socialUser))
        log.info("SocialUserInfo created is %s".format(userInfo))
        val sui = socialUserInfoRepo.save(userInfo)

        for (user <- userOpt) {
          if (socialUser.authMethod == AuthenticationMethod.UserPassword) {
            val email = socialUser.email.getOrElse(throw new IllegalStateException("user has no email"))
            val cred =
              UserCred(
                userId = user.id.get,
                loginName = email,
                provider = "bcrypt" /* hard-coded */,
                credentials = socialUser.passwordInfo.get.password,
                salt = socialUser.passwordInfo.get.salt.getOrElse(""))
            val emailAddress = emailRepo.getByAddressOpt(address = email) getOrElse {
              emailRepo.save(EmailAddress(userId = user.id.get, address = email))
            }
            log.info(s"[save(userpass)] Saved email is $emailAddress")
            val savedCred = userCredRepo.save(cred)
            log.info(s"[save(userpass)] Persisted $cred into userCredRepo as $savedCred")
          } else {
            imageStore.uploadPictureFromSocialNetwork(sui, user.externalId)
          }
        }
        sui
    }
    sui
  }

  def findByEmailAndProvider(email: String, providerId: String): Option[SocialUser] = reportExceptions {
    db.readOnly { implicit s =>
      providerId match {
        case UsernamePasswordProvider.UsernamePassword =>
          val cred = userCredRepo.findByEmailOpt(email)
          log.info(s"[findByEmail] $email provider=$providerId cred=$cred")
          cred match {
            case Some(c:UserCred) => {
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
class SecureSocialAuthenticatorPluginImpl @Inject()(
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  sessionRepo: UserSessionRepo,
  airbrake: AirbrakeNotifier,
  app: Application)
  extends AuthenticatorStore(app) with SecureSocialAuthenticatorPlugin with Logging  {

  private def reportExceptions[T](f: => T): Either[Error, T] =
    try Right(f) catch { case ex: Throwable =>
      airbrake.notify(ex)
      log.error("error while using secure social plugin", ex)
      Left(new Error(ex))
    }

  private def sessionFromAuthenticator(authenticator: Authenticator): UserSession = {
    val snType = SocialNetworkType(authenticator.identityId.providerId) // userpass -> fortytwo
    val (socialId, provider) = (SocialId(authenticator.identityId.userId), snType)
    log.info(s"[sessionFromAuthenticator] auth=$authenticator socialId=$socialId, provider=$provider")
    val userId = db.readOnly { implicit s => socialUserInfoRepo.get(socialId, provider).userId }                           // another dependency on socialUserInfo
    UserSession(
      userId = userId,
      externalId = ExternalId[UserSession](authenticator.id),
      socialId = socialId,
      provider = provider,
      expires = authenticator.expirationDate,
      state = if (authenticator.isValid) UserSessionStates.ACTIVE else UserSessionStates.INACTIVE
    )
  }
  private def authenticatorFromSession(session: UserSession): Authenticator = Authenticator(
    id = session.externalId.id,
    identityId = IdentityId(session.socialId.id, session.provider.name),
    creationDate = session.createdAt,
    lastUsed = session.updatedAt,
    expirationDate = session.expires
  )

  private def needsUpdate(oldSession: UserSession, newSession: UserSession): Boolean = {
    // We only want to save if we actually changed something. SecureSocial likes to "touch" the session to update the
    // last used time, but we're not using that right now. If we eventually do want to keep track of the last used
    // time, we should try to avoid writing to the database every time.
    oldSession.copy(
      updatedAt = newSession.updatedAt,
      createdAt = newSession.createdAt,
      id = newSession.id) != newSession
  }

  def save(authenticator: Authenticator): Either[Error, Unit] = reportExceptions {
    val newSession = sessionFromAuthenticator(authenticator)
    log.info(s"[save] authenticator=$authenticator newSession=$newSession")
    authenticatorFromSession {
      val oldSessionOpt = db.readOnly { implicit s => sessionRepo.getOpt(newSession.externalId) }
      if (oldSessionOpt.exists(!needsUpdate(_, newSession))) {
        oldSessionOpt.get
      } else {
        db.readWrite { implicit s =>
          sessionRepo.save(newSession.copy(
            id = oldSessionOpt.map(_.id.get),
            createdAt = oldSessionOpt.map(_.createdAt).getOrElse(newSession.createdAt)
          ))
        }
      }
    }
  }
  def find(id: String): Either[Error, Option[Authenticator]] = reportExceptions {
    val externalIdOpt = try {
      Some(ExternalId[UserSession](id))
    } catch {
      case ex: Throwable => None
    }

    val res = externalIdOpt flatMap { externalId =>
      db.readOnly { implicit s =>
        val sess = sessionRepo.getOpt(externalId)
        log.info(s"[find] sessionRepo.get($externalId)=$sess")
        sess
      } collect {
        case s if s.isValid =>
          authenticatorFromSession(s)
      }
    }
    log.info(s"[find] id=$id res=$res")
    res
  }
  def delete(id: String): Either[Error, Unit] = reportExceptions {
    db.readWrite { implicit s =>
      sessionRepo.getOpt(ExternalId[UserSession](id)).foreach { session =>
        sessionRepo.save(session invalidated)
      }
    }
  }
}
