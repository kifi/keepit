package com.keepit.commanders

import akka.actor.Scheduler
import com.google.inject.{ Provider, Inject }
import com.keepit.abook.ABookServiceClient
import com.keepit.commanders.HandleCommander.{ UnavailableHandleException, InvalidHandleException }
import com.keepit.commanders.emails.{ ContactJoinedEmailSender, WelcomeEmailSender, EmailSenderProvider }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.TransactionalCaching
import com.keepit.common.core._
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ EmailAddress, _ }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time._
import com.keepit.common.usersegment.{ UserSegment, UserSegmentFactory }
import com.keepit.eliza.{ UserPushNotificationCategory, PushNotificationExperiment, ElizaServiceClient }
import com.keepit.graph.GraphServiceClient
import com.keepit.heimdal.{ ContextStringData, HeimdalServiceClient, _ }
import com.keepit.model.{ UserEmailAddress, _ }
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.SocialContactJoined
import com.keepit.search.SearchServiceClient
import com.keepit.social.{ BasicUser, SocialNetworks, UserIdentity }
import com.keepit.typeahead.{ KifiUserTypeahead, SocialUserTypeahead, TypeaheadHit }
import com.kifi.macros.json
import play.api.libs.json._
import play.api.libs.functional.syntax._
import securesocial.core.{ Identity, Registry, UserService }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util._

case class BasicSocialUser(network: String, profileUrl: Option[String], pictureUrl: Option[String])
object BasicSocialUser {
  implicit val writesBasicSocialUser = Json.writes[BasicSocialUser]
  def from(sui: SocialUserInfo): BasicSocialUser =
    BasicSocialUser(network = sui.networkType.name, profileUrl = sui.getProfileUrl, pictureUrl = sui.getPictureUrl())
}

case class EmailInfo(address: EmailAddress, isPrimary: Boolean, isVerified: Boolean, isPendingPrimary: Boolean)
object EmailInfo {
  implicit val format = new Format[EmailInfo] {
    def reads(json: JsValue): JsResult[EmailInfo] = {
      Try(new EmailInfo(
        (json \ "address").as[EmailAddress],
        (json \ "isPrimary").asOpt[Boolean].getOrElse(false),
        (json \ "isVerified").asOpt[Boolean].getOrElse(false),
        (json \ "isPendingPrimary").asOpt[Boolean].getOrElse(false)
      )).toOption match {
        case Some(ei) => JsSuccess(ei)
        case None => JsError()
      }
    }

    def writes(ei: EmailInfo): JsValue = {
      Json.obj("address" -> ei.address, "isPrimary" -> ei.isPrimary, "isVerified" -> ei.isVerified, "isPendingPrimary" -> ei.isPendingPrimary)
    }
  }
}

case class UpdatableUserInfo(
  biography: Option[String], emails: Option[Seq[EmailInfo]],
  firstName: Option[String] = None, lastName: Option[String] = None)

object UpdatableUserInfo {
  implicit val updatableUserDataFormat = Json.format[UpdatableUserInfo]
}

case class BasicUserInfo(basicUser: BasicUser, info: UpdatableUserInfo, notAuthed: Seq[String], numLibraries: Int, numConnections: Int, numFollowers: Int, orgs: Seq[OrganizationInfo], pendingOrgs: Seq[OrganizationInfo])

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
    (__ \ 'pendingOrgs).write[Set[OrganizationInfo]]
  )(unlift(UserProfileStats.unapply))
}

case class UserNotFoundException(username: Username) extends Exception(username.toString)

