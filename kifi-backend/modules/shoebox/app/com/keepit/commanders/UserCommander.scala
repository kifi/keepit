package com.keepit.commanders

import akka.actor.Scheduler
import com.google.inject.{ ImplementedBy, Singleton, Provider, Inject }
import com.keepit.abook.ABookServiceClient
import com.keepit.classify.{ DomainRepo, NormalizedHostname }
import com.keepit.commanders.HandleCommander.{ UnavailableHandleException, InvalidHandleException }
import com.keepit.commanders.emails.{ ContactJoinedEmailSender, WelcomeEmailSender }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.TransactionalCaching
import com.keepit.common.core._
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ EmailAddress, _ }
import com.keepit.common.performance.StatsdTiming
import com.keepit.common.service.{ RequestConsolidator, IpAddress }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time._
import com.keepit.common.usersegment.{ UserSegment, UserSegmentFactory }
import com.keepit.eliza.{ UserPushNotificationCategory, PushNotificationExperiment, ElizaServiceClient }
import com.keepit.graph.GraphServiceClient
import com.keepit.heimdal.{ ContextStringData, HeimdalServiceClient, _ }
import com.keepit.model.UserValueName._
import com.keepit.model._
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.SocialContactJoined
import com.keepit.search.SearchServiceClient
import com.keepit.slack.{ SlackInfoCommander, UserSlackInfo }
import com.keepit.slack.models._
import com.keepit.social.{ BasicUser, SocialNetworks, UserIdentity }
import com.keepit.typeahead.{ KifiUserTypeahead, SocialUserTypeahead, TypeaheadHit }
import com.kifi.macros.json
import play.api.libs.json._
import play.api.libs.functional.syntax._
import securesocial.core._
import com.keepit.common.core._
import scala.concurrent.duration._

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util._

case class BasicSocialUser(network: String, profileUrl: Option[String], pictureUrl: Option[String])
object BasicSocialUser {
  implicit val writesBasicSocialUser = Json.writes[BasicSocialUser]
  def from(sui: SocialUserInfo): BasicSocialUser =
    BasicSocialUser(network = sui.networkType.name, profileUrl = sui.getProfileUrl, pictureUrl = sui.getPictureUrl())
}

case class EmailInfo(address: EmailAddress, isPrimary: Boolean, isVerified: Boolean, isPendingPrimary: Boolean, isFreeMail: Boolean, isOwned: Boolean)
object EmailInfo {
  implicit val format = new Format[EmailInfo] {
    def reads(json: JsValue): JsResult[EmailInfo] = {
      Try(new EmailInfo(
        (json \ "address").as[EmailAddress],
        (json \ "isPrimary").asOpt[Boolean].getOrElse(false),
        (json \ "isVerified").asOpt[Boolean].getOrElse(false),
        (json \ "isPendingPrimary").asOpt[Boolean].getOrElse(false),
        (json \ "isFreeMail").asOpt[Boolean].getOrElse(false),
        (json \ "isOwned").asOpt[Boolean].getOrElse(false)
      )).toOption match {
        case Some(ei) => JsSuccess(ei)
        case None => JsError()
      }
    }

    def writes(ei: EmailInfo): JsValue = {
      Json.obj("address" -> ei.address, "isPrimary" -> ei.isPrimary, "isVerified" -> ei.isVerified, "isPendingPrimary" -> ei.isPendingPrimary, "isFreeMail" -> ei.isFreeMail, "isOwned" -> ei.isOwned)
    }
  }
}

case class UpdatableUserInfo(
  biography: Option[String], emails: Option[Seq[EmailInfo]],
  firstName: Option[String] = None, lastName: Option[String] = None)

object UpdatableUserInfo {
  implicit val updatableUserDataFormat = Json.format[UpdatableUserInfo]
}

case class BasicUserInfo(
  basicUser: BasicUser,
  info: UpdatableUserInfo,
  notAuthed: Seq[String],
  numLibraries: Int,
  numConnections: Int,
  numFollowers: Int,
  orgs: Seq[OrganizationView],
  pendingOrgs: Seq[OrganizationView],
  potentialOrgs: Seq[OrganizationView],
  slack: UserSlackInfo)

case class UserProfile(userId: Id[User], basicUserWithFriendStatus: BasicUserWithFriendStatus, numKeeps: Int)

case class UserProfileStats(
  numLibraries: Int,
  numFollowedLibraries: Int,
  numCollabLibraries: Int,
  numKeeps: Int,
  numConnections: Int,
  numFollowers: Int,
  numTags: Int,
  numInvitedLibraries: Option[Int] = None,
  biography: Option[String] = None,
  orgs: Seq[OrganizationInfo],
  slackInfo: UserSlackInfo,
  pendingOrgs: Set[OrganizationInfo])
