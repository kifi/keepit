package com.keepit.commanders

import akka.actor.Scheduler
import com.keepit.common.core._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.S3ImageStore
import com.keepit.common.akka.SafeFuture
import com.keepit.common.mail._
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.social.BasicUserRepo
import com.keepit.social.{ UserIdentity, SocialNetworks }
import com.keepit.common.usersegment.UserSegment
import com.keepit.common.usersegment.UserSegmentFactory
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.abook.ABookServiceClient
import com.keepit.social.{ BasicUser, SocialGraphPlugin, SocialNetworkType }
import com.keepit.common.time._
import com.keepit.common.performance.timing
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ ContextStringData, HeimdalServiceClient, HeimdalContextBuilder }
import com.keepit.heimdal._
import com.keepit.search.SearchServiceClient
import com.keepit.typeahead.PrefixFilter
import com.keepit.typeahead.PrefixMatching
import com.keepit.typeahead.TypeaheadHit
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.{ Inject, Provider }
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.{ Left, Right, Try }
import securesocial.core.{ Identity, UserService, Registry }
import com.keepit.inject.FortyTwoConfig
import com.keepit.typeahead.{ KifiUserTypeahead, SocialUserTypeahead }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.model.SocialConnection
import play.api.libs.json.JsString
import com.keepit.model.SocialUserInfoUserKey
import scala.Some
import com.keepit.model.UserEmailAddress
import com.keepit.inject.FortyTwoConfig
import com.keepit.social.UserIdentity
import play.api.libs.json.JsSuccess
import com.keepit.model.SocialUserConnectionsKey
import com.keepit.common.mail.EmailAddress
import play.api.libs.json.JsObject
import com.keepit.common.cache.TransactionalCaching
import com.keepit.commanders.emails.{ EmailConfirmationSender, EmailSenderProvider, ContactJoinedEmailSender, FriendRequestEmailSender, FriendConnectionMadeEmailSender, EmailOptOutCommander }
import com.keepit.common.db.slick.Database.Replica

case class BasicSocialUser(network: String, profileUrl: Option[String], pictureUrl: Option[String])
object BasicSocialUser {
  implicit val writesBasicSocialUser = Json.writes[BasicSocialUser]
  def from(sui: SocialUserInfo): BasicSocialUser =
    BasicSocialUser(network = sui.networkType.name, profileUrl = sui.getProfileUrl, pictureUrl = sui.getPictureUrl())
}

case class ConnectionInfo(user: BasicUser, userId: Id[User], unfriended: Boolean, unsearched: Boolean)

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
  description: Option[String], emails: Option[Seq[EmailInfo]],
  firstName: Option[String] = None, lastName: Option[String] = None)
object UpdatableUserInfo {
  implicit val updatableUserDataFormat = Json.format[UpdatableUserInfo]
}

case class BasicUserInfo(basicUser: BasicUser, info: UpdatableUserInfo, notAuthed: Seq[String])