class UserCommander @Inject() (
    db: Database,
    userRepo: UserRepo,
    handleCommander: HandleCommander,
    userCredRepo: UserCredRepo,
    emailRepo: UserEmailAddressRepo,
    userValueRepo: UserValueRepo,
    userConnectionRepo: UserConnectionRepo,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    libraryRepo: LibraryRepo,
    organizationCommander: OrganizationCommander,
    organizationMembershipCommander: OrganizationMembershipCommander,
    organizationInviteCommander: OrganizationInviteCommander,
    organizationMembershipRepo: OrganizationMembershipRepo,
    organizationInviteRepo: OrganizationInviteRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    collectionCommander: CollectionCommander,
    abookServiceClient: ABookServiceClient,
    graphServiceClient: GraphServiceClient,
    postOffice: LocalPostOffice,
    clock: Clock,
    scheduler: Scheduler,
    socialUserTypeahead: SocialUserTypeahead,
    kifiUserTypeahead: KifiUserTypeahead,
    elizaServiceClient: ElizaServiceClient,
    searchClient: SearchServiceClient,
    s3ImageStore: S3ImageStore,
    heimdalClient: HeimdalServiceClient,
    userImageUrlCache: UserImageUrlCache,
    libraryMembershipRepo: LibraryMembershipRepo,
    friendStatusCommander: FriendStatusCommander,
    userEmailAddressCommander: UserEmailAddressCommander,
    welcomeEmailSender: Provider[WelcomeEmailSender],
    contactJoinedEmailSender: Provider[ContactJoinedEmailSender],
    usernameCache: UsernameCache,
    userExperimentRepo: UserExperimentRepo,
    allFakeUsersCache: AllFakeUsersCache,
    kifiInstallationCommander: KifiInstallationCommander,
    implicit val executionContext: ExecutionContext,
    experimentRepo: UserExperimentRepo,
    airbrake: AirbrakeNotifier) extends Logging { self =>

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

  def setSettings(userId: Id[User], newSettings: Map[UserValueName, JsValue]) = {
    db.readWrite { implicit s =>
      var settings = userValueRepo.getValue(userId, UserValues.userProfileSettings).as[JsObject]
      newSettings.collect {
        case (UserValueName(name), valueToSet) =>
          settings = settings ++ Json.obj(name -> valueToSet)
      }
      userValueRepo.setValue(userId, UserValueName.USER_PROFILE_SETTINGS, settings)
    }
  }

  def updateUserBiography(userId: Id[User], biography: String): Unit = {
    db.readWrite { implicit session =>
      val trimmed = biography.trim
      if (trimmed != "") {
        userValueRepo.setValue(userId, UserValueName.USER_DESCRIPTION, trimmed)
      } else {
        userValueRepo.clearValue(userId, UserValueName.USER_DESCRIPTION)
      }
      userRepo.save(userRepo.getNoCache(userId)) // update user index sequence number
    }
  }

  def updateUserInfo(userId: Id[User], userData: UpdatableUserInfo): Unit = {
    db.readOnlyMaster { implicit session =>
      val user = userRepo.getNoCache(userId)

      userData.emails.foreach(updateEmailAddresses(userId, user.firstName, _))
      userData.biography.foreach(updateUserBiography(userId, _))

      if (userData.firstName.exists(_.nonEmpty) && userData.lastName.exists(_.nonEmpty)) {
        updateUserNames(user, userData.firstName.get, userData.lastName.get)
      }
    }
  }

  def updateUserNames(user: User, newFirstName: String, newLastName: String): User = {
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

  // todo(Léo): this method isn't resilient to intermediate failures, should be made idempotent and atomic (and confirmation email can be sent async)
  def addEmail(userId: Id[User], address: EmailAddress, isPrimary: Boolean): Future[Either[String, Unit]] = {
    db.readWrite { implicit session =>
      userEmailAddressCommander.intern(userId, address)
    } match {
      case Success((emailAddr, true)) =>
        db.readWrite { implicit session =>
          if (isPrimary && !userEmailAddressCommander.isPrimaryEmail(emailAddr)) {
            userEmailAddressCommander.setAsPrimaryEmail(emailAddr)
          }
        }

        if (!emailAddr.verified && !emailAddr.verificationSent) {
          userEmailAddressCommander.sendVerificationEmail(emailAddr).imap(Right(_))
        } else Future.successful(Right(()))
      case Success((_, false)) => Future.successful(Left("email already added"))
      case Failure(_: UnavailableEmailAddressException) => Future.successful(Left("permission_denied"))
      case Failure(error) => Future.failed(error)
    }
  }

  def makeEmailPrimary(userId: Id[User], address: EmailAddress): Either[String, Unit] = {
    db.readWrite { implicit session =>
      emailRepo.getByAddressAndUser(userId, address) match {
        case Some(emailRecord) => Right {
          if (!userEmailAddressCommander.isPrimaryEmail(emailRecord)) {
            userEmailAddressCommander.setAsPrimaryEmail(emailRecord)
          }
        }
        case _ => Left("unknown_email")
      }
    }
  }

  def removeEmail(userId: Id[User], address: EmailAddress): Either[String, Unit] = {
    db.readWrite { implicit session =>
      emailRepo.getByAddressAndUser(userId, address) match {
        case Some(email) => userEmailAddressCommander.deactivate(email) match {
          case Success(_) => Right(())
          case Failure(_: LastEmailAddressException) => Left("last email")
          case Failure(_: LastVerifiedEmailAddressException) => Left("last verified email")
          case Failure(_: PrimaryEmailAddressException) => Left("trying to remove primary email")
          case Failure(unknownError) => throw unknownError
        }
        case _ => Left("email not found")
      }
    }
  }

  def socialNetworkInfo(userId: Id[User]) = db.readOnlyMaster { implicit s =>
    socialUserInfoRepo.getByUser(userId).map(BasicSocialUser.from)
  }

  def getGmailABookInfos(userId: Id[User]) = abookServiceClient.getABookInfos(userId).map(_.filter(_.origin == ABookOrigins.GMAIL))

  def uploadContactsProxy(userId: Id[User], origin: ABookOriginType, payload: JsValue): Future[Try[ABookInfo]] = {
    abookServiceClient.uploadContacts(userId, origin, payload)
  }

  def getUserInfo(user: User): BasicUserInfo = {
    val (basicUser, biography, emails, pendingPrimary, notAuthed, numLibraries, numConnections, numFollowers, orgInfos, pendingOrgInfos) = db.readOnlyMaster { implicit session =>
      val basicUser = basicUserRepo.load(user.id.get)
      val biography = userValueRepo.getValueStringOpt(user.id.get, UserValueName.USER_DESCRIPTION)
      val emails = emailRepo.getAllByUser(user.id.get).map { e => (e, userEmailAddressCommander.isPrimaryEmail(e)) }
      val pendingPrimary = userValueRepo.getValueStringOpt(user.id.get, UserValueName.PENDING_PRIMARY_EMAIL).map(EmailAddress(_))
      val notAuthed = socialUserInfoRepo.getNotAuthorizedByUser(user.id.get).map(_.networkType.name).filter(_ != "linkedin") // Don't send down LinkedIn anymore

      val libCounts = libraryMembershipRepo.countsWithUserIdAndAccesses(user.id.get, LibraryAccess.all.toSet)
      val numLibsOwned = libCounts.getOrElse(LibraryAccess.OWNER, 0)
      val numLibsCollab = libCounts.getOrElse(LibraryAccess.READ_WRITE, 0)
      val numLibraries = numLibsOwned + numLibsCollab

      val numConnections = userConnectionRepo.getConnectionCount(user.id.get)
      val numFollowers = libraryMembershipRepo.countFollowersForOwner(user.id.get)

      val orgs = organizationMembershipRepo.getByUserId(user.id.get, Limit(Int.MaxValue), Offset(0)).map(_.organizationId)
      val orgInfos = orgs.map(orgId => organizationCommander.getOrganizationInfo(orgId, user.id))

      val pendingOrgs = organizationInviteRepo.getByInviteeIdAndDecision(user.id.get, InvitationDecision.PENDING).map(_.organizationId)
      val pendingOrgInfos = pendingOrgs.map(orgId => organizationCommander.getOrganizationInfo(orgId, user.id)).toSeq

      (basicUser, biography, emails, pendingPrimary, notAuthed, numLibraries, numConnections, numFollowers, orgInfos, pendingOrgInfos)
    }

    val emailInfos = emails.sortBy { case (e, isPrimary) => (isPrimary, !e.verified, e.id.get.id) }.reverse.map {
      case (email, isPrimary) =>
        EmailInfo(
          address = email.address,
          isVerified = email.verified,
          isPrimary = isPrimary,
          isPendingPrimary = pendingPrimary.isDefined && pendingPrimary.get.equalsIgnoreCase(email.address)
        )
    }
    BasicUserInfo(basicUser, UpdatableUserInfo(biography, Some(emailInfos)), notAuthed, numLibraries, numConnections, numFollowers, orgInfos, pendingOrgInfos)
  }

  def getHelpRankInfo(userId: Id[User]): Future[UserKeepAttributionInfo] = {
    heimdalClient.getKeepAttributionInfo(userId)
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
    if (!db.readOnlyMaster { implicit session => userValueRepo.getValueStringOpt(newUserId, UserValueName.CONTACTS_NOTIFIED_ABOUT_JOINING).exists(_ == "true") }) {

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

            elizaServiceClient.sendGlobalNotification( //push sent
              userIds = toNotify,
              title = s"${newUser.firstName} ${newUser.lastName} joined Kifi!",
              body = s"To discover ${newUser.firstName}’s public keeps while searching, get connected! Invite ${newUser.firstName} to connect on Kifi »",
              linkText = s"Invite ${newUser.firstName} to connect",
              linkUrl = s"https://www.kifi.com/${newUser.username.value}?intent=connect",
              imageUrl = s3ImageStore.avatarUrlByUser(newUser),
              sticky = false,
              category = NotificationCategory.User.CONTACT_JOINED
            ) map { _ =>
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
              }
            toNotify.foreach { userId =>
              elizaServiceClient.sendNotificationEvent(SocialContactJoined(
                Recipient(userId),
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

  def sendWelcomeEmail(newUser: User, withVerification: Boolean = false, targetEmailOpt: Option[EmailAddress] = None, isPlainEmail: Boolean = true): Future[Unit] = {
    if (!db.readOnlyMaster { implicit session => userValueRepo.getValue(newUser.id.get, UserValues.welcomeEmailSent) }) {
      val emailF = welcomeEmailSender.get.apply(newUser.id.get, targetEmailOpt, isPlainEmail)
      emailF.map { email =>
        db.readWrite { implicit rw => userValueRepo.setValue(newUser.id.get, UserValues.welcomeEmailSent.name, true) }
        ()
      }
    } else Future.successful(())
  }

  private def setNewPassword(userId: Id[User], sui: SocialUserInfo, newPassword: String): Identity = {
    val pwdInfo = Registry.hashers.currentHasher.hash(newPassword)
    val savedIdentity = UserService.save(UserIdentity(
      userId = sui.userId,
      socialUser = sui.credentials.get.copy(passwordInfo = Some(pwdInfo))
    ))
    val updatedCred = db.readWrite { implicit session =>
      userCredRepo.findByUserIdOpt(userId) map { userCred =>
        userCredRepo.save(userCred.withCredentials(pwdInfo.password))
      }
    }
    log.info(s"[doChangePassword] UserCreds updated=${updatedCred.map(c => s"id=${c.id} userId=${c.userId}")}")
    savedIdentity
  }

  def doChangePassword(userId: Id[User], oldPassword: Option[String], newPassword: String): Try[Identity] = Try {
    val (sfi, hasNoPassword) = db.readOnlyMaster { implicit session =>
      val sfi = socialUserInfoRepo.getByUser(userId).find(_.networkType == SocialNetworks.FORTYTWO)
      val hasNoPassword = userValueRepo.getValue(userId, UserValues.hasNoPassword)
      (sfi, hasNoPassword)
    }
    val resOpt = sfi map { sui =>
      if (hasNoPassword) {
        val identity = setNewPassword(userId, sui, newPassword)
        db.readWrite { implicit s =>
          userValueRepo.setValue(userId, UserValueName.HAS_NO_PASSWORD, false)
        }
        identity
      } else if (oldPassword.nonEmpty) {
        val hasher = Registry.hashers.currentHasher
        val identity = sui.credentials.get
        if (hasher.matches(identity.passwordInfo.get, oldPassword.get)) {
          setNewPassword(userId, sui, newPassword)
        } else {
          log.warn(s"[doChangePassword($userId)] oldPwd=${oldPassword.get} newPwd=$newPassword pwd=${identity.passwordInfo.get}")
          throw new IllegalArgumentException("bad_old_password")
        }
      } else {
        throw new IllegalArgumentException("empty_password_and_nonSocialSignup")
      }
    }
    resOpt getOrElse { throw new IllegalArgumentException("no_user") }
  }

  implicit val hitOrdering = TypeaheadHit.defaultOrdering[SocialUserBasicInfo]

  def sendCloseAccountEmail(userId: Id[User], comment: String): ElectronicMail = {
    val safeComment = comment.replaceAll("[<>]+", "")
    db.readWrite { implicit s =>
      postOffice.sendMail(ElectronicMail(
        from = SystemEmailAddress.ENG,
        to = Seq(SystemEmailAddress.SUPPORT),
        subject = s"Close Account for $userId",
        htmlBody = s"User $userId requested to close account.<br/>---<br/>$safeComment",
        category = NotificationCategory.System.ADMIN
      ))
    }
  }

  def delay(f: => Unit) = {
    import scala.concurrent.duration._
    scheduler.scheduleOnce(5 minutes) {
      f
    }
  }

  @deprecated(message = "use addEmail/modifyEmail/removeEmail", since = "2014-08-20")
  def updateEmailAddresses(userId: Id[User], firstName: String, emails: Seq[EmailInfo]): Unit = {
    db.readWrite { implicit session =>
      val uniqueEmails = emails.map(_.address).toSet
      val (existing, toRemove) = emailRepo.getAllByUser(userId).partition(em => uniqueEmails contains em.address)

      // Add new emails
      val added = (uniqueEmails -- existing.map(_.address)).map { address =>
        userEmailAddressCommander.intern(userId, address).get._1 tap { addedEmail =>
          session.onTransactionSuccess(userEmailAddressCommander.sendVerificationEmail(addedEmail))
        }
      }

      // Set the correct email as primary
      (added ++ existing).foreach { emailRecord =>
        val isPrimary = emails.exists { emailInfo => (emailInfo.address == emailRecord.address) && (emailInfo.isPrimary || emailInfo.isPendingPrimary) }
        if (isPrimary && !userEmailAddressCommander.isPrimaryEmail(emailRecord)) {
          userEmailAddressCommander.setAsPrimaryEmail(emailRecord)
        }
      }

      // Remove missing emails
      toRemove.foreach(userEmailAddressCommander.deactivate(_))
    }
  }

  def getUserImageUrl(userId: Id[User], width: Int): Future[String] = {
    val user = db.readOnlyMaster { implicit session => userRepo.get(userId) }
    val imageName = user.pictureName.getOrElse("0")
    implicit val txn = TransactionalCaching.Implicits.directCacheAccess
    userImageUrlCache.getOrElseFuture(UserImageUrlCacheKey(userId, width, imageName)) {
      s3ImageStore.getPictureUrl(Some(width), user, imageName)
    }
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

  private def readPrefs(prefSet: Set[UserValueName], userId: Id[User]): JsObject = {
    // Reading from master because the value may have been updated just before
    val values = db.readOnlyMaster { implicit s =>
      userValueRepo.getValues(userId, prefSet.toSeq: _*)
    }
    JsObject(prefSet.toSeq.map { name =>
      name.name -> values(name).map(value => {
        if (value == "false") JsBoolean(false)
        else if (value == "true") JsBoolean(true)
        else if (value == "null") JsNull
        else JsString(value)
      }).getOrElse(JsNull)
    })
  }

  def getPrefs(prefSet: Set[UserValueName], userId: Id[User], experiments: Set[UserExperimentType]): Future[JsObject] = {
    getPrefUpdates(prefSet, userId, experiments) map { updates =>
      savePrefs(userId, updates)
    } recover {
      case t: Throwable => airbrake.notify(s"Error updating prefs for user $userId", t)
    } map { _ =>
      readPrefs(prefSet, userId)
    }
  }

  def savePrefs(userId: Id[User], o: Map[UserValueName, JsValue]) = {
    db.readWrite(attempts = 3) { implicit s =>
      o.map {
        case (name, JsNull) => userValueRepo.clearValue(userId, name)
        case (name, _: JsUndefined) => userValueRepo.clearValue(userId, name)
        case (name, JsString(value)) => userValueRepo.setValue(userId, name, value)
        case (name, value) => userValueRepo.setValue(userId, name, value.toString)
      }
    }
  }

  val DELIGHTED_MIN_INTERVAL = 60 // days
  val DELIGHTED_INITIAL_DELAY = 14 // days

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

  def importSocialEmail(userId: Id[User], emailAddress: EmailAddress): UserEmailAddress = {
    db.readWrite { implicit s =>
      userEmailAddressCommander.intern(userId, emailAddress, verified = true) match {
        case Success((email, _)) => email
        case Failure(error) => throw error
      }
    }
  }

  def getFriendRecommendations(userId: Id[User], offset: Int, limit: Int): Future[Option[FriendRecommendations]] = {
    val futureRecommendedUsers = abookServiceClient.getFriendRecommendations(userId, offset, limit)
    val futureRelatedUsers = graphServiceClient.getSociallyRelatedEntitiesForUser(userId)
    futureRecommendedUsers.flatMap {
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

  def getUserByUsername(username: Username): Option[(User, Boolean)] = {
    db.readOnlyMaster { implicit session =>
      handleCommander.getByHandle(username).collect {
        case (Right(user), isPrimary) if user.state == UserStates.ACTIVE =>
          (user, isPrimary)
      }
    }
  }

  def getByExternalIds(externalIds: Seq[ExternalId[User]]): Map[ExternalId[User], User] = {
    db.readOnlyReplica { implicit session => userRepo.getAllUsersByExternalId(externalIds) }
  }
  def getByExternalId(externalId: ExternalId[User]): User = {
    db.readOnlyReplica { implicit session => userRepo.getAllUsersByExternalId(Seq(externalId)).values.head }
  }

  def getAllFakeUsers(): Set[Id[User]] = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
    allFakeUsersCache.getOrElse(AllFakeUsersKey) {
      db.readOnlyReplica { implicit session =>
        userExperimentRepo.getByType(UserExperimentType.FAKE).map(_.userId).toSet ++ experimentRepo.getUserIdsByExperiment(UserExperimentType.AUTO_GEN)
      }
    }
  }
}
