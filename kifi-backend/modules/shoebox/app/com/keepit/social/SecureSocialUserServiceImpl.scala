package com.keepit.social

import com.keepit.common.oauth.{ SlackIdentity, EmailPasswordIdentity }
import com.keepit.common.performance._

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.store.S3ImageStore
import com.keepit.inject.AppScoped
import com.keepit.model._
import com.keepit.common.time.{ Clock }
import com.keepit.common.core._
import com.keepit.model.view.UserSessionView
import com.keepit.slack.SlackCommander
import com.keepit.slack.models.{ SlackTeamMembership, SlackTeamMembershipRepo }
import com.keepit.social.SocialNetworks._

import play.api.{ Application }
import securesocial.core._
import securesocial.core.IdentityId
import com.keepit.model.UserEmailAddress
import securesocial.core.providers.Token
import com.keepit.commanders.{ UserCreationCommander, UserEmailAddressCommander, LocalUserExperimentCommander }
import com.keepit.common.mail.EmailAddress

import scala.concurrent.Future
import scala.util.{ Success, Try }

@Singleton
class UserIdentityHelper @Inject() (
    userRepo: UserRepo,
    emailRepo: UserEmailAddressRepo,
    userCredRepo: UserCredRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    identityUserIdCache: IdentityUserIdCache,
    slackMembershipRepo: SlackTeamMembershipRepo) {
  import IdentityHelpers._

  def getOwnerId(identityId: IdentityId)(implicit session: RSession): Option[Id[User]] = identityUserIdCache.getOrElseOpt(IdentityUserIdKey(identityId)) {
    import SocialNetworks._
    val networkType = parseNetworkType(identityId)
    networkType match {
      case EMAIL | FORTYTWO | FORTYTWO_NF => {
        val validEmailAddress = EmailAddress.validate(identityId.userId).toOption getOrElse (throw new IllegalStateException(s"Invalid address for email authentication: ${(networkType, social)}"))
        emailRepo.getOwner(validEmailAddress)
      }
      case SLACK =>
        val (slackTeamId, slackUserId) = parseSlackId(identityId)
        slackMembershipRepo.getBySlackTeamAndUser(slackTeamId, slackUserId).flatMap(_.userId)
      case socialNetwork if social.contains(socialNetwork) =>
        val socialId = parseSocialId(identityId)
        socialUserInfoRepo.getOpt(socialId, networkType).flatMap(_.userId)
      case unsupportedNetwork => throw new Exception(s"Unsupported authentication network: $unsupportedNetwork")
    }
  }

  def getUserIdentityByUserId(userId: Id[User])(implicit session: RSession): Option[UserIdentity] = {
    Try(emailRepo.getByUser(userId)) match {
      case Success(email) =>
        val user = userRepo.get(userId)
        val userCred = userCredRepo.findByUserIdOpt(userId)
        Some(UserIdentity(user, email, userCred))
      case _ =>
        socialUserInfoRepo.getByUser(userId).filter(_.networkType != SocialNetworks.FORTYTWO).flatMap { info =>
          info.credentials.map(UserIdentity(info.userId, _))
        }.headOption orElse {
          slackMembershipRepo.getByUserId(userId).find(_.token.isDefined).map(SlackTeamMembership.toIdentity)
        }
    }
  }

  def getUserIdentity(identityId: IdentityId)(implicit session: RSession): Option[UserIdentity] = {
    val networkType = parseNetworkType(identityId)
    networkType match {
      case EMAIL | FORTYTWO | FORTYTWO_NF => {
        EmailAddress.validate(identityId.userId).toOption.flatMap { email =>
          emailRepo.getByAddress(email).map { emailAddr =>
            val userId = emailAddr.userId
            val user = userRepo.get(userId)
            val userCred = userCredRepo.findByUserIdOpt(userId)
            UserIdentity(user, email, userCred)
          }
        }
      }
      case SLACK =>
        Try(parseSlackId(identityId)).toOption.flatMap {
          case (slackTeamId, slackUserId) => slackMembershipRepo.getBySlackTeamAndUser(slackTeamId, slackUserId).map(SlackTeamMembership.toIdentity)
        }
      case socialNetwork if SocialNetworks.social.contains(socialNetwork) => {
        val socialId = parseSocialId(identityId)
        socialUserInfoRepo.getOpt(socialId, networkType).flatMap { info =>
          info.credentials.map(UserIdentity(info.userId, _))
        }
      }
    }
  }
}