class UserCommander @Inject() (
    db: Database,
    userRepo: UserRepo,
    userCredRepo: UserCredRepo,
    emailRepo: UserEmailAddressRepo,
    userValueRepo: UserValueRepo,
    userConnectionRepo: UserConnectionRepo,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    userExperimentCommander: LocalUserExperimentCommander,
    socialUserInfoRepo: SocialUserInfoRepo,
    socialConnectionRepo: SocialConnectionRepo,
    socialUserRepo: SocialUserInfoRepo,
    invitationRepo: InvitationRepo,
    friendRequestRepo: FriendRequestRepo,
    searchFriendRepo: SearchFriendRepo,
    userCache: SocialUserInfoUserCache,
    socialUserConnectionsCache: SocialUserConnectionsCache,
    socialGraphPlugin: SocialGraphPlugin,
    bookmarkCommander: KeepsCommander,
    collectionCommander: CollectionCommander,
    abookServiceClient: ABookServiceClient,
    postOffice: LocalPostOffice,
    clock: Clock,
    scheduler: Scheduler,
    socialUserTypeahead: SocialUserTypeahead,
    kifiUserTypeahead: KifiUserTypeahead,
    elizaServiceClient: ElizaServiceClient,
    searchClient: SearchServiceClient,
    s3ImageStore: S3ImageStore,
    emailOptOutCommander: EmailOptOutCommander,
    heimdalClient: HeimdalServiceClient,
    fortytwoConfig: FortyTwoConfig,
    userImageUrlCache: UserImageUrlCache,
    libraryCommander: LibraryCommander,
    emailSender: EmailSenderProvider,
    airbrake: AirbrakeNotifier) extends Logging { self =>

  def updateUserDescription(userId: Id[User], description: String): Unit = {
    db.readWrite { implicit session =>
      val trimmed = description.trim
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

      userData.emails.foreach(updateEmailAddresses(userId, user.firstName, user.primaryEmail, _))
      userData.description.foreach(updateUserDescription(userId, _))

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

  def addEmail(userId: Id[User], address: EmailAddress, isPrimary: Boolean): Future[Either[String, UserEmailAddress]] = {
    db.readWrite { implicit session =>
      if (emailRepo.getByAddressOpt(address).isEmpty) {
        val emailAddr = emailRepo.save(UserEmailAddress(userId = userId, address = address).withVerificationCode(clock.now))
        Some(emailAddr)
      } else {
        None
      }
    } match {
      case Some(emailAddr) =>
        emailSender.confirmation(emailAddr).imap { f =>
          db.readWrite { implicit session =>
            val user = userRepo.get(userId)
            if (user.primaryEmail.isEmpty && isPrimary)
              userValueRepo.setValue(userId, UserValueName.PENDING_PRIMARY_EMAIL, address)
          }
          Right(emailAddr)
        }
      case None => Future.successful(Left("email already added"))
    }
  }
  def makeEmailPrimary(userId: Id[User], address: EmailAddress): Either[String, Unit] = {
    db.readWrite { implicit session =>
      emailRepo.getByAddressOpt(address) match {
        case None => Left("email not found")
        case Some(emailRecord) if emailRecord.userId == userId =>
          val user = userRepo.get(userId)
          if (emailRecord.verified && (user.primaryEmail.isEmpty || user.primaryEmail.get != emailRecord)) {
            updateUserPrimaryEmail(emailRecord)
          } else {
            userValueRepo.setValue(userId, UserValueName.PENDING_PRIMARY_EMAIL, address)
          }
          Right()
      }
    }
  }
  def removeEmail(userId: Id[User], address: EmailAddress): Either[String, Unit] = {
    db.readWrite { implicit session =>
      emailRepo.getByAddressOpt(address) match {
        case None => Left("email not found")
        case Some(email) =>
          val user = userRepo.get(userId)
          val allEmails = emailRepo.getAllByUser(userId)
          val isPrimary = user.primaryEmail.nonEmpty && (user.primaryEmail.get == address)
          val isLast = allEmails.isEmpty
          val isLastVerified = !allEmails.exists(em => em.address != address && em.verified)
          val pendingPrimary = userValueRepo.getValueStringOpt(userId, UserValueName.PENDING_PRIMARY_EMAIL).map(EmailAddress(_))
          if (!isPrimary && !isLast && !isLastVerified) {
            if (pendingPrimary.isDefined && address == pendingPrimary.get) {
              userValueRepo.clearValue(userId, UserValueName.PENDING_PRIMARY_EMAIL)
            }
            emailRepo.save(email.withState(UserEmailAddressStates.INACTIVE))
            Right()
          } else if (isLast) {
            Left("last email")
          } else if (isLastVerified) {
            Left("last verified email")
          } else {
            Left("trying to remove primary email")
          }
      }
    }
  }

  def getConnectionsPage(userId: Id[User], page: Int, pageSize: Int): (Seq[ConnectionInfo], Int) = {
    val infos = db.readOnlyReplica { implicit s =>
      val searchFriends = searchFriendRepo.getSearchFriends(userId)
      val connectionIds = userConnectionRepo.getConnectedUsers(userId)
      val unfriendedIds = userConnectionRepo.getUnfriendedUsers(userId)
      val connections = connectionIds.map(_ -> false).toSeq ++ unfriendedIds.map(_ -> true).toSeq
      connections.map {
        case (friendId, unfriended) =>
          ConnectionInfo(basicUserRepo.load(friendId), friendId, unfriended, searchFriends.contains(friendId))
      }
    }
    (infos.drop(page * pageSize).take(pageSize), infos.filterNot(_.unfriended).size)
  }

  def getFriends(user: User, experiments: Set[ExperimentType]): Set[BasicUser] = {
    val basicUsers = db.readOnlyMaster { implicit s =>
      if (canMessageAllUsers(user.id.get)) {
        userRepo.allExcluding(UserStates.PENDING, UserStates.BLOCKED, UserStates.INACTIVE)
          .collect { case u if u.id.get != user.id.get => BasicUser.fromUser(u) }.toSet
      } else {
        basicUserRepo.loadAll(userConnectionRepo.getConnectedUsers(user.id.get)).values.toSet
      }
    }

    // Apologies for this code. "Personal favor" for Danny. Doing it right should be speced and requires
    // two models, service clients, and caches.
    val iNeededToDoThisIn20Minutes = if (experiments.contains(ExperimentType.ADMIN)) {
      Seq(
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424201"), "FortyTwo Engineering", "", "0.jpg", None),
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424202"), "FortyTwo Family", "", "0.jpg", None),
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424203"), "FortyTwo Product", "", "0.jpg", None)
      )
    } else {
      Seq()
    }

    // This will eventually be a lot more complex. However, for now, tricking the client is the way to go.
    // ^^^^^^^^^ Unrelated to the offensive code above ^^^^^^^^^
    val kifiSupport = Seq(
      BasicUser(ExternalId[User]("aa345838-70fe-45f2-914c-f27c865bdb91"), "Tamila, Kifi Help", "", "tmilz.jpg", None))
    basicUsers ++ iNeededToDoThisIn20Minutes ++ kifiSupport
  }

  private def canMessageAllUsers(userId: Id[User]): Boolean = {
    userExperimentCommander.userHasExperiment(userId, ExperimentType.CAN_MESSAGE_ALL_USERS)
  }

  def socialNetworkInfo(userId: Id[User]) = db.readOnlyMaster { implicit s =>
    socialUserInfoRepo.getByUser(userId).map(BasicSocialUser.from)
  }

  def getGmailABookInfos(userId: Id[User]) = abookServiceClient.getABookInfos(userId).map(_.filter(_.origin == ABookOrigins.GMAIL))

  def uploadContactsProxy(userId: Id[User], origin: ABookOriginType, payload: JsValue): Future[Try[ABookInfo]] = {
    abookServiceClient.uploadContacts(userId, origin, payload)
  }

  def getUserInfo(user: User): BasicUserInfo = {
    val (basicUser, description, emails, pendingPrimary, notAuthed) = db.readOnlyMaster { implicit session =>
      val basicUser = basicUserRepo.load(user.id.get)
      val description = userValueRepo.getValueStringOpt(user.id.get, UserValueName.USER_DESCRIPTION)
      val emails = emailRepo.getAllByUser(user.id.get)
      val pendingPrimary = userValueRepo.getValueStringOpt(user.id.get, UserValueName.PENDING_PRIMARY_EMAIL).map(EmailAddress(_))
      val notAuthed = socialUserRepo.getNotAuthorizedByUser(user.id.get).map(_.networkType.name)
      (basicUser, description, emails, pendingPrimary, notAuthed)
    }

    def isPrimary(address: EmailAddress) = user.primaryEmail.isDefined && address.equalsIgnoreCase(user.primaryEmail.get)
    val emailInfos = emails.sortBy(e => (isPrimary(e.address), !e.verified, e.id.get.id)).map { email =>
      EmailInfo(
        address = email.address,
        isVerified = email.verified,
        isPrimary = isPrimary(email.address),
        isPendingPrimary = pendingPrimary.isDefined && pendingPrimary.get.equalsIgnoreCase(email.address)
      )
    }
    BasicUserInfo(basicUser, UpdatableUserInfo(description, Some(emailInfos)), notAuthed)
  }

  def getKeepAttributionInfo(userId: Id[User]): Future[UserKeepAttributionInfo] = {
    heimdalClient.getKeepAttributionInfo(userId)
  }

  def getUserSegment(userId: Id[User]): UserSegment = {
    val (numBms, numFriends) = db.readOnlyReplica { implicit s => //using cache
      (keepRepo.getCountByUser(userId), userConnectionRepo.getConnectionCount(userId))
    }

    val segment = UserSegmentFactory(numBms, numFriends)
    segment
  }

  def createUser(firstName: String, lastName: String, addrOpt: Option[EmailAddress], state: State[User]) = {
    val newUser = db.readWrite { implicit session =>
      userRepo.save(User(firstName = firstName, lastName = lastName, primaryEmail = addrOpt, state = state, username = None))
    }
    SafeFuture {
      db.readWrite { implicit session =>
        userValueRepo.setValue(newUser.id.get, UserValueName.EXT_SHOW_EXT_MSG_INTRO, true)
      }
      searchClient.warmUpUser(newUser.id.get)
      searchClient.updateUserIndex()
    }

    libraryCommander.internSystemGeneratedLibraries(newUser.id.get)

    newUser
  }

  def tellUsersWithContactOfNewUserImmediate(newUser: User): Option[Future[Set[Id[User]]]] = synchronized {
    require(newUser.id.isDefined, "UserCommander.tellUsersWithContactOfNewUserImmediate: newUser.id is required")

    val newUserId = newUser.id.get
    if (!db.readOnlyMaster { implicit session => userValueRepo.getValueStringOpt(newUserId, UserValueName.CONTACTS_NOTIFIED_ABOUT_JOINING).exists(_ == "true") }) {
      newUser.primaryEmail.map { email =>
        db.readWrite { implicit session => userValueRepo.setValue(newUserId, UserValueName.CONTACTS_NOTIFIED_ABOUT_JOINING, true) }

        // get users who have this user's email in their contacts
        abookServiceClient.getUsersWithContact(email) flatMap {
          case contacts if contacts.size > 0 => {
            val alreadyConnectedUsers = db.readOnlyReplica { implicit session =>
              userConnectionRepo.getConnectedUsers(newUser.id.get)
            }
            // only notify users who are not already connected to our list of users with the contact email
            val toNotify = contacts.diff(alreadyConnectedUsers) - newUserId

            log.info("sending new user contact notifications to: " + toNotify)
            val emailsF = toNotify.map { userId => emailSender.contactJoined(userId, newUserId) }

            elizaServiceClient.sendGlobalNotification(
              userIds = toNotify,
              title = s"${newUser.firstName} ${newUser.lastName} joined Kifi!",
              body = s"To discover ${newUser.firstName}’s public keeps while searching, get connected! Click this to send a friend request to ${newUser.firstName}.",
              linkText = "Kifi Friends",
              linkUrl = "https://www.kifi.com/invite?friend=" + newUser.externalId,
              imageUrl = userAvatarImageUrl(newUser),
              sticky = false,
              category = NotificationCategory.User.CONTACT_JOINED
            )

            Future.sequence(emailsF.toSeq) map (_ => toNotify)
          }
          case _ => {
            log.info("cannot send contact notifications: primary email empty for user.id=" + newUserId)
            Future.successful(Set.empty)
          }
        }
      }
    } else Option(Future.successful(Set.empty))
  }

  def sendWelcomeEmail(newUser: User, withVerification: Boolean = false, targetEmailOpt: Option[EmailAddress] = None): Future[Unit] = {
    if (!db.readOnlyMaster { implicit session => userValueRepo.getValue(newUser.id.get, UserValues.welcomeEmailSent) }) {
      val emailF = emailSender.welcome(newUser.id.get, targetEmailOpt)
      emailF.map { email =>
        db.readWrite { implicit rw => userValueRepo.setValue(newUser.id.get, UserValues.welcomeEmailSent.name, true) }
        ()
      }
    } else Future.successful(())
  }

  def doChangePassword(userId: Id[User], oldPassword: String, newPassword: String): Try[Identity] = Try {
    val resOpt = db.readOnlyMaster { implicit session =>
      socialUserInfoRepo.getByUser(userId).find(_.networkType == SocialNetworks.FORTYTWO)
    } map { sui =>
      val hasher = Registry.hashers.currentHasher
      val identity = sui.credentials.get
      if (!hasher.matches(identity.passwordInfo.get, oldPassword)) {
        log.warn(s"[doChangePassword($userId)] oldPwd=$oldPassword newPwd=$newPassword pwd=${identity.passwordInfo.get}")
        throw new IllegalArgumentException("bad_old_password")
      } else {
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
        log.info(s"[doChangePassword] UserCreds updated=${updatedCred.map(c => s"id=${c.id} userId=${c.userId} login=${c.loginName}")}")
        savedIdentity
      }
    }
    resOpt getOrElse { throw new IllegalArgumentException("no_user") }
  }

  implicit val hitOrdering = TypeaheadHit.defaultOrdering[SocialUserBasicInfo]

  // todo(josh) replace with sender
  private def sendFriendRequestAcceptedEmailAndNotification(myUserId: Id[User], friend: User): Unit = SafeFuture {
    //sending 'friend request accepted' email && Notification
    val (respondingUser, respondingUserImage) = db.readWrite { implicit session =>
      val respondingUser = userRepo.get(myUserId)
      val respondingUserImage = userAvatarImageUrl(respondingUser)
      (respondingUser, respondingUserImage)
    }

    val emailF = emailSender.connectionMade(friend.id.get, myUserId, NotificationCategory.User.FRIEND_ACCEPTED)

    val notifF = elizaServiceClient.sendGlobalNotification(
      userIds = Set(friend.id.get),
      title = s"${respondingUser.firstName} ${respondingUser.lastName} accepted your friend request!",
      body = s"Now you will enjoy ${respondingUser.firstName}'s keeps in your search results and you can message ${respondingUser.firstName} directly.",
      linkText = "Invite more friends to kifi",
      linkUrl = "https://www.kifi.com/friends/invite",
      imageUrl = respondingUserImage,
      sticky = false,
      category = NotificationCategory.User.FRIEND_ACCEPTED
    )

    emailF flatMap (_ => notifF)
  }

  def sendingFriendRequestEmailAndNotification(request: FriendRequest, myUserId: Id[User], recipient: User): Unit = SafeFuture {
    val (requestingUser, requestingUserImage) = db.readWrite { implicit session =>
      val requestingUser = userRepo.get(myUserId)
      val requestingUserImage = s3ImageStore.avatarUrlByExternalId(Some(200), requestingUser.externalId, requestingUser.pictureName.getOrElse("0"), Some("https"))
      (requestingUser, requestingUserImage)
    }

    val emailF = emailSender.friendRequest(recipient.id.get, myUserId)

    val friendReqF = elizaServiceClient.sendGlobalNotification(
      userIds = Set(recipient.id.get),
      title = s"${requestingUser.firstName} ${requestingUser.lastName} sent you a friend request",
      body = s"Enjoy ${requestingUser.firstName}’s keeps in your search results and message ${requestingUser.firstName} directly.",
      linkText = s"Respond to ${requestingUser.firstName}’s friend request",
      linkUrl = "https://www.kifi.com/friends",
      imageUrl = requestingUserImage,
      sticky = false,
      category = NotificationCategory.User.FRIEND_REQUEST
    ) map { id =>
        db.readWrite { implicit session =>
          friendRequestRepo.save(request.copy(messageHandle = Some(id)))
        }
      }

    emailF flatMap (_ => friendReqF)
  }

  def friend(myUserId: Id[User], friendUserId: ExternalId[User]): (Boolean, String) = {
    db.readWrite { implicit s =>
      userRepo.getOpt(friendUserId) map { recipient =>
        val openFriendRequests = friendRequestRepo.getBySender(myUserId, Set(FriendRequestStates.ACTIVE))

        if (openFriendRequests.size > 40) {
          (false, "tooManySent")
        } else if (friendRequestRepo.getBySenderAndRecipient(myUserId, recipient.id.get).isDefined) {
          (true, "alreadySent")
        } else {
          friendRequestRepo.getBySenderAndRecipient(recipient.id.get, myUserId) map { friendReq =>
            for {
              su1 <- socialUserInfoRepo.getByUser(friendReq.senderId).find(_.networkType == SocialNetworks.FORTYTWO)
              su2 <- socialUserInfoRepo.getByUser(friendReq.recipientId).find(_.networkType == SocialNetworks.FORTYTWO)
            } yield {
              socialConnectionRepo.getConnectionOpt(su1.id.get, su2.id.get) match {
                case Some(sc) =>
                  socialConnectionRepo.save(sc.withState(SocialConnectionStates.ACTIVE))
                case None =>
                  socialConnectionRepo.save(SocialConnection(socialUser1 = su1.id.get, socialUser2 = su2.id.get, state = SocialConnectionStates.ACTIVE))
              }
            }
            userConnectionRepo.addConnections(friendReq.senderId, Set(friendReq.recipientId), requested = true)

            elizaServiceClient.sendToUser(friendReq.senderId, Json.arr("new_friends", Set(basicUserRepo.load(friendReq.recipientId))))
            elizaServiceClient.sendToUser(friendReq.recipientId, Json.arr("new_friends", Set(basicUserRepo.load(friendReq.senderId))))
            s.onTransactionSuccess {
              Seq(friendReq.senderId, friendReq.recipientId) foreach { id =>
                socialUserTypeahead.refresh(id)
                kifiUserTypeahead.refresh(id)
              }
            }
            log.info("just made a friend! updating user graph index now.")
            searchClient.updateUserGraph()
            sendFriendRequestAcceptedEmailAndNotification(myUserId, recipient)
            (true, "acceptedRequest")
          } getOrElse {
            val request = friendRequestRepo.save(FriendRequest(senderId = myUserId, recipientId = recipient.id.get, messageHandle = None))
            sendingFriendRequestEmailAndNotification(request, myUserId, recipient)
            (true, "sentRequest")
          }
        }
      } getOrElse {
        (false, s"User with id $myUserId not found.")
      }
    }
  }

  def unfriend(userId: Id[User], id: ExternalId[User]): Boolean = {
    db.readOnlyMaster(attempts = 2) { implicit ro => userRepo.getOpt(id) } exists { user =>
      val success = db.readWrite(attempts = 2) { implicit s =>
        userConnectionRepo.unfriendConnections(userId, user.id.toSet) > 0
      }
      if (success) {
        db.readOnlyReplica { implicit session =>
          elizaServiceClient.sendToUser(userId, Json.arr("lost_friends", Set(basicUserRepo.load(user.id.get))))
          elizaServiceClient.sendToUser(user.id.get, Json.arr("lost_friends", Set(basicUserRepo.load(userId))))
        }
        Seq(userId, user.id.get) foreach { id =>
          socialUserTypeahead.refresh(id)
          kifiUserTypeahead.refresh(id)
        }
        searchClient.updateUserGraph()
      }
      success
    }
  }

  def sendCloseAccountEmail(userId: Id[User], comment: String): ElectronicMail = {
    val safeComment = comment.replaceAll("[<>]+", "")
    db.readWrite { implicit s =>
      postOffice.sendMail(ElectronicMail(
        from = SystemEmailAddress.ENG,
        to = Seq(SystemEmailAddress.SUPPORT),
        subject = s"Close Account for ${userId}",
        htmlBody = s"User ${userId} requested to close account.<br/>---<br/>${safeComment}",
        category = NotificationCategory.System.ADMIN
      ))
    }
  }

  def ignoreFriendRequest(userId: Id[User], id: ExternalId[User]): (Boolean, String) = {
    db.readWrite { implicit s =>
      userRepo.getOpt(id) map { sender =>
        friendRequestRepo.getBySenderAndRecipient(sender.id.get, userId) map { friendRequest =>
          friendRequestRepo.save(friendRequest.copy(state = FriendRequestStates.IGNORED))
          (true, "friend_request_ignored")
        } getOrElse (false, "friend_request_not_found")
      } getOrElse (false, "user_not_found")
    }
  }

  def incomingFriendRequests(userId: Id[User]): Seq[BasicUser] = {
    db.readOnlyMaster(attempts = 2) { implicit ro =>
      friendRequestRepo.getByRecipient(userId) map { fr => basicUserRepo.load(fr.senderId) }
    }
  }

  def outgoingFriendRequests(userId: Id[User]): Seq[BasicUser] = {
    db.readOnlyMaster(attempts = 2) { implicit ro =>
      friendRequestRepo.getBySender(userId) map { fr => basicUserRepo.load(fr.recipientId) }
    }
  }

  def disconnect(userId: Id[User], networkString: String): (Option[SocialUserInfo], String) = {
    val network = SocialNetworkType(networkString)
    val (thisNetwork, otherNetworks) = db.readOnlyMaster { implicit s =>
      userCache.remove(SocialUserInfoUserKey(userId))
      socialUserInfoRepo.getByUser(userId).partition(_.networkType == network)
    }
    if (otherNetworks.isEmpty) {
      (None, "no_other_connected_network")
    } else if (thisNetwork.isEmpty || thisNetwork.head.networkType == SocialNetworks.FORTYTWO) {
      (None, "not_connected_to_network")
    } else {
      val sui = thisNetwork.head
      socialGraphPlugin.asyncRevokePermissions(sui)
      db.readWrite { implicit s =>
        socialConnectionRepo.deactivateAllConnections(sui.id.get)
        socialUserInfoRepo.invalidateCache(sui)
        socialUserInfoRepo.save(sui.copy(credentials = None, userId = None))
        socialUserInfoRepo.getByUser(userId).map(socialUserInfoRepo.invalidateCache)
        userCache.remove(SocialUserInfoUserKey(userId))
      }
      val newLoginUser = otherNetworks.find(_.networkType == SocialNetworks.FORTYTWO).getOrElse(otherNetworks.head)
      (Some(newLoginUser), "disconnected")
    }
  }

  def includeFriend(userId: Id[User], id: ExternalId[User]): Option[Boolean] = {
    db.readWrite { implicit s =>
      val friendIdOpt = userRepo.getOpt(id) collect {
        case user if userConnectionRepo.getConnectionOpt(userId, user.id.get).isDefined => user.id.get
      }
      friendIdOpt map { friendId =>
        val changed = searchFriendRepo.includeFriend(userId, friendId)
        log.info(s"[includeFriend($userId,$id)] friendId=$friendId changed=$changed")
        searchClient.updateSearchFriendGraph()
        changed
      }
    }
  }

  def excludeFriend(userId: Id[User], id: ExternalId[User]): Option[Boolean] = {
    db.readWrite { implicit s =>
      val friendIdOpt = userRepo.getOpt(id) collect {
        case user if userConnectionRepo.getConnectionOpt(userId, user.id.get).isDefined => user.id.get
      }
      friendIdOpt map { friendId =>
        val changed = searchFriendRepo.excludeFriend(userId, friendId)
        log.info(s"[excludeFriend($userId, $id)] friendId=$friendId changed=$changed")
        searchClient.updateSearchFriendGraph()
        changed
      }
    }
  }

  def delay(f: => Unit) = {
    import scala.concurrent.duration._
    scheduler.scheduleOnce(5 minutes) {
      f
    }
  }

  @deprecated(message = "use addEmail/modifyEmail/removeEmail", since = "2014-08-20")
  def updateEmailAddresses(userId: Id[User], firstName: String, primaryEmail: Option[EmailAddress], emails: Seq[EmailInfo]): Unit = {
    db.readWrite { implicit session =>
      val pendingPrimary = userValueRepo.getValueStringOpt(userId, UserValueName.PENDING_PRIMARY_EMAIL).map(EmailAddress(_))
      val uniqueEmails = emails.map(_.address).toSet
      val (existing, toRemove) = emailRepo.getAllByUser(userId).partition(em => uniqueEmails contains em.address)
      // Remove missing emails
      for (email <- toRemove) {
        val isPrimary = primaryEmail.isDefined && (primaryEmail.get == email.address)
        val isLast = existing.isEmpty
        val isLastVerified = !existing.exists(em => em != email && em.verified)
        if (!isPrimary && !isLast && !isLastVerified) {
          if (pendingPrimary.isDefined && email.address == pendingPrimary.get) {
            userValueRepo.clearValue(userId, UserValueName.PENDING_PRIMARY_EMAIL)
          }
          emailRepo.save(email.withState(UserEmailAddressStates.INACTIVE))
        }
      }
      // Add new emails
      for (address <- uniqueEmails -- existing.map(_.address)) {
        if (emailRepo.getByAddressOpt(address).isEmpty) {
          val emailAddr = emailRepo.save(UserEmailAddress(userId = userId, address = address).withVerificationCode(clock.now))
          emailSender.confirmation(emailAddr)
        }
      }
      // Set the correct email as primary
      for (emailInfo <- emails) {
        if (emailInfo.isPrimary || emailInfo.isPendingPrimary) {
          val emailRecordOpt = emailRepo.getByAddressOpt(emailInfo.address)
          emailRecordOpt.collect {
            case emailRecord if emailRecord.userId == userId =>
              if (emailRecord.verified) {
                if (primaryEmail.isEmpty || primaryEmail.get != emailRecord.address) {
                  updateUserPrimaryEmail(emailRecord)
                }
              } else {
                userValueRepo.setValue(userId, UserValueName.PENDING_PRIMARY_EMAIL, emailInfo.address)
              }
          }
        }
      }

      userValueRepo.getValueStringOpt(userId, UserValueName.PENDING_PRIMARY_EMAIL).map { pp =>
        emailRepo.getByAddressOpt(EmailAddress(pp)) match {
          case Some(em) =>
            if (em.verified && em.address == pp) {
              updateUserPrimaryEmail(em)
            }
          case None => userValueRepo.clearValue(userId, UserValueName.PENDING_PRIMARY_EMAIL)
        }
      }
    }
  }

  def updateUserPrimaryEmail(primaryEmail: UserEmailAddress)(implicit session: RWSession) = {
    require(primaryEmail.verified, s"Suggested primary email $primaryEmail is not verified")
    userValueRepo.clearValue(primaryEmail.userId, UserValueName.PENDING_PRIMARY_EMAIL)
    val currentUser = userRepo.get(primaryEmail.userId)
    userRepo.save(currentUser.copy(primaryEmail = Some(primaryEmail.address)))
    heimdalClient.setUserProperties(primaryEmail.userId, "$email" -> ContextStringData(primaryEmail.address.address))
  }

  def getUserImageUrl(userId: Id[User], width: Int): Future[String] = {
    val user = db.readOnlyMaster { implicit session => userRepo.get(userId) }
    val imageName = user.pictureName.getOrElse("0")
    implicit val txn = TransactionalCaching.Implicits.directCacheAccess
    userImageUrlCache.getOrElseFuture(UserImageUrlCacheKey(userId, width, imageName)) {
      s3ImageStore.getPictureUrl(Some(width), user, imageName)
    }
  }

  private def getPrefUpdates(prefSet: Set[UserValueName], userId: Id[User], experiments: Set[ExperimentType]): Future[Map[UserValueName, JsValue]] = {
    if (prefSet.contains(UserValueName.SHOW_DELIGHTED_QUESTION)) {
      // Check if user should be shown Delighted question
      val user = db.readOnlyMaster { implicit s =>
        userRepo.get(userId)
      }
      val time = clock.now()
      val shouldShowDelightedQuestionFut = if (experiments.contains(ExperimentType.DELIGHTED_SURVEY_PERMANENT)) {
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

  def getPrefs(prefSet: Set[UserValueName], userId: Id[User], experiments: Set[ExperimentType]): Future[JsObject] = {
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
        case (name, value) =>
          if (value == JsNull || value.isInstanceOf[JsUndefined]) {
            userValueRepo.clearValue(userId, name)
          } else {
            userValueRepo.setValue(userId, name, value.toString)
          }
      }
    }
  }

  val DELIGHTED_MIN_INTERVAL = 30 // days
  val DELIGHTED_INITIAL_DELAY = 7 // days

  def setLastUserActive(userId: Id[User]): Unit = {
    val time = clock.now
    db.readWrite { implicit s =>
      userValueRepo.setValue(userId, UserValueName.LAST_ACTIVE, time)
    }
  }

  def postDelightedAnswer(userId: Id[User], answer: BasicDelightedAnswer): Future[Option[ExternalId[DelightedAnswer]]] = {
    val user = db.readOnlyReplica { implicit s => userRepo.get(userId) }
    heimdalClient.postDelightedAnswer(DelightedUserRegistrationInfo(userId, user.externalId, user.primaryEmail, user.fullName), answer) map { answerOpt =>
      answerOpt flatMap (_.answerId)
    }
  }

  def cancelDelightedSurvey(userId: Id[User]): Future[Boolean] = {
    val user = db.readOnlyReplica { implicit s => userRepo.get(userId) }
    heimdalClient.cancelDelightedSurvey(DelightedUserRegistrationInfo(userId, user.externalId, user.primaryEmail, user.fullName))
  }

  def setUsername(userId: Id[User], username: Username, overrideRestrictions: Boolean = false, readOnly: Boolean = false): Either[String, Username] = {
    if (overrideRestrictions || Username.isValid(username.value)) {
      db.readWrite { implicit session =>
        val existingUser = userRepo.getNormalizedUsername(Username.normalize(username.value))

        if (existingUser.isEmpty || existingUser.get.id.get == userId) {
          if (!readOnly) {
            userRepo.save(userRepo.get(userId).withUsername(username))
          }
          Right(username)
        } else {
          Left("username_exists")
        }
      }
    } else {
      Left("invalid_username")
    }
  }

  def autoSetUsername(user: User, readOnly: Boolean): Option[Username] = {
    val name = Username.lettersOnly((user.firstName + user.lastName).toLowerCase)
    val seed = if (name.length < 4) {
      name + Seq.fill(4 - name.length)(0)
    } else name

    val candidates = seed :: (1 to 30).map(n => seed + scala.util.Random.nextInt(999)).toList
    var keepTrying = true
    var selectedUsername: Option[Username] = None
    var i = 0
    while (keepTrying && i < 30) {
      setUsername(user.id.get, Username(candidates(i)), readOnly = readOnly) match {
        case Right(username) =>
          keepTrying = false
          selectedUsername = Some(username)
        case Left(_) =>
          i += 1
      }
    }
    selectedUsername
  }

  def removeUsername(userId: Id[User]) = {
    db.readWrite { implicit session =>
      val user = userRepo.get(userId)
      userRepo.save(user.copy(username = None, normalizedUsername = None))
    }
  }

  protected def userAvatarImageUrl(user: User) = s3ImageStore.avatarUrlByUser(user)

  def importSocialEmail(userId: Id[User], emailAddress: EmailAddress): UserEmailAddress = {
    db.readWrite { implicit s =>
      val emails = emailRepo.getByAddress(emailAddress, excludeState = None)
      emails.map { email =>
        if (email.userId != userId) {
          if (email.state == UserEmailAddressStates.VERIFIED) {
            throw new IllegalStateException(s"email ${email.address} of user ${email.userId} is VERIFIED but not associated with user $userId")
          } else if (email.state == UserEmailAddressStates.UNVERIFIED) {
            emailRepo.save(email.withState(UserEmailAddressStates.INACTIVE))
          }
          None
        } else {
          Some(email)
        }
      }.flatten.headOption.getOrElse {
        log.info(s"creating new email $emailAddress for user $userId")
        val user = userRepo.get(userId)
        if (user.primaryEmail.isEmpty) userRepo.save(user.copy(primaryEmail = Some(emailAddress)))
        emailRepo.save(UserEmailAddress(userId = userId, address = emailAddress, state = UserEmailAddressStates.VERIFIED))
      }
    }
  }

}

object DefaultKeeps {
  val orderedTags: Seq[String] = Seq(
    "Recipe",
    "Shopping Wishlist",
    "Travel",
    "Read Later",
    "Funny",
    "Example Keep",
    "kifi Support"
  )

  val orderedKeepsWithTags: Seq[(KeepInfo, Seq[String])] = {
    val Seq(recipe, shopping, travel, later, funny, example, support) = orderedTags
    Seq(
      // Example keeps
      (KeepInfo(title = None, url = "http://joythebaker.com/2013/12/curry-hummus-with-currants-and-olive-oil/", isPrivate = true), Seq(example, recipe)),
      (KeepInfo(title = None, url = "http://www.amazon.com/Hitchhikers-Guide-Galaxy-25th-Anniversary/dp/1400052920/", isPrivate = true), Seq(example, shopping)),
      (KeepInfo(title = None, url = "https://www.airbnb.com/locations/san-francisco/mission-district", isPrivate = true), Seq(example, travel)),
      (KeepInfo(title = None, url = "http://twistedsifter.com/2013/01/50-life-hacks-to-simplify-your-world/", isPrivate = true), Seq(example, later)),
      (KeepInfo(title = None, url = "http://www.youtube.com/watch?v=_OBlgSz8sSM", isPrivate = true), Seq(example, funny)),

      // Support Keeps
      (KeepInfo(title = Some("kifi • Install kifi on Firefox and Chrome"), url = "https://www.kifi.com/install", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("kifi • How to Use kifi"), url = "http://support.kifi.com/customer/portal/articles/1397866-introduction-to-kifi-", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("kifi • Contact Us"), url = "http://support.kifi.com/customer/portal/emails/new", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("kifi • Find friends your friends on kifi"), url = "https://www.kifi.com/friends/invite", isPrivate = true), Seq(support))
    )
  }
}