object UserProfileStats {
  implicit val writes: Writes[UserProfileStats] = (
    (__ \ 'numLibraries).write[Int] and
    (__ \ 'numFollowedLibraries).write[Int] and
    (__ \ 'numCollabLibraries).write[Int] and
    (__ \ 'numKeeps).write[Int] and
    (__ \ 'numConnections).write[Int] and
    (__ \ 'numFollowers).write[Int] and
    (__ \ 'numTags).write[Int] and
    (__ \ 'numInvitedLibraries).writeNullable[Int] and
    (__ \ 'biography).writeNullable[String] and
    (__ \ 'orgs).write[Seq[OrganizationInfo]] and
    (__ \ 'slackInfo).write[UserSlackInfo] and
    (__ \ 'pendingOrgs).write[Set[OrganizationInfo]]
  )(unlift(UserProfileStats.unapply))
}

case class UserNotFoundException(username: Username) extends Exception(username.toString)

@ImplementedBy(classOf[UserCommanderImpl])
trait UserCommander {
  def userFromUsername(username: Username): Option[User]
  def profile(username: Username, viewer: Option[User]): Option[UserProfile]
  def setSettings(userId: Id[User], newSettings: UserValueSettings)
  def updateUserBiography(userId: Id[User], biography: String): Unit
  def updateUserInfo(userId: Id[User], userData: UpdatableUserInfo): Unit
  def updateName(userId: Id[User], newFirstName: Option[String], newLastName: Option[String]): User
  def socialNetworkInfo(userId: Id[User]): Seq[BasicSocialUser]
  def getGmailABookInfos(userId: Id[User]): Future[Seq[ABookInfo]]
  def uploadContactsProxy(userId: Id[User], origin: ABookOriginType, payload: JsValue): Future[Try[ABookInfo]]
  def getUserInfo(user: User): BasicUserInfo
  def getUserSegment(userId: Id[User]): UserSegment
  def tellUsersWithContactOfNewUserImmediate(newUser: User): Option[Future[Set[Id[User]]]]
  def sendWelcomeEmail(userId: Id[User], withVerification: Boolean = false, targetEmailOpt: Option[EmailAddress] = None): Future[Unit]
  def changePassword(userId: Id[User], newPassword: String, oldPassword: Option[String]): Try[Unit]
  def resetPassword(code: String, ip: IpAddress, password: String): Either[String, Id[User]]
  def sendCloseAccountEmail(userId: Id[User], comment: String): ElectronicMail
  def sendCreateTeamEmail(userId: Id[User], emailAddress: EmailAddress): Either[String, ElectronicMail]
  def getPrefs(prefSet: Set[UserValueName], userId: Id[User], experiments: Set[UserExperimentType]): JsObject
  def savePrefs(userId: Id[User], o: Map[UserValueName, JsValue]): Unit
  def setLastUserActive(userId: Id[User]): Unit
  def postDelightedAnswer(userId: Id[User], answer: BasicDelightedAnswer): Future[Option[ExternalId[DelightedAnswer]]]
  def cancelDelightedSurvey(userId: Id[User]): Future[Boolean]
  def setUsername(userId: Id[User], username: Username, overrideValidityCheck: Boolean = false, overrideProtection: Boolean = false): Either[String, Username]
  def getFriendRecommendations(userId: Id[User], offset: Int, limit: Int): Future[Option[FriendRecommendations]]
  def loadBasicUsersAndConnectionCounts(idsForUsers: Set[Id[User]], idsForCounts: Set[Id[User]]): (Map[Id[User], BasicUser], Map[Id[User], Int])
  def reNormalizedUsername(readOnly: Boolean, max: Int): Int
  def getByExternalIds(externalIds: Seq[ExternalId[User]]): Map[ExternalId[User], User]
  def getByExternalId(externalId: ExternalId[User]): User
  def getAllFakeUsers(): Set[Id[User]]
}

@Singleton
class UserCommanderImpl @Inject() (
    db: Database,
    userRepo: UserRepo,
    handleCommander: HandleCommander,
    userCredRepo: UserCredRepo,
    passwordResetRepo: PasswordResetRepo,
    emailRepo: UserEmailAddressRepo,
    userValueRepo: UserValueRepo,
    userConnectionRepo: UserConnectionRepo,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    libraryRepo: LibraryRepo,
    orgConfigurationRepo: OrganizationConfigurationRepo,
    domainRepo: DomainRepo,
    organizationInfoCommander: OrganizationInfoCommander,
    organizationMembershipRepo: OrganizationMembershipRepo,
    organizationInviteRepo: OrganizationInviteRepo,
    organizationDomainOwnershipRepo: OrganizationDomainOwnershipRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    abookServiceClient: ABookServiceClient,
    graphServiceClient: GraphServiceClient,
    postOffice: LocalPostOffice,
    clock: Clock,
    elizaServiceClient: ElizaServiceClient,
    heimdalClient: HeimdalServiceClient,
    libraryMembershipRepo: LibraryMembershipRepo,
    friendStatusCommander: FriendStatusCommander,
    userEmailAddressCommander: UserEmailAddressCommander,
    welcomeEmailSender: Provider[WelcomeEmailSender],
    contactJoinedEmailSender: Provider[ContactJoinedEmailSender],
    userExperimentRepo: UserExperimentRepo,
    allFakeUsersCache: AllFakeUsersCache,
    kifiInstallationCommander: KifiInstallationCommander,
    kifiInstallationRepo: KifiInstallationRepo,
    implicit val executionContext: ExecutionContext,
    experimentRepo: UserExperimentRepo,
    slackTeamMembershipRepo: SlackTeamMembershipRepo,
    slackTeamRepo: SlackTeamRepo,
    slackInfoCommander: SlackInfoCommander,
    airbrake: AirbrakeNotifier) extends UserCommander with Logging { self =>

  def userFromUsername(username: Username): Option[User] = db.readOnlyReplica { implicit session =>
    userRepo.getByUsername(username) //cached
  }

  def profile(username: Username, viewer: Option[User]): Option[UserProfile] = {
    userFromUsername(username) map { user =>
      val basicUserWithFriendStatus = viewer.filter(_.id != user.id) map { viewer =>
        db.readOnlyMaster { implicit session =>
          friendStatusCommander.augmentUser(viewer.id.get, user.id.get, BasicUser.fromUser(user))
        }
      } getOrElse BasicUserWithFriendStatus.fromWithoutFriendStatus(user)
      db.readOnlyReplica { implicit session =>
        //not in v1
        //    val friends = userConnectionRepo.getConnectionCount(user.id.get) //cached // remove this?
        //    val numFollowers = libraryMembershipRepo.countFollowersWithOwnerId(user.id.get) //cached // remove this?
        val numKeeps = keepRepo.getCountByUser(user.id.get)
        UserProfile(userId = user.id.get, basicUserWithFriendStatus, numKeeps = numKeeps)
      }
    }
  }

  def setSettings(userId: Id[User], newSettings: UserValueSettings) = {
    db.readWrite { implicit s =>
      var settings = userValueRepo.getValue(userId, UserValues.userProfileSettings).as[JsObject]
      settings = settings ++ UserValueSettings.writeToJson(newSettings).as[JsObject]
      userValueRepo.setValue(userId, UserValueName.USER_PROFILE_SETTINGS, settings)
    }
  }

  def updateUserBiography(userId: Id[User], biography: String): Unit = {
    db.readWrite(attempts = 3) { implicit session =>
      val trimmed = biography.trim
      if (trimmed.nonEmpty) {
        userValueRepo.setValue(userId, UserValueName.USER_DESCRIPTION, trimmed)
      } else {
        userValueRepo.clearValue(userId, UserValueName.USER_DESCRIPTION)
      }
      userRepo.save(userRepo.getNoCache(userId)) // update user index sequence number
    }
  }

  def updateUserInfo(userId: Id[User], userData: UpdatableUserInfo): Unit = {
    val user = db.readOnlyMaster { implicit session => userRepo.getNoCache(userId) }

    userData.emails.foreach(userEmailAddressCommander.updateEmailAddresses(userId, _))
    userData.biography.foreach(updateUserBiography(userId, _))

    if (userData.firstName.exists(_.nonEmpty) && userData.lastName.exists(_.nonEmpty)) {
      updateUserNames(user, userData.firstName.get, userData.lastName.get)
    }
  }

  private def updateUserNames(user: User, newFirstName: String, newLastName: String): User = {
    db.readWrite { implicit session =>
      userRepo.save(user.copy(firstName = newFirstName, lastName = newLastName))
    }
  }

  def updateName(userId: Id[User], newFirstName: Option[String], newLastName: Option[String]): User = {
    db.readWrite { implicit session =>
      val user = userRepo.get(userId)
      userRepo.save(user.copy(firstName = newFirstName.getOrElse(user.firstName), lastName = newLastName.getOrElse(user.lastName)))
    }
  }

  def socialNetworkInfo(userId: Id[User]): Seq[BasicSocialUser] = db.readOnlyMaster { implicit s =>
    socialUserInfoRepo.getByUser(userId).map(BasicSocialUser.from)
  }

  def getGmailABookInfos(userId: Id[User]): Future[Seq[ABookInfo]] = abookServiceClient.getABookInfos(userId).map(_.filter(_.origin == ABookOrigins.GMAIL))

  def uploadContactsProxy(userId: Id[User], origin: ABookOriginType, payload: JsValue): Future[Try[ABookInfo]] = {
    abookServiceClient.uploadContacts(userId, origin, payload)
  }

  @StatsdTiming("UserCommander.getUserInfo")
  def getUserInfo(user: User): BasicUserInfo = {
    val (basicUser, biography, emails, pendingPrimary, notAuthed, numLibraries, numConnections, numFollowers, orgViews, pendingOrgViews, potentialOrgs, userSlackInfo) = db.readOnlyMaster { implicit session =>
      val basicUser = basicUserRepo.load(user.id.get)
      val biography = userValueRepo.getValueStringOpt(user.id.get, UserValueName.USER_DESCRIPTION)
      val emails = emailRepo.getAllByUser(user.id.get)
      val pendingPrimary = userValueRepo.getValueStringOpt(user.id.get, UserValueName.PENDING_PRIMARY_EMAIL).map(EmailAddress(_))
      val notAuthed = socialUserInfoRepo.getNotAuthorizedByUser(user.id.get).map(_.networkType.name).filter(_ != "linkedin") // Don't send down LinkedIn anymore

      val libCounts = libraryMembershipRepo.countsWithUserIdAndAccesses(user.id.get, LibraryAccess.all.toSet)
      val numLibsOwned = libCounts.getOrElse(LibraryAccess.OWNER, 0)
      val numLibsCollab = libCounts.getOrElse(LibraryAccess.READ_WRITE, 0)
      val numLibraries = numLibsOwned + numLibsCollab

      val numConnections = userConnectionRepo.getConnectionCount(user.id.get)
      val numFollowers = libraryMembershipRepo.countFollowersForOwner(user.id.get)

      val orgs = organizationMembershipRepo.getByUserId(user.id.get, Limit(Int.MaxValue), Offset(0)).map(_.organizationId)
      val orgViews = orgs.map(orgId => organizationInfoCommander.getOrganizationViewHelper(orgId, user.id, authTokenOpt = None))

      val pendingOrgs = organizationInviteRepo.getByInviteeIdAndDecision(user.id.get, InvitationDecision.PENDING).map(_.organizationId)
      val pendingOrgViews = pendingOrgs.map(orgId => organizationInfoCommander.getOrganizationViewHelper(orgId, user.id, authTokenOpt = None))

      val emailHostnames = emails.flatMap(email => NormalizedHostname.fromHostname(email.address.hostname))
      val potentialOrgsToHide = userValueRepo.getValue(user.id.get, UserValues.hideEmailDomainOrganizations).as[Set[Id[Organization]]]
      val potentialOrgIds = organizationDomainOwnershipRepo.getOwnershipsByDomains(emailHostnames.toSet).values.flatten.distinctBy(_.organizationId)
        .collect {
          case ownership if !orgs.contains(ownership.organizationId) && !pendingOrgs.contains(ownership.organizationId) &&
            !potentialOrgsToHide.contains(ownership.organizationId) && canVerifyToJoin(ownership.organizationId) =>
            ownership.organizationId
        }
      val potentialOrgViews = potentialOrgIds.map(orgId => organizationInfoCommander.getOrganizationViewHelper(orgId, user.id, authTokenOpt = None))

      val userSlackInfo = slackInfoCommander.getUserSlackInfo(user.id.get, Some(user.id.get))

      (basicUser, biography, emails, pendingPrimary, notAuthed, numLibraries, numConnections, numFollowers, orgViews, pendingOrgViews, potentialOrgViews.toSeq, userSlackInfo)
    }

    val emailInfos = db.readOnlyReplica { implicit session =>
      emails.sortBy { e => (e.primary, !e.verified, e.id.get.id) }.reverse.map {
        email =>
          EmailInfo(
            address = email.address,
            isVerified = email.verified,
            isPrimary = email.primary,
            isPendingPrimary = pendingPrimary.exists(_.equalsIgnoreCase(email.address)),
            isFreeMail = isFreeMail(email),
            isOwned = isOwned(email)
          )
      }
    }
    BasicUserInfo(basicUser, UpdatableUserInfo(biography, Some(emailInfos)), notAuthed, numLibraries, numConnections, numFollowers, orgViews, pendingOrgViews.toSeq, potentialOrgs, userSlackInfo)
  }

  private def canVerifyToJoin(orgId: Id[Organization])(implicit session: RSession) = orgConfigurationRepo.getByOrgId(orgId).settings.settingFor(StaticFeature.JoinByVerifying).contains(StaticFeatureSetting.NONMEMBERS)
  private def isFreeMail(email: UserEmailAddress)(implicit session: RSession): Boolean = {
    NormalizedHostname.fromHostname(email.address.hostname) match {
      case None =>
        airbrake.notify(s"email ${email.address} owned by user ${email.userId} does not have a valid hostname")
        false
      case Some(hostname) => domainRepo.get(hostname).exists(_.isEmailProvider)
    }
  }
  private def isOwned(email: UserEmailAddress)(implicit session: RSession): Boolean = {
    NormalizedHostname.fromHostname(email.address.hostname) match {
      case None =>
        airbrake.notify(s"email ${email.address} owned by user ${email.userId} does not have a valid hostname")
        false
      case Some(hostname) => organizationDomainOwnershipRepo.getOwnershipsForDomain(hostname).nonEmpty
    }
  }

  def getUserSegment(userId: Id[User]): UserSegment = {
    val (numBms, numFriends) = db.readOnlyReplica { implicit s => //using cache
      (keepRepo.getCountByUser(userId), userConnectionRepo.getConnectionCount(userId))
    }

    val segment = UserSegmentFactory(numBms, numFriends)
    segment
  }

  def tellUsersWithContactOfNewUserImmediate(newUser: User): Option[Future[Set[Id[User]]]] = synchronized {
    require(newUser.id.isDefined, "UserCommander.tellUsersWithContactOfNewUserImmediate: newUser.id is required")

    val newUserId = newUser.id.get
    if (!db.readOnlyMaster { implicit session => userValueRepo.getValueStringOpt(newUserId, UserValueName.CONTACTS_NOTIFIED_ABOUT_JOINING).contains("true") }) {

      val verifiedEmailAddresses = db.readOnlyMaster { implicit session =>
        val allAddresses = emailRepo.getAllByUser(newUserId)
        allAddresses.collect { case email if email.verified => email.address }
      }

      if (verifiedEmailAddresses.nonEmpty) Some {
        db.readWrite { implicit session => userValueRepo.setValue(newUserId, UserValueName.CONTACTS_NOTIFIED_ABOUT_JOINING, true) }
        // get users who have this user's email in their contacts
        Future.sequence(verifiedEmailAddresses.map(abookServiceClient.getUsersWithContact)).imap(_.toSet.flatten) flatMap {
          case contacts if contacts.nonEmpty =>
            val alreadyConnectedUsers = db.readOnlyReplica { implicit session =>
              userConnectionRepo.getConnectedUsers(newUser.id.get)
            }
            // only notify users who are not already connected to our list of users with the contact email
            val toNotify = contacts.diff(alreadyConnectedUsers) - newUserId

            log.info("sending new user contact notifications to: " + toNotify)
            val emailsF = toNotify.map { userId => contactJoinedEmailSender.get.apply(userId, newUserId) }

            toNotify.foreach { userId =>
              val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(userId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
              if (canSendPush) {
                elizaServiceClient.sendUserPushNotification(
                  userId = userId,
                  message = s"${newUser.firstName} ${newUser.lastName} just joined Kifi!",
                  recipient = newUser,
                  pushNotificationExperiment = PushNotificationExperiment.Experiment1,
                  category = UserPushNotificationCategory.ContactJoined)
              }
            }
            toNotify.foreach { userId =>
              elizaServiceClient.sendNotificationEvent(SocialContactJoined(
                Recipient.fromUser(userId),
                currentDateTime,
                newUserId
              ))
            }
            Future.sequence(emailsF.toSeq) map (_ => toNotify)
          case _ =>
            log.info("cannot send contact notifications: no verified email found for user.id=" + newUserId)
            Future.successful(Set.empty)
        }
      }
      else None
    } else Option(Future.successful(Set.empty))
  }

  def sendWelcomeEmail(userId: Id[User], withVerification: Boolean = false, targetEmailOpt: Option[EmailAddress] = None): Future[Unit] = {
    val welcomeEmailAlreadySent = db.readOnlyMaster { implicit session =>
      userValueRepo.getValue(userId, UserValues.welcomeEmailSent)
    }
    if (!welcomeEmailAlreadySent) {
      val (verificationCode, installs) = db.readWrite { implicit session =>
        val verifyCode = for {
          emailAddress <- targetEmailOpt if withVerification
          emailRecord <- emailRepo.getByAddressAndUser(userId, emailAddress)
          code <- emailRecord.verificationCode orElse emailRepo.save(emailRecord.withVerificationCode(clock.now())).verificationCode
        } yield {
          code
        }

        val installs = kifiInstallationRepo.all(userId).groupBy(_.platform).keys.toSet

        verifyCode match {
          case Some(v) => (Some(v), installs)
          case None => (None, installs)
        }
      }

      val emailF = welcomeEmailSender.get.sendToUser(userId, targetEmailOpt, verificationCode, installs)
      emailF.map { email =>
        db.readWrite { implicit rw => userValueRepo.setValue(userId, UserValues.welcomeEmailSent.name, true) }
        ()
      }
    } else Future.successful(())
  }

  def changePassword(userId: Id[User], newPassword: String, oldPassword: Option[String]): Try[Unit] = Try {
    db.readWrite { implicit session =>
      val isAllowed = oldPassword.exists(userCredRepo.verifyPassword(userId).apply(_)) || userValueRepo.getValue(userId, UserValues.hasNoPassword)
      if (isAllowed) {
        doChangePassword(userId, newPassword)
      } else {
        log.warn(s"Failed to change password for user $userId, invalid old password: $oldPassword")
        throw new IllegalArgumentException("bad_old_password")
      }
    }
  }

  def resetPassword(code: String, ip: IpAddress, password: String): Either[String, Id[User]] = {
    db.readWrite { implicit s =>
      passwordResetRepo.getByToken(code) match {
        case Some(pr) if passwordResetRepo.useResetToken(code, ip) =>
          emailRepo.getByAddress(pr.sentTo).foreach(userEmailAddressCommander.saveAsVerified(_)) // mark email address as verified
          doChangePassword(pr.userId, newPassword = password)
          Right(pr.userId)
        case Some(pr) if pr.state == PasswordResetStates.ACTIVE || pr.state == PasswordResetStates.INACTIVE => Left("expired")
        case Some(pr) if pr.state == PasswordResetStates.USED => Left("already_used")
        case _ => Left("invalid_code")
      }
    }
  }

  private def doChangePassword(userId: Id[User], newPassword: String)(implicit session: RWSession): Unit = {
    val newPasswordInfo = Registry.hashers.currentHasher.hash(newPassword)
    val cred = userCredRepo.internUserPassword(userId, newPasswordInfo.password)
    log.info(s"[doChangePassword] UserCreds updated=[id=${cred.id} userId=${cred.userId}]")
    userValueRepo.setValue(userId, UserValueName.HAS_NO_PASSWORD, false)
  }

  implicit val hitOrdering = TypeaheadHit.defaultOrdering[SocialUserBasicInfo]

  def sendCloseAccountEmail(userId: Id[User], comment: String): ElectronicMail = {
    val safeComment = comment.replaceAll("[<>]+", "")
    db.readWrite { implicit s =>
      postOffice.sendMail(ElectronicMail(
        from = SystemEmailAddress.ENG42,
        to = Seq(SystemEmailAddress.SUPPORT),
        subject = s"Close Account for $userId",
        htmlBody = s"User $userId requested to close account.<br/>---<br/>$safeComment",
        category = NotificationCategory.System.ADMIN
      ))
    }
  }

  def sendCreateTeamEmail(userId: Id[User], emailAddress: EmailAddress): Either[String, ElectronicMail] = {
    db.readWrite { implicit s =>
      val userEmailTry = emailRepo.getByAddress(emailAddress, excludeState = None) match {
        case None =>
          Success(emailRepo.save(UserEmailAddress(userId = userId, address = emailAddress, hash = EmailAddressHash.hashEmailAddress(emailAddress))).tap {
            newEmail => userEmailAddressCommander.sendVerificationEmailHelper(newEmail)
          })
        case Some(email) if email.userId != userId => Failure(new Exception("email_taken"))
        case Some(email) if email.state == UserEmailAddressStates.INACTIVE => Success(emailRepo.save(email.withState(UserEmailAddressStates.ACTIVE)))
        case Some(email) => Success(email)
      }
      userEmailTry match {
        case Success(userEmail) => {
          val mail = postOffice.sendMail(ElectronicMail(
            from = SystemEmailAddress.NOTIFICATIONS,
            fromName = Some("Kifi"),
            to = Seq(userEmail.address),
            subject = "Create a Kifi team from your desktop",
            htmlBody =
              s"""
                  |Per your request, you can <a href="https://www.kifi.com/teams/new">create a team</a> on Kifi from
                  |your desktop. Teams allow you to quickly onboard new members via access to all of the libraries within your team's space.
                  |You can also send a page to all team members in just 1 click, integrate your libraries with Slack, and more.
                  |
                  |Get started by visiting the page to <a href="https://www.kifi.com/teams/new">create a team</a>.
              """.stripMargin,
            textBody = Some(
              s"""
                  |Per your request, you can create a team on Kifi from
                  |your desktop. Teams allow you to quickly onboard new members via access to all of the libraries within your team's space.
                  |You can also send a page to all team members in just 1 click, integrate your libraries with Slack, and more.
                  |
                  |Get started by visiting the page to create a team: www.kifi.com/teams/new
               """.stripMargin),
            category = NotificationCategory.User.CREATE_TEAM
          ))
          Right(mail)
        }
        case Failure(err) => Left("email_taken")
      }
    }
  }

  def getPrefs(prefSet: Set[UserValueName], userId: Id[User], experiments: Set[UserExperimentType]): JsObject = {
    updatePrefs(prefSet, userId, experiments)

    val staticPrefs = readUserValuePrefs(prefSet, userId)
    val missing = prefSet.diff(staticPrefs.keySet)
    val allPrefs = (staticPrefs ++ generateDynamicPrefs(missing, userId)).map(r => r._1.name -> r._2)
    JsObject(allPrefs.toSeq)
  }

  private def getPrefUpdates(prefSet: Set[UserValueName], userId: Id[User], experiments: Set[UserExperimentType]): Future[Map[UserValueName, JsValue]] = {
    if (prefSet.contains(UserValueName.SHOW_DELIGHTED_QUESTION)) {
      // Check if user should be shown Delighted question
      val user = db.readOnlyMaster { implicit s =>
        userRepo.get(userId)
      }
      val time = clock.now()
      val shouldShowDelightedQuestionFut = if (experiments.contains(UserExperimentType.DELIGHTED_SURVEY_PERMANENT)) {
        Future.successful(true)
      } else if (time.minusDays(DELIGHTED_INITIAL_DELAY) > user.createdAt) {
        heimdalClient.getLastDelightedAnswerDate(userId).map { lastDelightedAnswerDate =>
          val minDate = lastDelightedAnswerDate getOrElse START_OF_TIME
          time.minusDays(DELIGHTED_MIN_INTERVAL) > minDate
        }.recover {
          case ex: Throwable =>
            airbrake.notify(s"Heimdal call to get delighted pref failed for $userId", ex)
            false
        }
      } else {
        Future.successful(false)
      }
      shouldShowDelightedQuestionFut map { shouldShowDelightedQuestion =>
        Map(UserValueName.SHOW_DELIGHTED_QUESTION -> JsBoolean(shouldShowDelightedQuestion))
      }
    } else Future.successful(Map())
  }

  private def readUserValuePrefs(prefSet: Set[UserValueName], userId: Id[User]): Map[UserValueName, JsValue] = {
    val values = db.readOnlyReplica { implicit s =>
      userValueRepo.getValues(userId, prefSet.toSeq: _*)
    }
    prefSet.flatMap { name =>
      values(name).flatMap { value =>
        if (value == "false") Some(name -> JsBoolean(false))
        else if (value == "true") Some(name -> JsBoolean(true))
        else if (value == "null") None
        else Some(name -> JsString(value))
      }
    }.toMap
  }

  private def generateDynamicPrefs(prefSet: Set[UserValueName], userId: Id[User]): Map[UserValueName, JsValue] = {
    db.readOnlyReplica { implicit session =>
      prefSet.collect {
        case pref if pref == SLACK_INT_PROMO || pref == SLACK_UPSELL_WIDGET =>
          val orgIds = organizationMembershipRepo.getAllByUserId(userId).map(_.organizationId).toSet
          def hasAnOrg = orgIds.nonEmpty
          def hasIntegrations = slackTeamRepo.getByOrganizationIds(orgIds).nonEmpty || slackTeamMembershipRepo.getByUserId(userId).nonEmpty
          pref -> JsBoolean(hasAnOrg && !hasIntegrations)
      }.toMap
    }
  }

  private def updatePrefs(prefSet: Set[UserValueName], userId: Id[User], experiments: Set[UserExperimentType]) = {
    getPrefUpdates(prefSet, userId, experiments) map { updates =>
      savePrefs(userId, updates)
    } recover {
      case t: Throwable => airbrake.notify(s"Error updating prefs for user $userId", t)
    }
  }

  def savePrefs(userId: Id[User], o: Map[UserValueName, JsValue]): Unit = {
    db.readWrite(attempts = 3) { implicit s =>
      o.map {
        case (name, JsNull) => userValueRepo.clearValue(userId, name)
        case (name, _: JsUndefined) => userValueRepo.clearValue(userId, name)
        case (name, JsString(value)) => userValueRepo.setValue(userId, name, value)
        case (name, value) => userValueRepo.setValue(userId, name, value.toString)
      }
    }
    ()
  }

  val DELIGHTED_MIN_INTERVAL = 90 // days
  val DELIGHTED_INITIAL_DELAY = 28 // days

  def setLastUserActive(userId: Id[User]): Unit = {
    val time = clock.now
    db.readWrite(attempts = 3) { implicit s =>
      userValueRepo.setValue(userId, UserValueName.LAST_ACTIVE, time)
    }
  }

  def postDelightedAnswer(userId: Id[User], answer: BasicDelightedAnswer): Future[Option[ExternalId[DelightedAnswer]]] = {
    val (user, emailAddress) = db.readOnlyReplica { implicit s =>
      (userRepo.get(userId), Try(emailRepo.getByUser(userId)).toOption)
    }
    heimdalClient.postDelightedAnswer(DelightedUserRegistrationInfo(userId, user.externalId, emailAddress, user.fullName), answer) map { answerOpt =>
      answerOpt flatMap (_.answerId)
    }
  }

  def cancelDelightedSurvey(userId: Id[User]): Future[Boolean] = {
    val (user, emailAddress) = db.readOnlyReplica { implicit s =>
      (userRepo.get(userId), Try(emailRepo.getByUser(userId)).toOption)
    }
    heimdalClient.cancelDelightedSurvey(DelightedUserRegistrationInfo(userId, user.externalId, emailAddress, user.fullName))
  }

  def setUsername(userId: Id[User], username: Username, overrideValidityCheck: Boolean = false, overrideProtection: Boolean = false): Either[String, Username] = {
    db.readWrite(attempts = 3) { implicit session =>
      val user = userRepo.get(userId)
      handleCommander.setUsername(user, username, overrideProtection = overrideProtection, overrideValidityCheck = overrideValidityCheck) match {
        case Success(updatedUser) => Right(updatedUser.username)
        case Failure(InvalidHandleException(handle)) => Left("invalid_username")
        case Failure(_: UnavailableHandleException) => Left("username_exists")
        case Failure(error) => throw error
      }
    }
  }

  def getFriendRecommendations(userId: Id[User], offset: Int, limit: Int): Future[Option[FriendRecommendations]] = {
    val futureRecommendedUsers = abookServiceClient.getFriendRecommendations(userId, offset, limit)
    val futureRelatedUsers = graphServiceClient.getSociallyRelatedEntitiesForUser(userId)
    val recos = futureRecommendedUsers.flatMap {
      case None => Future.successful(None)
      case Some(recommendedUsers) =>

        val friends = db.readOnlyMaster { implicit session =>
          userConnectionRepo.getConnectedUsersForUsers(recommendedUsers.toSet + userId) //cached
        }

        val mutualFriends = recommendedUsers.map { recommendedUserId =>
          recommendedUserId -> (friends.getOrElse(userId, Set.empty) intersect friends.getOrElse(recommendedUserId, Set.empty))
        }.toMap

        val mutualLibrariesCounts = db.readOnlyMaster { implicit session =>
          libraryRepo.countMutualLibrariesForUsers(userId, recommendedUsers.toSet)
        }

        val allUserIds = mutualFriends.values.flatten.toSet ++ recommendedUsers

        val (basicUsers, userConnectionCounts) = loadBasicUsersAndConnectionCounts(allUserIds, allUserIds)

        futureRelatedUsers.map { sociallyRelatedEntitiesOpt =>

          val friendshipStrength = {
            val relatedUsers = sociallyRelatedEntitiesOpt.map(_.users.related) getOrElse Seq.empty
            relatedUsers.filter { case (userId, _) => friends.contains(userId) }
          }.toMap[Id[User], Double].withDefaultValue(0d)

          val sortedMutualFriends = mutualFriends.mapValues(_.toSeq.sortBy(-friendshipStrength(_)))

          Some(FriendRecommendations(basicUsers, userConnectionCounts, recommendedUsers, sortedMutualFriends, mutualLibrariesCounts))
        }
    }
    recos.recover {
      case e: Throwable =>
        log.warn(s"[getFriendRecommendations] Failed getting recos", e)
        None
    }
  }

  def loadBasicUsersAndConnectionCounts(idsForUsers: Set[Id[User]], idsForCounts: Set[Id[User]]): (Map[Id[User], BasicUser], Map[Id[User], Int]) = {
    db.readOnlyReplica { implicit session =>
      val basicUsers = basicUserRepo.loadAll(idsForUsers)
      val connectionCounts = userConnectionRepo.getConnectionCounts(idsForCounts)
      (basicUsers, connectionCounts)
    }
  }

  def reNormalizedUsername(readOnly: Boolean, max: Int): Int = {
    var counter = 0
    val batchSize = 50
    var page = 0
    var batch: Seq[User] = db.readOnlyMaster { implicit s =>
      val batch = userRepo.page(page, batchSize)
      page += 1
      batch
    }
    while (batch.nonEmpty && counter < max) {
      batch.map { user =>
        user.primaryUsername.foreach { primaryUsername =>
          val renormalizedUsermame = Username(HandleOps.normalize(primaryUsername.original.value))
          if (primaryUsername.normalized != renormalizedUsermame) {
            log.info(s"[readOnly = $readOnly] [#$counter/P$page] setting user ${user.id.get} ${user.fullName} with username $renormalizedUsermame")
            db.readWrite { implicit s => userRepo.save(user.copy(primaryUsername = Some(primaryUsername.copy(normalized = renormalizedUsermame)))) }
            counter += 1
            if (counter >= max) return counter
          } else {
            log.info(s"username normalization did not change: ${primaryUsername.normalized}")
          }
        }
      }
      batch = db.readOnlyMaster { implicit s =>
        val batch = userRepo.page(page, batchSize)
        page += 1
        batch
      }
    }
    counter
  }

  def getByExternalIds(externalIds: Seq[ExternalId[User]]): Map[ExternalId[User], User] = {
    db.readOnlyReplica { implicit session => userRepo.getAllUsersByExternalId(externalIds.toSet) }
  }
  def getByExternalId(externalId: ExternalId[User]): User = {
    db.readOnlyReplica { implicit session => userRepo.getAllUsersByExternalId(Set(externalId)).values.head }
  }

  private val fakeUsers = new RequestConsolidator[AllFakeUsersKey.type, Set[Id[User]]](5.minutes)
  def getAllFakeUsers(): Set[Id[User]] = {
    val fakesF = fakeUsers(AllFakeUsersKey) { _ =>
      import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
      val fakes = allFakeUsersCache.getOrElse(AllFakeUsersKey) {
        db.readOnlyReplica { implicit session =>
          userExperimentRepo.getByType(UserExperimentType.FAKE).map(_.userId).toSet ++ experimentRepo.getUserIdsByExperiment(UserExperimentType.AUTO_GEN)
        }
      }
      Future.successful(fakes)
    }
    // ↓↓↓ Not actually contributing to badness. We just need the API here to conform to RequestConsolidator's AND this synchronous API.
    // However, since I have you, please don't use this method. Figure out how fake user data got in your data set and restrict it here.
    // No one likes cleaning up fake users, so this slows the system down for everyone.
    Await.result(fakesF, 10.seconds)
  }
}