@Singleton
class SecureSocialUserPluginImpl @Inject() (
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  userRepo: UserRepo,
  userCredRepo: UserCredRepo,
  imageStore: S3ImageStore,
  airbrake: AirbrakeNotifier,
  emailRepo: UserEmailAddressRepo,
  slackMembershipRepo: SlackTeamMembershipRepo,
  socialGraphPlugin: SocialGraphPlugin,
  userCreationCommander: UserCreationCommander,
  userExperimentCommander: LocalUserExperimentCommander,
  userEmailAddressCommander: UserEmailAddressCommander,
  slackCommander: SlackCommander,
  userIdentityHelper: UserIdentityHelper,
  clock: Clock)
    extends UserService with SecureSocialUserPlugin with Logging {

  import IdentityHelpers._

  private def reportExceptions[T](f: => T): T = try f catch {
    case ex: Throwable =>
      airbrake.notify(ex)
      throw ex
  }

  def find(id: IdentityId): Option[UserIdentity] = reportExceptions {
    db.readOnlyMaster { implicit session =>
      userIdentityHelper.getUserIdentity(id)
    }
  }

  def save(identity: Identity): UserIdentity = reportExceptions {
    val (userIdOpt, socialUser, allowSignup) = getUserIdAndSocialUser(identity)
    log.info(s"[save] Interning SocialUser $socialUser. [userId=$userIdOpt, allowSignup=$allowSignup]")
    val savedUserIdOpt = getOrCreateUser(userIdOpt, socialUser, allowSignup) match {
      case None => {
        // This is required on the first step of a social signup, in order to persist credentials before a User is actually created
        if (SocialNetworks.social.contains(parseNetworkType(socialUser))) {
          db.readWrite { implicit session =>
            internSocialUserInfo(None, socialUser)
          }
        }
        None
      }
      case Some(user) => {
        val userId = user.id.get
        val networkType = parseNetworkType(socialUser)
        val (isNewIdentity, socialUserInfoOpt) = db.readWrite { implicit session =>
          val isNewEmailAddressMaybe = internEmailAddress(userId, socialUser).map(_._2)
          networkType match {
            case EMAIL | FORTYTWO | FORTYTWO_NF => {
              val isNewEmailAddress = isNewEmailAddressMaybe.get
              userCredRepo.internUserPassword(userId, socialUser.passwordInfo.get.password)
              (isNewEmailAddress, None)
            }
            case SLACK => (connectSlackMembership(userId, socialUser), None)
            case socialNetwork if SocialNetworks.social.contains(socialNetwork) => {
              val (socialUserInfo, isNewSocialUserInfo) = internSocialUserInfo(Some(userId), socialUser)
              (isNewSocialUserInfo, Some(socialUserInfo))
            }
          }
        }

        if (isNewIdentity) {
          uploadProfileImage(user, socialUser)
        }
        if (SocialNetworks.REFRESHING.contains(networkType)) {
          socialUserInfoOpt.foreach(socialGraphPlugin.asyncFetch(_))
        }
        updateExperimentIfTestUser(userId)
        Some(userId)
      }
    }
    UserIdentity(savedUserIdOpt, socialUser)
  }

  private def updateExperimentIfTestUser(userId: Id[User]): Unit = try {
    timing(s"updateExperimentIfTestUser $userId") {
      @inline def setExp(exp: UserExperimentType) {
        val marked = userExperimentCommander.userHasExperiment(userId, exp)
        if (marked)
          log.debug(s"test user $userId is already marked as $exp")
        else {
          log.debug(s"setting test user $userId as $exp")
          userExperimentCommander.addExperimentForUser(userId, exp)
        }
      }
      val emailAddresses = db.readOnlyMaster(attempts = 3) { implicit rw => emailRepo.getAllByUser(userId).map(_.address) }
      val experiments = emailAddresses.flatMap(UserExperimentType.getExperimentForEmail)
      experiments.foreach(setExp)
    }
  } catch {
    case e: Exception => airbrake.notify(s"error updating experiment if test user for user $userId")
  }

  private def getUserIdAndSocialUser(identity: Identity): (Option[Id[User]], SocialUser, Boolean) = {
    identity match {
      case UserIdentity(userId, socialUser) => (userId, socialUser, false)
      case NewUserIdentity(userId, socialUser) => (userId, socialUser, true)
      case ident =>
        airbrake.notify(s"using an identity $ident should not be possible at this point!")
        (None, SocialUser(ident), false)
    }
  }

  private def createUser(socialUser: SocialUser): User = timing(s"create user ${socialUser.identityId}") {
    val u = userCreationCommander.createUser(
      socialUser.firstName,
      socialUser.lastName,
      state = UserStates.ACTIVE
    )
    log.info(s"[createUser] new user: name=${u.firstName + " " + u.lastName} state=${u.state}")
    u
  }

  private def getExistingUserOrAllowSignup(userId: Option[Id[User]], socialUser: SocialUser, allowSignup: Boolean)(implicit session: RSession): Either[User, Boolean] = {
    val socialUserOwnerId = userIdentityHelper.getOwnerId(socialUser.identityId)
    (userId orElse socialUserOwnerId) match {
      case None => Right(allowSignup) // todo(Léo): check for existing email address here rather than in AuthCommander?
      case Some(existingUserId) if socialUserOwnerId.exists(_ != existingUserId) => {
        val message = s"User $existingUserId passed a SocialUser owned by user ${socialUserOwnerId.get}: $socialUser"
        log.warn(message)
        airbrake.notify(new IllegalStateException(message))
        Right(false)
      }
      case Some(existingUserId) => userRepo.get(existingUserId) match {
        case user if Set(UserStates.BLOCKED, UserStates.INACTIVE).contains(user.state) => {
          val message = s"User $existingUserId has an invalid state: ${user.state}!"
          airbrake.notify(new IllegalStateException(message))
          Right(false)
        }
        case user => Left(user)
      }
    }
  }

  private def getOrCreateUser(userId: Option[Id[User]], socialUser: SocialUser, allowSignup: Boolean): Option[User] = {
    db.readOnlyMaster { implicit session =>
      getExistingUserOrAllowSignup(userId, socialUser, allowSignup)
    } match {
      case Left(existingUser) if existingUser.state == UserStates.INCOMPLETE_SIGNUP => Some {
        db.readWrite { implicit session =>
          userRepo.save(existingUser.withName(socialUser.firstName, socialUser.lastName).withState(UserStates.ACTIVE))
        }
      }
      case Left(existingUser) => Some(existingUser)
      case Right(true) => Some(createUser(socialUser))
      case Right(false) => None
    }
  }

  private def internEmailAddress(userId: Id[User], socialUser: SocialUser)(implicit session: RWSession): Try[(UserEmailAddress, Boolean)] = {
    parseEmailAddress(socialUser).flatMap { emailAddress =>
      val networkType = parseNetworkType(socialUser)
      val verified = verifiedEmailProviders.contains(networkType)
      userEmailAddressCommander.intern(userId, emailAddress, verified)
    }
  }

  private def internSocialUserInfo(userIdOpt: Option[Id[User]], socialUser: SocialUser)(implicit session: RWSession): (SocialUserInfo, Boolean) = {
    val networkType = parseNetworkType(socialUser)
    require(SocialNetworks.social.contains(networkType), s"SocialUser from $networkType should not intern a SocialUserInfo: $socialUser")

    val socialId = parseSocialId(socialUser)
    userIdOpt.foreach { userId =>
      val existingSocialIdForNetwork = socialUserInfoRepo.getByUser(userId).collectFirst { case info if info.networkType == networkType => info.socialId }
      if (existingSocialIdForNetwork.exists(_ != socialId)) {
        val message = s"Can't intern SocialUserInfo $socialUser for user $userId who has already connected a $networkType account with SocialId $existingSocialIdForNetwork"
        throw new IllegalStateException(message)
      }
    }

    socialUserInfoRepo.getOpt(socialId, networkType) match {
      case Some(existingInfo) if existingInfo.state != SocialUserInfoStates.INACTIVE => {
        //todo(eishay): send a direct fetch request, social user info with user must be FETCHED_USING_SELF, so setting user should trigger a pull
        val updatedState = if (existingInfo.state == SocialUserInfoStates.APP_NOT_AUTHORIZED) SocialUserInfoStates.CREATED else existingInfo.state
        val updatedInfo = existingInfo.withCredentials(socialUser).withState(updatedState).copy(userId = userIdOpt orElse existingInfo.userId)
        val savedInfo = if (updatedInfo != existingInfo) socialUserInfoRepo.save(updatedInfo) else existingInfo
        val isNewIdentity = savedInfo.userId != existingInfo.userId
        (savedInfo, isNewIdentity)
      }
      case inactiveExistingInfoOpt => {
        val newInfo = SocialUserInfo(id = inactiveExistingInfoOpt.flatMap(_.id), userId = userIdOpt,
          socialId = socialId, networkType = networkType, pictureUrl = socialUser.avatarUrl,
          fullName = socialUser.fullName, credentials = Some(socialUser)
        )
        (socialUserInfoRepo.save(newInfo), true)
      }
    }
  }

  private def connectSlackMembership(userId: Id[User], socialUser: SocialUser)(implicit session: RWSession): Boolean = {
    val (slackTeamId, slackUserId) = parseSlackId(socialUser.identityId)
    slackCommander.unsafeConnectSlackMembership(slackTeamId, slackUserId, userId)
  }

  private def uploadProfileImage(user: User, socialUser: SocialUser): Future[Unit] = {
    val networkType = parseNetworkType(socialUser)
    val socialId = parseSocialId(socialUser)
    imageStore.uploadRemotePicture(user.id.get, user.externalId, UserPictureSource(networkType), None, setDefault = false) { preferredSize =>
      SocialNetworks.getPictureUrl(networkType, socialId)(preferredSize) orElse socialUser.avatarUrl
    } imap (_ => ())
  }

  def findByEmailAndProvider(email: String, providerId: String): Option[SocialUser] = None
  def save(token: Token): Unit = {}
  def findToken(token: String): Option[Token] = None
  def deleteToken(uuid: String): Unit = {}
  def deleteExpiredTokens(): Unit = {}
}

