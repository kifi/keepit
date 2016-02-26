package com.keepit.social

import com.keepit.common.oauth._
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
import com.keepit.slack.SlackIdentityCommander
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
      case unsupportedNetwork => throw new IllegalStateException(s"Unsupported authentication network: $unsupportedNetwork")
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
          case (slackTeamId, slackUserId) => slackMembershipRepo.getBySlackTeamAndUser(slackTeamId, slackUserId).map(SlackTeamMembership.toUserIdentity)
        }
      case socialNetwork if SocialNetworks.social.contains(socialNetwork) => {
        val socialId = parseSocialId(identityId)
        socialUserInfoRepo.getOpt(socialId, networkType).flatMap { info =>
          info.credentials.map { socialUser =>
            val richIdentity = socialNetwork match {
              case FACEBOOK => FacebookIdentity(socialUser)
              case LINKEDIN => LinkedInIdentity(socialUser)
              case TWITTER => TwitterIdentity(socialUser, info.pictureUrl, info.profileUrl)
              case _ => throw new IllegalStateException(s"Unexpected SocialUserInfo: $info")
            }
            UserIdentity(richIdentity, info.userId)
          }
        }
      }
      case unsupportedNetwork => throw new IllegalStateException(s"Unsupported authentication network: $unsupportedNetwork")
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
  slackIdentityCommander: SlackIdentityCommander,
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
    val maybeUserIdentity = identity match {
      case valid: MaybeUserIdentity => valid
      case _ => throw new IllegalStateException(s"Asked to save unexpected identity: $identity")
    }
    log.info(s"[save] Interning identity $maybeUserIdentity")
    val savedUserIdOpt = getOrCreateUser(maybeUserIdentity) match {
      case None =>
        // This is required on the first step of a social signup, in order to persist credentials before a User is actually created
        db.readWrite { implicit session =>
          maybeUserIdentity.identity match {
            case slackIdentity: SlackIdentity => internSlackIdentity(None, slackIdentity)
            case socialIdentity if SocialNetworks.social.contains(parseNetworkType(maybeUserIdentity)) => internSocialUserInfo(None, maybeUserIdentity)
            case _ => // ignore
          }
        }
        None
      case Some(user) => {
        val userId = user.id.get
        val networkType = parseNetworkType(maybeUserIdentity)
        val (isNewIdentity, socialUserInfoOpt) = db.readWrite { implicit session =>
          val isNewEmailAddressMaybe = internEmailAddress(userId, maybeUserIdentity).map(_._2)
          maybeUserIdentity.identity match {
            case emailIdentity: EmailPasswordIdentity =>
              val isNewEmailAddress = isNewEmailAddressMaybe.get
              userCredRepo.internUserPassword(userId, maybeUserIdentity.passwordInfo.get.password)
              (isNewEmailAddress, None)
            case slackIdentity: SlackIdentity => (internSlackIdentity(Some(userId), slackIdentity), None)
            case socialIdentity if SocialNetworks.social.contains(networkType) =>
              val (socialUserInfo, isNewSocialUserInfo) = internSocialUserInfo(Some(userId), maybeUserIdentity)
              (isNewSocialUserInfo, Some(socialUserInfo))
            case unexpectedRichIdentity =>
              val message = s"Got unexpected RichIdentity $unexpectedRichIdentity"
              log.error(message)
              airbrake.notify(new IllegalStateException(message))
              (false, None)
          }
        }

        if (isNewIdentity) {
          uploadProfileImage(user, maybeUserIdentity)
        }
        if (SocialNetworks.REFRESHING.contains(networkType)) {
          socialUserInfoOpt.foreach(socialGraphPlugin.asyncFetch(_))
        }
        updateExperimentIfTestUser(userId)
        Some(userId)
      }
    }
    UserIdentity(maybeUserIdentity.identity, savedUserIdOpt)
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

  private def createUser(identity: MaybeUserIdentity): User = timing(s"create user ${identity.identityId}") {
    require(identity.isInstanceOf[NewUserIdentity], s"Unexpected identity at user creation: $identity")
    val u = userCreationCommander.createUser(
      identity.firstName,
      identity.lastName,
      state = UserStates.ACTIVE
    )
    log.info(s"[createUser] new user: name=${u.firstName + " " + u.lastName} state=${u.state}")
    u
  }

  private def getExistingUserOrAllowSignup(identity: MaybeUserIdentity)(implicit session: RSession): Either[User, Boolean] = {
    val identityOwnerId = userIdentityHelper.getOwnerId(RichIdentity.toIdentityId(identity.identity))
    (identity.userId orElse identityOwnerId) match {
      case None => Right {
        // todo(Léo): check for existing email address here rather than in AuthCommander?
        identity match {
          case _: NewUserIdentity => true
          case _ => false
        }
      }
      case Some(existingUserId) if identityOwnerId.exists(_ != existingUserId) => {
        val message = s"User $existingUserId passed an identity owned by user ${identityOwnerId.get}: $identity"
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

  private def getOrCreateUser(identity: MaybeUserIdentity): Option[User] = {
    db.readOnlyMaster { implicit session =>
      getExistingUserOrAllowSignup(identity)
    } match {
      case Left(existingUser) if existingUser.state == UserStates.INCOMPLETE_SIGNUP => Some {
        db.readWrite { implicit session =>
          userRepo.save(existingUser.withName(identity.firstName, identity.lastName).withState(UserStates.ACTIVE))
        }
      }
      case Left(existingUser) => Some(existingUser)
      case Right(true) => Some(createUser(identity))
      case Right(false) => None
    }
  }

  private def internEmailAddress(userId: Id[User], identity: MaybeUserIdentity)(implicit session: RWSession): Try[(UserEmailAddress, Boolean)] = {
    parseEmailAddress(identity).flatMap { emailAddress =>
      val networkType = parseNetworkType(identity)
      val verified = verifiedEmailProviders.contains(networkType)
      userEmailAddressCommander.intern(userId, emailAddress, verified)
    }
  }

  private def internSocialUserInfo(userIdOpt: Option[Id[User]], identity: MaybeUserIdentity)(implicit session: RWSession): (SocialUserInfo, Boolean) = {

    val networkType = parseNetworkType(identity)
    require(SocialNetworks.social.contains(networkType), s"Identity from $networkType should not intern a SocialUserInfo: $identity")
    val socialId = parseSocialId(identity)
    userIdOpt.foreach { userId =>
      val existingSocialIdForNetwork = socialUserInfoRepo.getByUser(userId).collectFirst { case info if info.networkType == networkType => info.socialId }
      if (existingSocialIdForNetwork.exists(_ != socialId)) {
        val message = s"Can't intern SocialUserInfo $identity for user $userId who has already connected a $networkType account with SocialId $existingSocialIdForNetwork"
        throw new IllegalStateException(message)
      }
    }

    socialUserInfoRepo.getOpt(socialId, networkType) match {
      case Some(existingInfo) if existingInfo.state != SocialUserInfoStates.INACTIVE => {
        //todo(eishay): send a direct fetch request, social user info with user must be FETCHED_USING_SELF, so setting user should trigger a pull
        val updatedState = if (existingInfo.state == SocialUserInfoStates.APP_NOT_AUTHORIZED) SocialUserInfoStates.CREATED else existingInfo.state
        val updatedInfo = existingInfo.withCredentials(identity).withState(updatedState).copy(userId = userIdOpt orElse existingInfo.userId)
        val savedInfo = if (updatedInfo != existingInfo) socialUserInfoRepo.save(updatedInfo) else existingInfo
        val isNewIdentity = savedInfo.userId != existingInfo.userId
        (savedInfo, isNewIdentity)
      }
      case inactiveExistingInfoOpt => {
        val newInfo = SocialUserInfo(id = inactiveExistingInfoOpt.flatMap(_.id), userId = userIdOpt,
          socialId = socialId, networkType = networkType, pictureUrl = identity.avatarUrl,
          fullName = identity.fullName, credentials = Some(identity)
        )
        (socialUserInfoRepo.save(newInfo), true)
      }
    }
  }

  private def internSlackIdentity(userId: Option[Id[User]], identity: SlackIdentity)(implicit session: RWSession): Boolean = {
    slackIdentityCommander.internSlackIdentity(userId, identity)
  }

  private def uploadProfileImage(user: User, identity: MaybeUserIdentity): Future[Unit] = {
    val networkType = parseNetworkType(identity)
    val socialId = parseSocialId(identity)
    imageStore.uploadRemotePicture(user.id.get, user.externalId, UserPictureSource(networkType), None, setDefault = false) { preferredSize =>
      SocialNetworks.getPictureUrl(networkType, socialId)(preferredSize) orElse identity.avatarUrl
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