@AppScoped
class SecureSocialAuthenticatorPluginImpl @Inject() (
  db: Database,
  sessionRepo: UserSessionRepo,
  userIdentityHelper: UserIdentityHelper,
  airbrake: AirbrakeNotifier,
  app: Application)
    extends AuthenticatorStore(app) with SecureSocialAuthenticatorPlugin with Logging {

  import IdentityHelpers._

  private def reportExceptionsAndTime[T](tag: String)(f: => T): Either[Error, T] = timing(tag) {
    try Right(f) catch {
      case ex: Throwable =>
        airbrake.notify(ex)
        log.error("error while using secure social plugin", ex)
        Left(new Error(ex))
    }
  }

  private def sessionFromAuthenticator(authenticator: Authenticator): UserSession = timing(s"sessionFromAuthenticator ${authenticator.identityId.userId}") {
    val userId = db.readOnlyMaster { implicit session =>
      userIdentityHelper.getOwnerId(authenticator.identityId)
    }
    UserSession(
      userId = userId,
      externalId = ExternalId[UserSession](authenticator.id),
      socialId = parseSocialId(authenticator.identityId),
      provider = parseNetworkType(authenticator.identityId),
      expires = authenticator.expirationDate,
      state = if (authenticator.isValid) UserSessionStates.ACTIVE else UserSessionStates.INACTIVE
    )
  }

  private def authenticatorFromSession(session: UserSessionView, externalId: ExternalId[UserSession]): Authenticator = Authenticator(
    id = externalId.id,
    identityId = getIdentityId(session),
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
        if (id.nonEmpty) {
          log.warn(s"error parsing external id[$id]")
        }
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
