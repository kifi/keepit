package com.keepit.commanders

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
import akka.actor.Scheduler
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.Try
import securesocial.core.{ Identity, UserService, Registry }
import com.keepit.inject.FortyTwoConfig
import com.keepit.typeahead.socialusers.{ KifiUserTypeahead, SocialUserTypeahead }
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
import com.keepit.commanders.emails.{ SendEmailToNewUserFriendsHelper, SendEmailToNewUserContactsHelper, EmailOptOutCommander }
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
    bookmarkClicksRepo: UserBookmarkClicksRepo,
    userImageUrlCache: UserImageUrlCache,
    libraryCommander: LibraryCommander,
    sendEmailToNewUserFriendsHelper: SendEmailToNewUserFriendsHelper,
    sendEmailToNewUserContactsHelper: SendEmailToNewUserContactsHelper,
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

  def getHelpCounts(user: Id[User]): (Int, Int) = {
    //unique keeps, total clicks
    db.readOnlyReplica { implicit session => bookmarkClicksRepo.getClickCounts(user) }
  }

  def getKeepAttributionCounts(userId: Id[User]): Future[(Int, Int, Int)] = { // (discoveryCount, rekeepCount, rekeepTotalCount)
    heimdalClient.getDiscoveryCountByKeeper(userId) map { discoveryCount =>
      val (rekeepCount, rekeepTotalCount) = db.readOnlyReplica { implicit ro =>
        bookmarkClicksRepo.getReKeepCounts(userId)
      }
      (discoveryCount, rekeepCount, rekeepTotalCount)
    }
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
        abookServiceClient.getUsersWithContact(email).map {
          case contacts if contacts.size > 0 => {

            val toNotify = db.readOnlyReplica { implicit session =>
              val alreadyConnectedUsers = userConnectionRepo.getConnectedUsers(newUser.id.get)

              // only notify users who are not already connected to our list of users with the contact email
              for {
                userId <- contacts.diff(alreadyConnectedUsers)
                if userExperimentCommander.userHasExperiment(userId, ExperimentType.NOTIFY_USER_WHEN_CONTACTS_JOIN)
              } yield userId
            }

            log.info("sending new user contact notifications to: " + toNotify)
            sendEmailToNewUserContactsHelper(newUser, toNotify)

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

            toNotify
          }
          case _ => {
            log.info("cannot send contact notifications: primary email empty for user.id=" + newUserId)
            Set.empty
          }
        }
      }
    } else Option(Future.successful(Set.empty))
  }

  def sendWelcomeEmail(newUser: User, withVerification: Boolean = false, targetEmailOpt: Option[EmailAddress] = None): Unit = {
    val olderUser: Boolean = newUser.createdAt.isBefore(currentDateTime.minus(24 * 3600 * 1000)) //users older than 24h get the long form welcome email
    if (!db.readOnlyMaster { implicit session => userValueRepo.getValue(newUser.id.get, UserValues.welcomeEmailSent) }) {
      db.readWrite { implicit session => userValueRepo.setValue(newUser.id.get, UserValues.welcomeEmailSent.name, true) }

      if (withVerification) {
        val url = fortytwoConfig.applicationBaseUrl
        db.readWrite { implicit session =>
          val emailAddr = emailRepo.save(emailRepo.getByAddressOpt(targetEmailOpt.get).get.withVerificationCode(clock.now))
          val verifyUrl = s"$url${com.keepit.controllers.core.routes.AuthController.verifyEmail(emailAddr.verificationCode.get)}"
          userValueRepo.setValue(newUser.id.get, UserValueName.PENDING_PRIMARY_EMAIL, emailAddr.address)

          val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(emailAddr.address))}"

          val (category, subj, body) = if (newUser.state != UserStates.ACTIVE) {
            (NotificationCategory.User.EMAIL_CONFIRMATION,
              "Kifi.com | Please confirm your email address",
              views.html.email.verifyEmail(newUser.firstName, verifyUrl).body)
          } else {
            (NotificationCategory.User.WELCOME,
              "Let's get started with Kifi",
              if (olderUser) views.html.email.welcomeLongInlined(newUser.firstName, verifyUrl, unsubLink).body else views.html.email.welcomeInlined(newUser.firstName, verifyUrl, unsubLink).body)
          }
          val mail = ElectronicMail(
            from = SystemEmailAddress.NOTIFICATIONS,
            to = Seq(targetEmailOpt.get),
            category = category,
            subject = subj,
            htmlBody = body,
            textBody = Some(views.html.email.welcomeText(newUser.firstName, verifyUrl, unsubLink).body)
          )
          postOffice.sendMail(mail)
        }
      } else {
        db.readWrite { implicit session =>
          val emailAddr = emailRepo.getByUser(newUser.id.get)
          val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(emailAddr))}"
          val mail = ElectronicMail(
            from = SystemEmailAddress.NOTIFICATIONS,
            to = Seq(emailAddr),
            category = NotificationCategory.User.WELCOME,
            subject = "Let's get started with Kifi",
            htmlBody = if (olderUser) views.html.email.welcomeLongInlined(newUser.firstName, "http://www.kifi.com", unsubLink).body else views.html.email.welcomeInlined(newUser.firstName, "http://www.kifi.com", unsubLink).body,
            textBody = Some(views.html.email.welcomeText(newUser.firstName, "http://www.kifi.com", unsubLink).body)
          )
          postOffice.sendMail(mail)
        }
      }
    }
  }

  def createDefaultKeeps(userId: Id[User]): Unit = {
    val contextBuilder = new HeimdalContextBuilder()
    contextBuilder += ("source", KeepSource.default.value) // manually set the source so that it appears in tag analytics
    val keepsByTag = bookmarkCommander.keepWithMultipleTags(userId, DefaultKeeps.orderedKeepsWithTags, KeepSource.default)(contextBuilder.build)
    val tagsByName = keepsByTag.keySet.map(tag => tag.name -> tag).toMap
    val keepsByUrl = keepsByTag.values.flatten.map(keep => keep.url -> keep).toMap
    db.readWrite { implicit session => collectionCommander.setCollectionOrdering(userId, DefaultKeeps.orderedTags.map(tagsByName(_).externalId)) }
    bookmarkCommander.setFirstKeeps(userId, DefaultKeeps.orderedKeepsWithTags.map { case (keepInfo, _) => keepsByUrl(keepInfo.url) })
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

  private def sendFriendRequestAcceptedEmailAndNotification(myUserId: Id[User], friend: User): Unit = SafeFuture {
    //sending 'friend request accepted' email && Notification
    val (respondingUser, respondingUserImage) = db.readWrite { implicit session =>
      val respondingUser = userRepo.get(myUserId)
      val destinationEmail = emailRepo.getByUser(friend.id.get)
      val respondingUserImage = userAvatarImageUrl(respondingUser)
      val targetUserImage = userAvatarImageUrl(friend)
      val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(destinationEmail))}"

      postOffice.sendMail(ElectronicMail(
        senderUserId = None,
        from = SystemEmailAddress.NOTIFICATIONS,
        fromName = Some(s"${respondingUser.firstName} ${respondingUser.lastName} (via Kifi)"),
        to = List(destinationEmail),
        subject = s"${respondingUser.firstName} ${respondingUser.lastName} accepted your Kifi friend request",
        htmlBody = views.html.email.friendRequestAcceptedInlined(friend.firstName, respondingUser.firstName, respondingUser.lastName, targetUserImage, respondingUserImage, unsubLink).body,
        textBody = Some(views.html.email.friendRequestAcceptedText(friend.firstName, respondingUser.firstName, respondingUser.lastName, targetUserImage, respondingUserImage, unsubLink).body),
        category = NotificationCategory.User.FRIEND_ACCEPTED)
      )

      (respondingUser, respondingUserImage)

    }

    elizaServiceClient.sendGlobalNotification(
      userIds = Set(friend.id.get),
      title = s"${respondingUser.firstName} ${respondingUser.lastName} accepted your friend request!",
      body = s"Now you will enjoy ${respondingUser.firstName}'s keeps in your search results and you can message ${respondingUser.firstName} directly.",
      linkText = "Invite more friends to kifi",
      linkUrl = "https://www.kifi.com/friends/invite",
      imageUrl = respondingUserImage,
      sticky = false,
      category = NotificationCategory.User.FRIEND_ACCEPTED
    )
  }

  def sendingFriendRequestEmailAndNotification(request: FriendRequest, myUserId: Id[User], recipient: User): Unit = SafeFuture {
    val (requestingUser, requestingUserImage) = db.readWrite { implicit session =>
      val requestingUser = userRepo.get(myUserId)
      val destinationEmail = emailRepo.getByUser(recipient.id.get)
      val requestingUserImage = s3ImageStore.avatarUrlByExternalId(Some(200), requestingUser.externalId, requestingUser.pictureName.getOrElse("0"), Some("https"))
      val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(destinationEmail))}"
      postOffice.sendMail(ElectronicMail(
        senderUserId = None,
        from = SystemEmailAddress.NOTIFICATIONS,
        fromName = Some(s"${requestingUser.firstName} ${requestingUser.lastName} (via Kifi)"),
        to = List(destinationEmail),
        subject = s"${requestingUser.firstName} ${requestingUser.lastName} sent you a friend request.",
        htmlBody = views.html.email.friendRequestInlined(recipient.firstName, requestingUser.firstName + " " + requestingUser.lastName, requestingUserImage, unsubLink).body,
        textBody = Some(views.html.email.friendRequestText(recipient.firstName, requestingUser.firstName + " " + requestingUser.lastName, requestingUserImage, unsubLink).body),
        category = NotificationCategory.User.FRIEND_REQUEST)
      )

      (requestingUser, requestingUserImage)
    }

    elizaServiceClient.sendGlobalNotification(
      userIds = Set(recipient.id.get),
      title = s"${requestingUser.firstName} ${requestingUser.lastName} sent you a friend request",
      body = s"Enjoy ${requestingUser.firstName}'s keeps in your search results and message ${requestingUser.firstName} directly.",
      linkText = s"Respond to ${requestingUser.firstName}'s friend request",
      linkUrl = "https://kifi.com/friends/requests",
      imageUrl = requestingUserImage,
      sticky = false,
      category = NotificationCategory.User.FRIEND_REQUEST
    ) map { id =>
        db.readWrite { implicit session =>
          friendRequestRepo.save(request.copy(messageHandle = Some(id)))
        }
      }
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
          val siteUrl = fortytwoConfig.applicationBaseUrl
          val verifyUrl = s"$siteUrl${com.keepit.controllers.core.routes.AuthController.verifyEmail(emailAddr.verificationCode.get)}"

          postOffice.sendMail(ElectronicMail(
            from = SystemEmailAddress.NOTIFICATIONS,
            to = Seq(address),
            subject = "Kifi.com | Please confirm your email address",
            htmlBody = views.html.email.verifyEmail(firstName, verifyUrl).body,
            category = NotificationCategory.User.EMAIL_CONFIRMATION
          ))
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
      val from = time.minusDays(DELIGHTED_INITIAL_DELAY)
      val to = user.createdAt
      val shouldShowDelightedQuestionFut = if (experiments.contains(ExperimentType.DELIGHTED_SURVEY_PERMANENT))
        Future.successful(true)
      else if (time.minusDays(DELIGHTED_INITIAL_DELAY) > user.createdAt) {
        heimdalClient.getLastDelightedAnswerDate(userId) map { lastDelightedAnswerDate =>
          val minDate = lastDelightedAnswerDate getOrElse START_OF_TIME
          (time.minusDays(DELIGHTED_MIN_INTERVAL) > minDate)
        }
      } else Future.successful(false)
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
    if (overrideRestrictions || UsernameOps.isValid(username.value)) {
      db.readWrite { implicit session =>
        val existingUser = userRepo.getNormalizedUsername(UsernameOps.normalize(username.value))

        if (existingUser.isEmpty || existingUser.get.id.get == userId) {
          if (!readOnly) {
            userRepo.save(userRepo.get(userId).copy(username = Some(username), normalizedUsername = Some(UsernameOps.normalize(username.value))))
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
    val name = UsernameOps.lettersOnly((user.firstName + user.lastName).toLowerCase)
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

object UsernameOps {
  import scala.collection.immutable.HashSet
  // Words that cannot appear anywhere in a username
  private val censorList = HashSet("support", "help", "kifi", "link", "friend", "invite", "admin", "about", "password")

  // No exact normalized matches
  private val topDomains = HashSet("123-reg", "163", "1688", "1und1", "360", "4shared", "51", "a8", "abc", "about", "aboutads", "ac", "accuweather", "acquirethisname", "addthis", "addtoany", "admin", "adobe", "alexa", "alibaba", "altervista", "amazon", "amazonaws", "ameblo", "angelfire", "answers", "aol", "apache", "apple", "archive", "arizona", "army", "arstechnica", "artisteer", "ask", "au", "auda", "baidu", "bandcamp", "barnesandnoble", "bbb", "bbc", "be", "behance", "berkeley", "biblegateway", "bigcartel", "biglobe", "bing", "bizjournals", "blinklist", "blog", "blogger", "bloglines", "bloglovin", "blogs", "blogspot", "blogtalkradio", "bloomberg", "bluehost", "booking", "boston", "br", "bravesites", "businessinsider", "businessweek", "businesswire", "buzzfeed", "ca", "cafepress", "cam", "canalblog", "cargocollective", "cbc", "cbslocal", "cbsnews", "cc", "cdbaby", "cdc", "census", "ch", "chicagotribune", "china", "chron", "chronoengine", "cisco", "clickbank", "cloudflare", "cmu", "cn", "cnbc", "cnet", "cnn", "co", "cocolog-nifty", "columbia", "com", "comcast", "comsenz", "constantcontact", "cornell", "cpanel", "craigslist", "creativecommons", "csmonitor", "cyberchimps", "cz", "dagondesign", "dailymail", "dailymotion", "de", "dedecms", "delicious", "deliciousdays", "dell", "desdev", "devhub", "deviantart", "digg", "diigo", "dion", "discovery", "discuz", "disqus", "dmoz", "domainmarket", "dot", "dropbox", "drupal", "dyndns", "e-recht24", "earthlink", "ebay", "economist", "ed", "edu", "edublogs", "eepurl", "ehow", "elegantthemes", "elpais", "engadget", "epa", "es", "etsy", "eu", "europa", "eventbrite", "examiner", "example", "exblog", "ezinearticles", "facebook", "fastcompany", "fc2", "fda", "feedburner", "fema", "flavors", "flickr", "fm", "forbes", "fotki", "foxnews", "fr", "free", "freewebs", "friendfeed", "ft", "ftc", "furl", "g", "gd", "geocities", "github", "gizmodo", "gl", "globo", "gmpg", "gnu", "go", "godaddy", "goo", "goodreads", "google", "gov", "gravatar", "guardian", "hao123", "harvard", "hatena", "hc360", "hexun", "hhs", "hibu", "histats", "hk", "home", "homestead", "hostgator", "house", "howstuffworks", "hp", "hubpages", "hud", "huffingtonpost", "hugedomains", "i2i", "ibm", "icio", "icq", "ifeng", "ihg", "illinois", "imageshack", "imdb", "imgur", "independent", "indiatimes", "indiegogo", "info", "infoseek", "instagram", "int", "intel", "io", "irs", "is", "issuu", "istockphoto", "it", "jalbum", "japanpost", "java", "jiathis", "jigsy", "jimdo", "joomla", "jp", "jugem", "kickstarter", "la", "last", "latimes", "linkedin", "list-manage", "live", "liveinternet", "livejournal", "loc", "lulu", "ly", "lycos", "mac", "macromedia", "mail", "mapquest", "mapy", "marketwatch", "marriott", "mashable", "mayoclinic", "me", "mediafire", "meetup", "merriam-webster", "microsoft", "miibeian", "miitbeian", "mil", "mit", "mlb", "moonfruit", "mozilla", "msn", "msu", "mtv", "multiply", "myspace", "mysql", "narod", "nasa", "nationalgeographic", "nature", "naver", "nba", "nbcnews", "ne", "net", "netlog", "netscape", "netvibes", "networkadvertising", "networksolutions", "newsvine", "newyorker", "nhs", "nifty", "nih", "ning", "nl", "noaa", "npr", "nps", "nsw", "nydailynews", "nymag", "nytimes", "nyu", "oaic", "oakley", "ocn", "odnoklassniki", "omniture", "opensource", "opera", "or", "oracle", "org", "over-blog", "ovh", "ow", "ox", "pagesperso-orange", "paginegialle", "parallels", "patch", "paypal", "pbs", "pcworld", "pen", "people", "phoca", "photobucket", "php", "phpbb", "pinterest", "pl", "plala", "posterous", "princeton", "printfriendly", "privacy", "prlog", "prnewswire", "prweb", "psu", "purevolume", "qq", "quantcast", "rakuten", "rambler", "redcross", "reddit", "rediff", "reference", "reuters", "reverbnation", "ru", "sakura", "salon", "samsung", "sbwire", "sciencedaily", "sciencedirect", "scientificamerican", "scribd", "seattletimes", "seesaa", "senate", "sfgate", "shareasale", "shinystat", "shop-pro", "shutterfly", "si", "simplemachines", "sina", "sitemeter", "skype", "skyrock", "slashdot", "slate", "slideshare", "smh", "smugmug", "so-net", "sogou", "sohu", "soundcloud", "soup", "sourceforge", "sphinn", "spiegel", "spotify", "springer", "squarespace", "squidoo", "stanford", "statcounter", "state", "storify", "studiopress", "stumbleupon", "sun", "surveymonkey", "symantec", "t", "t-online", "tamu", "taobao", "techcrunch", "technorati", "ted", "telegraph", "theatlantic", "theglobeandmail", "theguardian", "themeforest", "thetimes", "time", "timesonline", "tiny", "tinypic", "tinyurl", "tmall", "toplist", "topsy", "trellian", "tripadvisor", "tripod", "tumblr", "tuttocitta", "tv", "twitpic", "twitter", "tx", "typepad", "ucla", "ucoz", "ucsd", "uiuc", "uk", "umich", "umn", "un", "unblog", "unc", "unesco", "unicef", "uol", "upenn", "us", "usa", "usatoday", "usda", "usgs", "usnews", "ustream", "utexas", "va", "vimeo", "vinaora", "virginia", "vistaprint", "vk", "vkontakte", "vu", "w3", "walmart", "washington", "washingtonpost", "weather", "webeden", "webmd", "webnode", "webs", "weebly", "weibo", "whitehouse", "who", "wikia", "wikimedia", "wikipedia", "wikispaces", "wiley", "wired", "wisc", "wix", "woothemes", "wordpress", "wp", "wsj", "wufoo", "wunderground", "xing", "xinhuanet", "xrea", "yahoo", "yale", "yandex", "ycombinator", "yellowbook", "yellowpages", "yelp", "yolasite", "youku", "youtu", "youtube", "zdnet", "zimbio")
  private val commonEnglishWords = HashSet("the", "be", "and", "of", "a", "in", "to", "have", "it", "I", "that", "for", "you", "he", "with", "on", "do", "say", "this", "they", "at", "but", "we", "his", "from", "not", "by", "she", "or", "as", "what", "go", "their", "can", "who", "get", "if", "would", "her", "all", "my", "make", "about", "know", "will", "up", "one", "time", "there", "year", "so", "think", "when", "which", "them", "some", "me", "people", "take", "out", "into", "just", "see", "him", "your", "come", "could", "now", "than", "like", "other", "how", "then", "its", "our", "two", "more", "these", "want", "way", "look", "first", "also", "new", "because", "day", "use", "no", "man", "find", "here", "thing", "give", "many", "well", "only", "those", "tell", "very", "even", "back", "any", "good", "woman", "through", "us", "life", "child", "work", "down", "may", "after", "should", "call", "world", "over", "school", "still", "try", "last", "ask", "need", "too", "feel", "three", "state", "never", "become", "between", "high", "really", "something", "most", "another", "much", "family", "own", "leave", "put", "old", "while", "mean", "keep", "student", "why", "let", "great", "same", "big", "group", "begin", "seem", "country", "help", "talk", "where", "turn", "problem", "every", "start", "hand", "might", "American", "show", "part", "against", "place", "such", "again", "few", "case", "week", "company", "system", "each", "right", "program", "hear", "question", "during", "play", "government", "run", "small", "number", "off", "always", "move", "night", "live", "Mr", "point", "believe", "hold", "today", "bring", "happen", "next", "without", "before", "large", "million", "must", "home", "under", "water", "room", "write", "mother", "area", "national", "money", "story", "young", "fact", "month", "different", "lot", "study", "book", "eye", "job", "word", "though", "business", "issue", "side", "kind", "four", "head", "far", "black", "long", "both", "little", "house", "yes", "since", "provide", "service", "around", "friend", "important", "father", "sit", "away", "until", "power", "hour", "game", "often", "yet", "line", "political", "end", "among", "ever", "stand", "bad", "lose", "however", "member", "pay", "law", "meet", "car", "city", "almost", "include", "continue", "set", "later", "community", "name", "five", "once", "white", "least", "president", "learn", "real", "change", "team", "minute", "best", "several", "idea", "kid", "body", "information", "nothing", "ago", "lead", "social", "understand", "whether", "watch", "together", "follow", "parent", "stop", "face", "anything", "create", "public", "already", "speak", "others", "read", "level", "allow", "add", "office", "spend", "door", "health", "person", "art", "sure", "war", "history", "party", "within", "grow", "result", "open", "morning", "walk", "reason", "low", "win", "research", "girl", "guy", "early", "food", "moment", "himself", "air", "teacher", "force", "offer", "enough", "education", "across", "although", "remember", "foot", "second", "boy", "maybe", "toward", "able", "age", "policy", "everything", "love", "process", "music", "including", "consider", "appear", "actually", "buy", "probably", "human", "wait", "serve", "market", "die", "send", "expect", "sense", "build", "stay", "fall", "oh", "nation", "plan", "cut", "college", "interest", "death", "course", "someone", "experience", "behind", "reach", "local", "kill", "six", "remain", "effect", "yeah", "suggest", "class", "control", "raise", "care", "perhaps", "late", "hard", "field", "else", "pass", "former", "sell", "major", "sometimes", "require", "along", "development", "themselves", "report", "role", "better", "economic", "effort", "decide", "rate", "strong", "possible", "heart", "drug", "leader", "light", "voice", "wife", "whole", "police", "mind", "finally", "pull", "return", "free", "military", "price", "less", "according", "decision", "explain", "son", "hope", "develop", "view", "relationship", "carry", "town", "road", "drive", "arm", "TRUE", "federal", "break", "difference", "thank", "receive", "value", "international", "building", "action", "full", "model", "join", "season", "society", "tax", "director", "position", "player", "agree", "especially", "record", "pick", "wear", "paper", "special", "space", "ground", "form", "support", "event", "official", "whose", "matter", "everyone", "center", "couple", "site", "project", "hit", "base", "activity", "star", "table", "court", "produce", "eat", "teach", "oil", "half", "situation", "easy", "cost", "industry", "figure", "street", "image", "itself", "phone", "either", "data", "cover", "quite", "picture", "clear", "practice", "piece", "land", "recent", "describe", "product", "doctor", "wall", "patient", "worker", "news", "test", "movie", "certain", "north", "personal", "simply", "third", "technology", "catch", "step", "baby", "computer", "type", "attention", "draw", "film", "Republican", "tree", "source", "red", "nearly", "organization", "choose", "cause", "hair", "century", "evidence", "window", "difficult", "listen", "soon", "culture", "billion", "chance", "brother", "energy", "period", "summer", "realize", "hundred", "available", "plant", "likely", "opportunity", "term", "short", "letter", "condition", "choice", "single", "rule", "daughter", "administration", "south", "husband", "Congress", "floor", "campaign", "material", "population", "economy", "medical", "hospital", "church", "close", "thousand", "risk", "current", "fire", "future", "wrong", "involve", "defense", "anyone", "increase", "security", "bank", "myself", "certainly", "west", "sport", "board", "seek", "per", "subject", "officer", "private", "rest", "behavior", "deal", "performance", "fight", "throw", "top", "quickly", "past", "goal", "bed", "order", "author", "fill", "represent", "focus", "foreign", "drop", "blood", "upon", "agency", "push", "nature", "color", "recently", "store", "reduce", "sound", "note", "fine", "near", "movement", "page", "enter", "share", "common", "poor", "natural", "race", "concern", "series", "significant", "similar", "hot", "language", "usually", "response", "dead", "rise", "animal", "factor", "decade", "article", "shoot", "east", "save", "seven", "artist", "scene", "stock", "career", "despite", "central", "eight", "thus", "treatment", "beyond", "happy", "exactly", "protect", "approach", "lie", "size", "dog", "fund", "serious", "occur", "media", "ready", "sign", "thought", "list", "individual", "simple", "quality", "pressure", "accept", "answer", "resource", "identify", "left", "meeting", "determine", "prepare", "disease", "whatever", "success", "argue", "cup", "particularly", "amount", "ability", "staff", "recognize", "indicate", "character", "growth", "loss", "degree", "wonder", "attack", "herself", "region", "television", "box", "TV", "training", "pretty", "trade", "election", "everybody", "physical", "lay", "general", "feeling", "standard", "bill", "message", "fail", "outside", "arrive", "analysis", "benefit", "sex", "forward", "lawyer", "present", "section", "environmental", "glass", "skill", "sister", "PM", "professor", "operation", "financial", "crime", "stage", "ok", "compare", "authority", "miss", "design", "sort", "act", "ten", "knowledge", "gun", "station", "blue", "strategy", "clearly", "discuss", "indeed", "truth", "song", "example", "democratic", "check", "environment", "leg", "dark", "various", "rather", "laugh", "guess", "executive", "prove", "hang", "entire", "rock", "forget", "claim", "remove", "manager", "enjoy", "network", "legal", "religious", "cold", "final", "main", "science", "green", "memory", "card", "above", "seat", "cell", "establish", "nice", "trial", "expert", "spring", "firm", "Democrat", "radio", "visit", "management", "avoid", "imagine", "tonight", "huge", "ball", "finish", "yourself", "theory", "impact", "respond", "statement", "maintain", "charge", "popular", "traditional", "onto", "reveal", "direction", "weapon", "employee", "cultural", "contain", "peace", "pain", "apply", "measure", "wide", "shake", "fly", "interview", "manage", "chair", "fish", "particular", "camera", "structure", "politics", "perform", "bit", "weight", "suddenly", "discover", "candidate", "production", "treat", "trip", "evening", "affect", "inside", "conference", "unit", "style", "adult", "worry", "range", "mention", "deep", "edge", "specific", "writer", "trouble", "necessary", "throughout", "challenge", "fear", "shoulder", "institution", "middle", "sea", "dream", "bar", "beautiful", "property", "instead", "improve", "stuff", "detail", "method", "somebody", "magazine", "hotel", "soldier", "reflect", "heavy", "sexual", "bag", "heat", "marriage", "tough", "sing", "surface", "purpose", "exist", "pattern", "whom", "skin", "agent", "owner", "machine", "gas", "ahead", "generation", "commercial", "address", "cancer", "item", "reality", "coach", "Mrs", "yard", "beat", "violence", "total", "tend", "investment", "discussion", "finger", "garden", "notice", "collection", "modern", "task", "partner", "positive", "civil", "kitchen", "consumer", "shot", "budget", "wish", "painting", "scientist", "safe", "agreement", "capital", "mouth", "nor", "victim", "newspaper", "threat", "responsibility", "smile", "attorney", "score", "account", "interesting", "audience", "rich", "dinner", "vote", "western", "relate", "travel", "debate", "prevent", "citizen", "majority", "none", "front", "born", "admit", "senior", "assume", "wind", "key", "professional", "mission", "fast", "alone", "customer", "suffer", "speech", "successful", "option", "participant", "southern", "fresh", "eventually", "forest", "video", "global", "Senate", "reform", "access", "restaurant", "judge", "publish", "relation", "release", "bird", "opinion", "credit", "critical", "corner", "concerned", "recall", "version", "stare", "safety", "effective", "neighborhood", "original", "troop", "income", "directly", "hurt", "species", "immediately", "track", "basic", "strike", "sky", "freedom", "absolutely", "plane", "nobody", "achieve", "object", "attitude", "labor", "refer", "concept", "client", "powerful", "perfect", "nine", "therefore", "conduct", "announce", "conversation", "examine", "touch", "please", "attend", "completely", "variety", "sleep", "involved", "investigation", "nuclear", "researcher", "press", "conflict", "spirit", "replace", "British", "encourage", "argument", "camp", "brain", "feature", "afternoon", "AM", "weekend", "dozen", "possibility", "insurance", "department", "battle", "beginning", "date", "generally", "African", "sorry", "crisis", "complete", "fan", "stick", "define", "easily", "hole", "element", "vision", "status", "normal", "Chinese", "ship", "solution", "stone", "slowly", "scale", "university", "introduce", "driver", "attempt", "park", "spot", "lack", "ice", "boat", "drink", "sun", "distance", "wood", "handle", "truck", "mountain", "survey", "supposed", "tradition", "winter", "village", "Soviet", "refuse", "sales", "roll", "communication", "screen", "gain", "resident", "hide", "gold", "club", "farm", "potential", "European", "presence", "independent", "district", "shape", "reader", "Ms", "contract", "crowd", "Christian", "express", "apartment", "willing", "strength", "previous", "band", "obviously", "horse", "interested", "target", "prison", "ride", "guard", "terms", "demand", "reporter", "deliver", "text", "tool", "wild", "vehicle", "observe", "flight", "facility", "understanding", "average", "emerge", "advantage", "quick", "leadership", "earn", "pound", "basis", "bright", "operate", "guest", "sample", "contribute", "tiny", "block", "protection", "settle", "feed", "collect", "additional", "highly", "identity", "title", "mostly", "lesson", "faith", "river", "promote", "living", "count", "unless", "marry", "tomorrow", "technique", "path", "ear", "shop", "folk", "principle", "survive", "lift", "border", "competition", "jump", "gather", "limit", "fit", "cry", "equipment", "worth", "associate", "critic", "warm", "aspect", "insist", "failure", "annual", "French", "Christmas", "comment", "responsible", "affair", "procedure", "regular", "spread", "chairman", "baseball", "soft", "ignore", "egg", "belief", "demonstrate", "anybody", "murder", "gift", "religion", "review", "editor", "engage", "coffee", "document", "speed", "cross", "influence", "anyway", "threaten", "commit", "female", "youth", "wave", "afraid", "quarter", "background", "native", "broad", "wonderful", "deny", "apparently", "slightly", "reaction", "twice", "suit", "perspective", "growing", "blow", "construction", "intelligence", "destroy", "cook", "connection", "burn", "shoe", "grade", "context", "committee", "hey", "mistake", "location", "clothes", "Indian", "quiet", "dress", "promise", "aware", "neighbor", "function", "bone", "active", "extend", "chief", "combine", "wine", "below", "cool", "voter", "learning", "bus", "hell", "dangerous", "remind", "moral", "United", "category", "relatively", "victory", "academic", "Internet", "healthy", "negative", "following", "historical", "medicine", "tour", "depend", "photo", "finding", "grab", "direct", "classroom", "contact", "justice", "participate", "daily", "fair", "pair", "famous", "exercise", "knee", "flower", "tape", "hire", "familiar", "appropriate", "supply", "fully", "actor", "birth", "search", "tie", "democracy", "eastern", "primary", "yesterday", "circle", "device", "progress", "bottom", "island", "exchange", "clean", "studio", "train", "lady", "colleague", "application", "neck", "lean", "damage", "plastic", "tall", "plate", "hate", "otherwise", "writing", "male", "alive", "expression", "football", "intend", "chicken", "army", "abuse", "theater", "shut", "map", "extra", "session", "danger", "welcome", "domestic", "lots", "literature", "rain", "desire", "assessment", "injury", "respect", "northern", "nod", "paint", "fuel", "leaf", "dry", "Russian", "instruction", "pool", "climb", "sweet", "engine", "fourth", "salt", "expand", "importance", "metal", "fat", "ticket", "software", "disappear", "corporate", "strange", "lip", "reading", "urban", "mental", "increasingly", "lunch", "educational", "somewhere", "farmer", "sugar", "planet", "favorite", "explore", "obtain", "enemy", "greatest", "complex", "surround", "athlete", "invite", "repeat", "carefully", "soul", "scientific", "impossible", "panel", "meaning", "mom", "married", "instrument", "predict", "weather", "presidential", "emotional", "commitment", "Supreme", "bear", "pocket", "thin", "temperature", "surprise", "poll", "proposal", "consequence", "breath", "sight", "balance", "adopt", "minority", "straight", "connect", "works", "teaching", "belong", "aid", "advice", "okay", "photograph", "empty", "regional", "trail", "novel", "code", "somehow", "organize", "jury", "breast", "Iraqi", "acknowledge", "theme", "storm", "union", "desk", "thanks", "fruit", "expensive", "yellow", "conclusion", "prime", "shadow", "struggle", "conclude", "analyst", "dance", "regulation", "being", "ring", "largely", "shift", "revenue", "mark", "locate", "county", "appearance", "package", "difficulty", "bridge", "recommend", "obvious", "basically", "e-mail", "generate", "anymore", "propose", "thinking", "possibly", "trend", "visitor", "loan", "currently", "comfortable", "investor", "profit", "angry", "crew", "accident", "meal", "hearing", "traffic", "muscle", "notion", "capture", "prefer", "truly", "earth", "Japanese", "chest", "thick", "cash", "museum", "beauty", "emergency", "unique", "internal", "ethnic", "link", "stress", "content", "select", "root", "nose", "declare", "appreciate", "actual", "bottle", "hardly", "setting", "launch", "file", "sick", "outcome", "ad", "defend", "duty", "sheet", "ought", "ensure", "Catholic", "extremely", "extent", "component", "mix", "long-term", "slow", "contrast", "zone", "wake", "airport", "brown", "shirt", "pilot", "warn", "ultimately", "cat", "contribution", "capacity", "ourselves", "estate", "guide", "circumstance", "snow", "English", "politician", "steal", "pursue", "slip", "percentage", "meat", "funny", "neither", "soil", "surgery", "correct", "Jewish", "blame", "estimate", "due", "basketball", "golf", "investigate", "crazy", "significantly", "chain", "branch", "combination", "frequently", "governor", "relief", "user", "dad", "kick", "manner", "ancient", "silence", "rating", "golden", "motion", "German", "gender", "solve", "fee", "landscape", "used", "bowl", "equal", "forth", "frame", "typical", "except", "conservative", "eliminate", "host", "hall", "trust", "ocean", "row", "producer", "afford", "meanwhile", "regime", "division", "confirm", "fix", "appeal", "mirror", "tooth", "smart", "length", "entirely", "rely", "topic", "complain", "variable", "telephone", "perception", "attract", "confidence", "bedroom", "secret", "debt", "rare", "tank", "nurse", "coverage", "opposition", "aside", "anywhere", "bond", "pleasure", "master", "era", "requirement", "fun", "expectation", "wing", "separate", "somewhat", "pour", "stir", "judgment", "beer", "reference", "tear", "doubt", "grant", "seriously", "minister", "totally", "hero", "industrial", "cloud", "stretch", "winner", "volume", "seed", "surprised", "fashion", "pepper", "busy", "intervention", "copy", "tip", "cheap", "aim", "cite", "welfare", "vegetable", "gray", "dish", "beach", "improvement", "everywhere", "opening", "overall", "divide", "initial", "terrible", "oppose", "contemporary", "route", "multiple", "essential", "league", "criminal", "careful", "core", "upper", "rush", "necessarily", "specifically", "tired", "employ", "holiday", "vast", "resolution", "household", "fewer", "abortion", "apart", "witness", "match", "barely", "sector", "representative", "beneath", "beside", "incident", "limited", "proud", "flow", "faculty", "increased", "waste", "merely", "mass", "emphasize", "experiment", "definitely", "bomb", "enormous", "tone", "liberal", "massive", "engineer", "wheel", "decline", "invest", "cable", "towards", "expose", "rural", "AIDS", "Jew", "narrow", "cream", "secretary", "gate", "solid", "hill", "typically", "noise", "grass", "unfortunately", "hat", "legislation", "succeed", "celebrate", "achievement", "fishing", "accuse", "useful", "reject", "talent", "taste", "characteristic", "milk", "escape", "cast", "sentence", "unusual", "closely", "convince", "height", "physician", "assess", "plenty", "virtually", "addition", "sharp", "creative", "lower", "approve", "explanation", "gay", "campus", "proper", "guilty", "acquire", "compete", "technical", "plus", "immigrant", "weak", "illegal", "hi", "alternative", "interaction", "column", "personality", "signal", "curriculum", "honor", "passenger", "assistance", "forever", "regard", "Israeli", "association", "twenty", "knock", "wrap", "lab", "display", "criticism", "asset", "depression", "spiritual", "musical", "journalist", "prayer", "suspect", "scholar", "warning", "climate", "cheese", "observation", "childhood", "payment", "sir", "permit", "cigarette", "definition", "priority", "bread", "creation", "graduate", "request", "emotion", "scream", "dramatic", "universe", "gap", "excellent", "deeply", "prosecutor", "lucky", "drag", "airline", "library", "agenda", "recover", "factory", "selection", "primarily", "roof", "unable", "expense", "initiative", "diet", "arrest", "funding", "therapy", "wash", "schedule", "sad", "brief", "housing", "post", "purchase", "existing", "steel", "regarding", "shout", "remaining", "visual", "fairly", "chip", "violent", "silent", "suppose", "self", "bike", "tea", "perceive", "comparison", "settlement", "layer", "planning", "description", "slide", "widely", "wedding", "inform", "portion", "territory", "immediate", "opponent", "abandon", "lake", "transform", "tension", "leading", "bother", "consist", "alcohol", "enable", "bend", "saving", "desert", "shall", "error", "cop", "Arab", "double", "sand", "Spanish", "print", "preserve", "passage", "formal", "transition", "existence", "album", "participation", "arrange", "atmosphere", "joint", "reply", "cycle", "opposite", "lock", "deserve", "consistent", "resistance", "discovery", "exposure", "pose", "stream", "sale", "pot", "grand", "mine", "hello", "coalition", "tale", "knife", "resolve", "racial", "phase", "joke", "coat", "Mexican", "symptom", "manufacturer", "philosophy", "potato", "foundation", "quote", "online", "negotiation", "urge", "occasion", "dust", "breathe", "elect", "investigator", "jacket", "glad", "ordinary", "reduction", "rarely", "pack", "suicide", "numerous", "substance", "discipline", "elsewhere", "iron", "practical", "moreover", "passion", "volunteer", "implement", "essentially", "gene", "enforcement", "vs", "sauce", "independence", "marketing", "priest", "amazing", "intense", "advance", "employer", "shock", "inspire", "adjust", "retire", "visible", "kiss", "illness", "cap", "habit", "competitive", "juice", "congressional", "involvement", "dominate", "previously", "whenever", "transfer", "analyze", "attach", "disaster", "parking", "prospect", "boss", "complaint", "championship", "fundamental", "severe", "enhance", "mystery", "impose", "poverty", "entry", "spending", "king", "evaluate", "symbol", "maker", "mood", "accomplish", "emphasis", "illustrate", "boot", "monitor", "Asian", "entertainment", "bean", "evaluation", "creature", "commander", "digital", "arrangement", "concentrate", "usual", "anger", "psychological", "heavily", "peak", "approximately", "increasing", "disorder", "missile", "equally", "vary", "wire", "round", "distribution", "transportation", "holy", "twin", "command", "commission", "interpretation", "breakfast", "strongly", "engineering", "luck", "so-called", "constant", "clinic", "veteran", "smell", "tablespoon", "capable", "nervous", "tourist", "toss", "crucial", "bury", "pray", "tomato", "exception", "butter", "deficit", "bathroom", "objective", "electronic", "ally", "journey", "reputation", "mixture", "surely", "tower", "smoke", "confront", "pure", "glance", "dimension", "toy", "prisoner", "fellow", "smooth", "nearby", "peer", "designer", "personnel", "educator", "relative", "immigration", "belt", "teaspoon", "birthday", "implication", "perfectly", "coast", "supporter", "accompany", "silver", "teenager", "recognition", "retirement", "flag", "recovery", "whisper", "gentleman", "corn", "moon", "inner", "junior", "throat", "salary", "swing", "observer", "publication", "crop", "dig", "permanent", "phenomenon", "anxiety", "unlike", "wet", "literally", "resist", "convention", "embrace", "assist", "exhibition", "construct", "viewer", "pan", "consultant", "administrator", "occasionally", "mayor", "consideration", "CEO", "secure", "pink", "buck", "historic", "poem", "grandmother", "bind", "fifth", "constantly", "enterprise", "favor", "testing", "stomach", "apparent", "weigh", "install", "sensitive", "suggestion", "mail", "recipe", "reasonable", "preparation", "wooden", "elementary", "concert", "aggressive", "FALSE", "intention", "channel", "extreme", "tube", "drawing", "protein", "quit", "absence", "Latin", "rapidly", "jail", "diversity", "honest", "Palestinian", "pace", "employment", "speaker", "impression", "essay", "respondent", "giant", "cake", "historian", "negotiate", "restore", "substantial", "pop", "specialist", "origin", "approval", "quietly", "advise", "conventional", "depth", "wealth", "disability", "shell", "criticize", "effectively", "biological", "onion", "deputy", "flat", "brand", "assure", "mad", "award", "criteria", "dealer", "via", "utility", "precisely", "arise", "armed", "nevertheless", "highway", "clinical", "routine", "wage", "normally", "phrase", "ingredient", "stake", "Muslim", "fiber", "activist", "Islamic", "snap", "terrorism", "refugee", "incorporate", "hip", "ultimate", "switch", "corporation", "valuable", "assumption", "gear", "barrier", "minor", "provision", "killer", "assign", "gang", "developing", "classic", "chemical", "label", "teen", "index", "vacation", "advocate", "draft", "extraordinary", "heaven", "rough", "yell", "pregnant", "distant", "drama", "satellite", "personally", "clock", "chocolate", "Italian", "Canadian", "ceiling", "sweep", "advertising", "universal", "spin", "button", "bell", "rank", "darkness", "clothing", "super", "yield", "fence", "portrait", "survival", "roughly", "lawsuit", "testimony", "bunch", "found", "burden", "react", "chamber", "furniture", "cooperation", "string", "ceremony", "communicate", "cheek", "lost", "profile", "mechanism", "disagree", "penalty", "ie", "resort", "destruction", "unlikely", "tissue", "constitutional", "pant", "stranger", "infection", "cabinet", "broken", "apple", "electric", "proceed", "bet", "literary", "virus", "stupid", "dispute", "fortune", "strategic", "assistant", "overcome", "remarkable", "occupy", "statistics", "shopping", "cousin", "encounter", "wipe", "initially", "blind", "port", "electricity", "genetic", "adviser", "spokesman", "retain", "latter", "incentive", "slave", "translate", "accurate", "whereas", "terror", "expansion", "elite", "Olympic", "dirt", "odd", "rice", "bullet", "tight", "Bible", "chart", "solar", "square", "concentration", "complicated", "gently", "champion", "scenario", "telescope", "reflection", "revolution", "strip", "interpret", "friendly", "tournament", "fiction", "detect", "tremendous", "lifetime", "recommendation", "senator", "hunting", "salad", "guarantee", "innocent", "boundary", "pause", "remote", "satisfaction", "journal", "bench", "lover", "raw", "awareness", "surprising", "withdraw", "deck", "similarly", "newly", "pole", "testify", "mode", "dialogue", "imply", "naturally", "mutual", "founder", "advanced", "pride", "dismiss", "aircraft", "delivery", "mainly", "bake", "freeze", "platform", "finance", "sink", "attractive", "diverse", "relevant", "ideal", "joy", "regularly", "working", "singer", "evolve", "shooting", "partly", "unknown", "offense", "counter", "DNA", "potentially", "thirty", "justify", "protest", "crash", "craft", "treaty", "terrorist", "insight", "possess", "politically", "tap", "extensive", "episode", "swim", "tire", "fault", "loose", "shortly", "originally", "considerable", "prior", "intellectual", "assault", "relax", "stair", "adventure", "external", "proof", "confident", "headquarters", "sudden", "dirty", "violation", "tongue", "license", "shelter", "rub", "controversy", "entrance", "properly", "fade", "defensive", "tragedy", "net", "characterize", "funeral", "profession", "alter", "constitute", "establishment", "squeeze", "imagination", "mask", "convert", "comprehensive", "prominent", "presentation", "regardless", "load", "stable", "introduction", "pretend", "elderly", "representation", "deer", "split", "violate", "partnership", "pollution", "emission", "steady", "vital", "fate", "earnings", "oven", "distinction", "segment", "nowhere", "poet", "mere", "exciting", "variation", "comfort", "radical", "adapt", "Irish", "honey", "correspondent", "pale", "musician", "significance", "vessel", "storage", "flee", "mm-hmm", "leather", "distribute", "evolution", "ill", "tribe", "shelf", "grandfather", "lawn", "buyer", "dining", "wisdom", "council", "vulnerable", "instance", "garlic", "capability", "poetry", "celebrity", "gradually", "stability", "fantasy", "scared", "plot", "framework", "gesture", "depending", "ongoing", "psychology", "counselor", "chapter", "divorce", "owe", "pipe", "athletic", "slight", "math", "shade", "tail", "sustain", "mount", "obligation", "angle", "palm", "differ", "custom", "economist", "fifteen", "soup", "celebration", "efficient", "composition", "satisfy", "pile", "briefly", "carbon", "closer", "consume", "scheme", "crack", "frequency", "tobacco", "survivor", "besides", "psychologist", "wealthy", "galaxy", "given", "ski", "limitation", "trace", "appointment", "preference", "meter", "explosion", "publicly", "incredible", "fighter", "rapid", "admission", "hunter", "educate", "painful", "friendship", "aide", "infant", "calculate", "fifty", "rid", "porch", "tendency", "uniform", "formation", "scholarship", "reservation", "efficiency", "qualify", "mall", "derive", "scandal", "PC", "helpful", "impress", "heel", "resemble", "privacy", "fabric", "contest", "proportion", "guideline", "rifle", "maintenance", "conviction", "trick", "organic", "tent", "examination", "publisher", "strengthen", "proposed", "myth", "sophisticated", "cow", "etc", "standing", "asleep", "tennis", "nerve", "barrel", "bombing", "membership", "ratio", "menu", "controversial", "desperate", "lifestyle", "humor", "loud", "glove", "sufficient", "narrative", "photographer", "helicopter", "modest", "provider", "delay", "agricultural", "explode", "stroke", "scope", "punishment", "handful", "badly", "horizon", "curious", "downtown", "girlfriend", "prompt", "cholesterol", "absorb", "adjustment", "taxpayer", "eager", "principal", "detailed", "motivation", "assignment", "restriction", "laboratory", "workshop", "differently", "auto", "romantic", "cotton", "motor", "sue", "flavor", "overlook", "float", "undergo", "sequence", "demonstration", "jet", "orange", "consumption", "assert", "blade", "temporary", "medication", "cabin", "bite", "edition", "valley", "yours", "pitch", "pine", "brilliant", "versus", "manufacturing", "absolute", "chef", "discrimination", "offensive", "boom", "register", "appoint", "heritage", "God", "dominant", "successfully", "shit", "lemon", "hungry", "wander", "submit", "economics", "naked", "anticipate", "nut", "legacy", "extension", "shrug", "battery", "arrival", "legitimate", "orientation", "inflation", "cope", "flame", "cluster", "wound", "dependent", "shower", "institutional", "depict", "operating", "flesh", "garage", "operator", "instructor", "collapse", "borrow", "furthermore", "comedy", "mortgage", "sanction", "civilian", "twelve", "weekly", "habitat", "grain", "brush", "consciousness", "devote", "measurement", "province", "ease", "seize", "ethics", "nomination", "permission", "wise", "actress", "summit", "acid", "odds", "gifted", "frustration", "medium", "physically", "distinguish", "shore", "repeatedly", "lung", "running", "distinct", "artistic", "discourse", "basket", "ah", "fighting", "impressive", "competitor", "ugly", "worried", "portray", "powder", "ghost", "persuade", "moderate", "subsequent", "continued", "cookie", "carrier", "cooking", "frequent", "ban", "awful", "admire", "pet", "miracle", "exceed", "rhythm", "widespread", "killing", "lovely", "sin", "charity", "script", "tactic", "identification", "transformation", "everyday", "headline", "venture", "invasion", "nonetheless", "adequate", "piano", "grocery", "intensity", "exhibit", "blanket", "margin", "quarterback", "mouse", "rope", "concrete", "prescription", "African-American", "chase", "brick", "recruit", "patch", "consensus", "horror", "recording", "changing", "painter", "colonial", "pie", "sake", "gaze", "courage", "pregnancy", "swear", "defeat", "clue", "reinforce", "confusion", "slice", "occupation", "dear", "coal", "sacred", "formula", "cognitive", "collective", "exact", "uncle", "captain", "sigh", "attribute", "dare", "homeless", "gallery", "soccer", "defendant", "tunnel", "fitness", "lap", "grave", "toe", "container", "virtue", "abroad", "architect", "dramatically", "makeup", "inquiry", "rose", "surprisingly", "highlight", "decrease", "indication", "rail", "anniversary", "couch", "alliance", "hypothesis", "boyfriend", "compose", "mess", "legend", "regulate", "adolescent", "shine", "norm", "upset", "remark", "resign", "reward", "gentle", "related", "organ", "lightly", "concerning", "invent", "laughter", "northwest", "counseling", "receiver", "ritual", "insect", "interrupt", "salmon", "trading", "magic", "superior", "combat", "stem", "surgeon", "acceptable", "physics", "rape", "counsel", "jeans", "hunt", "continuous", "log", "echo", "pill", "excited", "sculpture", "compound", "integrate", "flour", "bitter", "bare", "slope", "rent", "presidency", "serving", "subtle", "greatly", "bishop", "drinking", "acceptance", "pump", "candy", "evil", "pleased", "medal", "beg", "sponsor", "ethical", "secondary", "slam", "export", "experimental", "melt", "midnight", "curve", "integrity", "entitle", "evident", "logic", "essence", "exclude", "harsh", "closet", "suburban", "greet", "interior", "corridor", "retail", "pitcher", "march", "snake", "excuse", "weakness", "pig", "classical", "estimated", "T-shirt", "unemployment", "civilization", "fold", "reverse", "missing", "correlation", "humanity", "flash", "developer", "reliable", "excitement", "beef", "Islam", "Roman", "architecture", "occasional", "administrative", "elbow", "deadly", "Hispanic", "allegation", "confuse", "airplane", "monthly", "duck", "dose", "Korean", "plead", "initiate", "lecture", "van", "sixth", "bay", "mainstream", "suburb", "sandwich", "trunk", "rumor", "implementation", "swallow", "motivate", "render", "longtime", "trap", "restrict", "cloth", "seemingly", "legislative", "effectiveness", "enforce", "lens", "inspector", "lend", "plain", "fraud", "companion", "contend", "nail", "array", "strict", "assemble", "frankly", "rat", "hay", "hallway", "cave", "inevitable", "southwest", "monster", "unexpected", "obstacle", "facilitate", "rip", "herb", "overwhelming", "integration", "crystal", "recession", "written", "motive", "flood", "pen", "ownership", "nightmare", "inspection", "supervisor", "consult", "arena", "diagnosis", "possession", "forgive", "consistently", "basement", "drift", "drain", "prosecution", "maximum", "announcement", "warrior", "prediction", "bacteria", "questionnaire", "mud", "infrastructure", "hurry", "privilege", "temple", "outdoor", "suck", "and/or", "broadcast", "re", "leap", "random", "wrist", "curtain", "pond", "domain", "guilt", "cattle", "walking", "playoff", "minimum", "fiscal", "skirt", "dump", "hence", "database", "uncomfortable", "execute", "limb", "ideology", "tune", "continuing", "harm", "railroad", "endure", "radiation", "horn", "chronic", "peaceful", "innovation", "strain", "guitar", "replacement", "behave", "administer", "simultaneously", "dancer", "amendment", "pad", "transmission", "await", "retired", "trigger", "spill", "grateful", "grace", "virtual", "colony", "adoption", "indigenous", "closed", "convict", "towel", "modify", "particle", "prize", "landing", "boost", "bat", "alarm", "festival", "grip", "weird", "undermine", "freshman", "sweat", "outer", "drunk", "separation", "traditionally", "govern", "southeast", "intelligent", "wherever", "ballot", "rhetoric", "convinced", "driving", "vitamin", "enthusiasm", "accommodate", "praise", "injure", "wilderness", "endless", "mandate", "respectively", "uncertainty", "chaos", "mechanical", "canvas", "forty", "lobby", "profound", "format", "trait", "currency", "turkey", "reserve", "beam", "astronomer", "corruption", "contractor", "apologize", "doctrine", "genuine", "thumb", "unity", "compromise", "horrible", "behavioral", "exclusive", "scatter", "commonly", "convey", "twist", "complexity", "fork", "disk", "relieve", "suspicion", "health-care", "residence", "shame", "meaningful", "sidewalk", "Olympics", "technological", "signature", "pleasant", "wow", "suspend", "rebel", "frozen", "spouse", "fluid", "pension", "resume", "theoretical", "sodium", "promotion", "delicate", "forehead", "rebuild", "bounce", "electrical", "hook", "detective", "traveler", "click", "compensation", "exit", "attraction", "dedicate", "altogether", "pickup", "carve", "needle", "belly", "scare", "portfolio", "shuttle", "invisible", "timing", "engagement", "ankle", "transaction", "rescue", "counterpart", "historically", "firmly", "mild", "rider", "doll", "noon", "amid", "identical", "precise", "anxious", "structural", "residential", "diagnose", "carbohydrate", "liberty", "poster", "theology", "nonprofit", "crawl", "oxygen", "handsome", "sum", "provided", "businessman", "promising", "conscious", "determination", "donor", "hers", "pastor", "jazz", "opera", "acquisition", "pit", "hug", "wildlife", "punish", "equity", "doorway", "departure", "elevator", "teenage", "guidance", "happiness", "statue", "pursuit", "repair", "decent", "gym", "oral", "clerk", "envelope", "reporting", "destination", "fist", "endorse", "exploration", "generous", "bath", "thereby", "indicator", "sunlight", "feedback", "spectrum", "purple", "laser", "bold", "reluctant", "starting", "expertise", "practically", "eating", "hint", "sharply", "parade", "realm", "cancel", "blend", "therapist", "peel", "pizza", "recipient", "hesitate", "flip", "accounting", "bias", "huh", "metaphor", "candle", "judicial", "entity", "suffering", "full-time", "lamp", "garbage", "servant", "regulatory", "diplomatic", "elegant", "reception", "vanish", "automatically", "chin", "necessity", "confess", "racism", "starter", "banking", "casual", "gravity", "enroll", "diminish", "prevention", "minimize", "chop", "performer", "intent", "isolate", "inventory", "productive", "assembly", "civic", "silk", "magnitude", "steep", "hostage", "collector", "popularity", "alien", "dynamic", "scary", "equation", "angel", "offering", "rage", "photography", "toilet", "disappointed", "precious", "prohibit", "realistic", "hidden", "tender", "gathering", "outstanding", "stumble", "lonely", "automobile", "artificial", "dawn", "abstract", "descend", "silly", "tide", "shared", "hopefully", "readily", "cooperate", "revolutionary", "romance", "hardware", "pillow", "kit", "continent", "seal", "circuit", "ruling", "shortage", "annually", "lately", "scan", "fool", "deadline", "rear", "processing", "ranch", "coastal", "undertake", "softly", "burning", "verbal", "tribal", "ridiculous", "automatic", "diamond", "credibility", "import", "sexually", "divine", "sentiment", "cart", "oversee", "o'clock", "elder", "pro", "inspiration", "Dutch", "quantity", "trailer", "mate", "Greek", "genius", "monument", "bid", "quest", "sacrifice", "invitation", "accuracy", "juror", "officially", "broker", "treasure", "loyalty", "talented", "gasoline", "stiff", "output", "nominee", "extended", "diabetes", "slap", "toxic", "alleged", "jaw", "grief", "mysterious", "rocket", "donate", "inmate", "tackle", "dynamics", "bow", "ours", "dignity", "carpet", "parental", "bubble", "buddy", "barn", "sword", "seventh", "glory", "tightly", "protective", "tuck", "drum", "faint", "queen", "dilemma", "input", "specialize", "northeast", "shallow", "liability", "sail", "merchant", "stadium", "improved", "bloody", "associated", "withdrawal", "refrigerator", "nest", "thoroughly", "lane", "ancestor", "condemn", "steam", "accent", "optimistic", "unite", "cage", "equip", "shrimp", "homeland", "rack", "costume", "wolf", "courtroom", "statute", "cartoon", "productivity", "grin", "symbolic", "bug", "bless", "aunt", "agriculture", "hostile", "conceive", "combined", "instantly", "bankruptcy", "vaccine", "bonus", "collaboration", "mixed", "opposed", "orbit", "grasp", "patience", "spite", "tropical", "voting", "patrol", "willingness", "revelation", "calm", "jewelry", "Cuban", "haul", "concede", "wagon", "afterward", "spectacular", "ruin", "sheer", "immune", "reliability", "ass", "alongside", "bush", "exotic", "fascinating", "clip", "thigh", "bull", "drawer", "sheep", "discourage", "coordinator", "ideological", "runner", "secular", "intimate", "empire", "cab", "exam", "documentary", "neutral", "biology", "flexible", "progressive", "web", "conspiracy", "casualty", "republic", "execution", "terrific", "whale", "functional", "instinct", "teammate", "aluminum", "whoever", "ministry", "verdict", "instruct", "skull", "self-esteem", "cooperative", "manipulate", "bee", "practitioner", "loop", "edit", "whip", "puzzle", "mushroom", "subsidy", "boil", "tragic", "mathematics", "mechanic", "jar", "earthquake", "pork", "creativity", "safely", "underlying", "dessert", "sympathy", "fisherman", "incredibly", "isolation", "sock", "eleven", "sexy", "entrepreneur", "syndrome", "bureau", "workplace", "ambition", "touchdown", "utilize", "breeze", "costly", "ambitious", "Christianity", "presumably", "influential", "translation", "uncertain", "dissolve", "statistical", "gut", "metropolitan", "rolling", "aesthetic", "spell", "insert", "booth", "helmet", "waist", "expected", "lion", "accomplishment", "royal", "panic", "crush", "actively", "cliff", "minimal", "cord", "fortunately", "cocaine", "illusion", "anonymous", "tolerate", "appreciation", "commissioner", "flexibility", "instructional", "scramble", "casino", "tumor", "decorate", "pulse", "equivalent", "fixed", "experienced", "donation", "diary", "sibling", "irony", "spoon", "midst", "alley", "interact", "soap", "cute", "rival", "short-term", "punch", "pin", "hockey", "passing", "persist", "supplier", "known", "momentum", "purse", "shed", "liquid", "icon", "elephant", "consequently", "legislature", "franchise", "correctly", "mentally", "foster", "bicycle", "encouraging", "cheat", "heal", "fever", "filter", "rabbit", "coin", "exploit", "accessible", "organism", "sensation", "partially", "upstairs", "dried", "conservation", "shove", "backyard", "charter", "stove", "consent", "comprise", "reminder", "alike", "placement", "dough", "grandchild", "dam", "reportedly", "well-known", "surrounding", "ecological", "outfit", "unprecedented", "columnist", "workout", "preliminary", "patent", "shy", "trash", "disabled", "gross", "damn", "hormone", "texture", "pencil", "frontier", "spray", "disclose", "custody", "banker", "beast", "interfere", "oak", "eighth", "notebook", "outline", "attendance", "speculation", "uncover", "behalf", "innovative", "shark", "mill", "installation", "stimulate", "tag", "vertical", "swimming", "fleet", "catalog", "outsider", "desperately", "stance", "compel", "sensitivity", "someday", "instant", "debut", "proclaim", "worldwide", "hike", "required", "confrontation", "colorful", "constitution", "trainer", "Thanksgiving", "scent", "stack", "eyebrow", "sack", "cease", "inherit", "tray", "pioneer", "organizational", "textbook", "uh", "nasty", "shrink", "emerging", "dot", "wheat", "fierce", "envision", "rational", "kingdom", "aisle", "weaken", "protocol", "exclusively", "vocal", "marketplace", "openly", "unfair", "terrain", "deploy", "risky", "pasta", "genre", "distract", "merit", "planner", "depressed", "chunk", "closest", "discount", "ladder", "jungle", "migration", "breathing", "invade", "hurricane", "retailer", "classify", "coup", "ambassador", "density", "supportive", "curiosity", "skip", "aggression", "stimulus", "journalism", "robot", "dip", "likewise", "informal", "Persian", "feather", "sphere", "tighten", "boast", "pat", "perceived", "sole", "publicity", "unfold", "well-being", "validity", "ecosystem", "strictly", "partial", "collar", "weed", "compliance", "streak", "supposedly", "added", "builder", "glimpse", "premise", "specialty", "deem", "artifact", "sneak", "monkey", "mentor", "two-thirds", "listener", "lightning", "legally", "sleeve", "disappointment", "disturb", "rib", "excessive", "high-tech", "debris", "rod", "logical", "ash", "socially", "parish", "slavery", "blank", "commodity", "cure", "mineral", "hunger", "dying", "developmental", "faster", "spare", "halfway", "equality", "cemetery", "harassment", "deliberately", "fame", "regret", "striking", "likelihood", "carrot", "atop", "toll", "rim", "embarrassed", "fucking", "cling", "isolated", "blink", "suspicious", "wheelchair", "squad", "eligible", "processor", "plunge", "demographic", "chill", "refuge", "steer", "legislator", "rally", "programming", "cheer", "outlet", "intact", "vendor", "thrive", "peanut", "chew", "elaborate", "conception", "auction", "steak", "comply", "triumph", "shareholder", "comparable", "transport", "conscience", "calculation", "considerably", "interval", "scratch", "awake", "jurisdiction", "inevitably", "feminist", "constraint", "emotionally", "expedition", "allegedly", "similarity", "butt", "lid", "dumb", "bulk", "sprinkle", "mortality", "philosophical", "conversion", "patron", "municipal", "liver", "harmony", "solely", "tolerance", "goat", "blessing", "banana", "palace", "formerly", "peasant", "neat", "grandparent", "lawmaker", "supermarket", "cruise", "mobile", "calendar", "widow", "deposit", "beard", "brake", "screening", "impulse", "forbid", "fur", "brutal", "predator", "poke", "opt", "voluntary", "valid", "forum", "dancing", "happily", "soar", "removal", "autonomy", "enact", "thread", "landmark", "unhappy", "offender", "coming", "privately", "fraction", "distinctive", "tourism", "threshold", "routinely", "suite", "regulator", "straw", "theological", "exhaust", "globe", "fragile", "objection", "chemistry", "old-fashioned", "crowded", "blast", "prevail", "overnight", "denial", "rental", "fantastic", "fragment", "screw", "warmth", "undergraduate", "headache", "policeman", "projection", "suitable", "graduation", "drill", "cruel", "mansion", "grape", "authorize", "cottage", "driveway", "charm", "sexuality", "loyal", "clay", "balloon", "invention", "ego", "fare", "homework", "disc", "sofa", "availability", "radar", "frown", "regain", "sweater", "rehabilitation", "rubber", "retreat", "molecule", "freely", "favorable", "steadily", "integrated", "ha", "youngster", "premium", "accountability", "overwhelm", "one-third", "contemplate", "update", "spark", "ironically", "fatigue", "speculate", "marker", "preach", "bucket", "blond", "confession", "provoke", "marble", "substantially", "defender", "explicit", "disturbing", "surveillance", "magnetic", "technician", "mutter", "devastating", "depart", "arrow", "trauma", "neighboring", "soak", "ribbon", "meantime", "transmit", "harvest", "consecutive", "coordinate", "spy", "slot", "riot", "nutrient", "citizenship", "severely", "sovereignty", "ridge", "brave", "lighting", "specify", "contributor", "frustrate", "articulate", "importantly", "transit", "dense", "seminar", "electronics", "sunny", "shorts", "swell", "accusation", "soften", "straighten", "terribly", "cue", "bride", "biography", "hazard", "compelling", "seldom", "tile", "economically", "honestly", "troubled", "twentieth", "balanced", "foreigner", "convenience", "delight", "weave", "timber", "till", "accurately", "plea", "bulb", "flying", "sustainable", "devil", "bolt", "cargo", "spine", "seller", "skilled", "managing", "marine", "dock", "organized", "fog", "diplomat", "boring", "sometime", "summary", "missionary", "epidemic", "fatal", "trim", "warehouse", "accelerate", "butterfly", "bronze", "drown", "inherent", "nationwide", "spit", "kneel", "vacuum", "selected", "dictate")
  private val topBrands = HashSet("abbott", "abbottlaboratories", "abbvie", "abbvieinc", "accenture", "ace", "acelimited", "actavis", "actavisinc", "adobe", "adt", "aes", "aescorp", "aetna", "aetnainc", "aflac", "aflacinc", "agilent", "agl", "air", "airgas", "airgasinc", "airlines", "akamai", "alcoa", "alcoainc", "aldrich", "alexion", "alexionpharmaceuticals", "allegheny", "allegion", "allegionplc", "allergan", "allerganinc", "alliance", "allstate", "allstatecorp", "altera", "alteracorp", "altria", "amazon", "ameren", "amerencorp", "america", "american", "ameriprise", "ameriprisefinancial", "amerisourcebergen", "amerisourcebergencorp", "ametek", "ametekinc", "amgen", "amgeninc", "amphenol", "anadarko", "analog", "aon", "aonplc", "apache", "apachecorporation", "apartment", "apple", "appleinc", "applied", "archer", "arts", "assurant", "assurantinc", "autodesk", "autodeskinc", "automatic", "automation", "automotive", "autonation", "autonationinc", "autozone", "autozoneinc", "avalonbay", "avery", "avon", "avonproducts", "baker", "ball", "ballcorp", "bancorp", "bancshares", "bank", "banks", "bard", "bath", "baxter", "beam", "beaminc", "becton", "bectondickinson", "bed", "bemis", "bemiscompany", "berkshire", "berkshirehathaway", "best", "beverage", "beyond", "biogen", "black", "blackrock", "block", "boeing", "boeingcompany", "borgwarner", "boston", "bostonproperties", "bostonscientific", "bowes", "bradstreet", "brands", "brewing", "bristol", "broadcom", "broadcomcorporation", "brown", "bus", "buy", "cable", "cablevision", "cabot", "cainc", "cameron", "campbell", "campbellsoup", "capital", "cardinal", "care", "carefusion", "caremark", "carmax", "carmaxinc", "carnival", "carnivalcorp", "castle", "castparts", "caterpillar", "caterpillarinc", "cbre", "cbregroup", "cbs", "cbscorp", "celgene", "celgenecorp", "centerpoint", "centerpointenergy", "century", "centurylink", "centurylinkinc", "cerner", "charles", "charlesschwab", "chase", "chemical", "chemicals", "chesapeake", "chesapeakeenergy", "chevron", "chevroncorp", "chipotle", "chubb", "chubbcorp", "cigna", "cignacorp", "cincinnati", "cincinnatifinancial", "cintas", "cintascorporation", "circuit", "cisco", "ciscosystems", "citigroup", "citigroupinc", "citrix", "citrixsystems", "city", "clark", "cliffs", "clorox", "cloroxco", "cme", "cms", "cmsenergy", "coach", "coachinc", "coca", "cognizant", "cola", "colgate", "colgatepalmolive", "collins", "com", "comcast", "comcastcorp", "comerica", "comericainc", "communications", "communities", "companies", "company", "computer", "conagra", "connectivity", "conocophillips", "consol", "consolidated", "consolidatededison", "constellation", "constellationbrands", "controls", "coors", "corning", "corninginc", "corp", "corporation", "cos", "costco", "costcoco", "covidien", "covidienplc", "creek", "crown", "csx", "csxcorp", "cummins", "cumminsinc", "cvs", "danaher", "danahercorp", "daniels", "darden", "dardenrestaurants", "data", "davidson", "davita", "davitainc", "decker", "deere", "deereco", "delphi", "delta", "denbury", "dennison", "dentsply", "dentsplyinternational", "depot", "devices", "devon", "diagnostics", "diamond", "dickinson", "digital", "directv", "discover", "discovery", "discoverycommunications", "disney", "dollar", "dollartree", "dominion", "dominionresources", "dover", "dovercorp", "dow", "dowchemical", "drilling", "dte", "duke", "dukeenergy", "dun", "dunbradstreet", "dynamics", "eastman", "eastmanchemical", "eaton", "eatoncorp", "ebay", "ebayinc", "ecolab", "ecolabinc", "edison", "edwards", "edwardslifesciences", "electric", "electronic", "electronicarts", "eli", "emc", "emccorp", "emerson", "emersonelectric", "energy", "engineering", "ensco", "enscoplc", "entergy", "entergycorp", "enterprise", "enterprises", "eog", "eogresources", "eqt", "eqtcorporation", "equifax", "equifaxinc", "equity", "equityresidential", "estee", "etrade", "exelon", "exeloncorp", "expedia", "expediainc", "expeditors", "exploration", "express", "expressscripts", "exxon", "facebook", "facebookinc", "family", "fargo", "fastenal", "fastenalco", "fedex", "fedexcorporation", "fidelity", "fifth", "financial", "first", "firstenergy", "firstenergycorp", "fiserv", "fiservinc", "fisher", "flav", "flir", "flirsystems", "flowserve", "flowservecorporation", "fluor", "fluorcorp", "fmc", "fmccorporation", "foods", "ford", "fordmotor", "forest", "forestlaboratories", "forman", "fossil", "fossilinc", "fox", "frag", "franklin", "franklinresources", "freeport", "frontier", "frontiercommunications", "gamble", "game", "gamestop", "gamestopcorp", "gannett", "gannettco", "gap", "gapthe", "garmin", "garminltd", "gas", "general", "generaldynamics", "generalelectric", "generalmills", "genuine", "genuineparts", "genworth", "gilead", "gileadsciences", "gld", "global", "goldman", "goodyear", "google", "googleinc", "graham", "grainger", "green", "grill", "group", "growth", "grumman", "half", "halliburton", "halliburtonco", "hannifin", "harley", "harleydavidson", "harman", "harris", "harriscorporation", "hartford", "hasbro", "hasbroinc", "hat", "hathaway", "hcp", "hcpinc", "health", "healthcare", "helmerich", "helmerichpayne", "hershey", "hess", "hesscorporation", "hewlett", "hewlettpackard", "hill", "hldg", "holding", "holdings", "home", "homedepot", "homes", "honeywell", "hormel", "horton", "hospira", "hospirainc", "host", "hotels", "hudson", "hughes", "humana", "humanainc", "huntington", "huntingtonbancshares", "idec", "illinois", "inc", "incorporated", "industries", "information", "ingersoll", "instruments", "int", "integrys", "intel", "intelcorp", "interactive", "intercontinentalexchange", "intercontinentalexchangeinc", "international", "internationalpaper", "interpublic", "interpublicgroup", "intl", "intuit", "intuitinc", "intuitive", "invesco", "invescoltd", "investment", "iron", "jabil", "jabilcircuit", "jacobs", "johnson", "johnsoncontrols", "johnsonjohnson", "joy", "jpmorgan", "jude", "juniper", "junipernetworks", "kansas", "kellogg", "kelloggco", "keurig", "keycorp", "kimberly", "kimberlyclark", "kimco", "kimcorealty", "kinder", "kindermorgan", "kla", "kohl", "kors", "kraft", "kroger", "krogerco", "laboratories", "laboratory", "lam", "lamresearch", "lauder", "lauren", "legg", "leggett", "leggettplatt", "leggmason", "lennar", "lennarcorp", "leucadia", "lifesciences", "lilly", "limited", "lincoln", "lincolnnational", "linear", "lines", "lockheed", "loews", "loewscorp", "lorillard", "lorillardinc", "lowe", "lsi", "lsicorporation", "ltd", "lyondellbasell", "macerich", "macerichco", "machines", "macy", "management", "marathon", "marathonpetroleum", "market", "marriott", "marsh", "marshmclennan", "mart", "martin", "masco", "mascocorp", "mason", "mastercard", "mastercardinc", "materials", "mattel", "mattelinc", "mccormick", "mccormickco", "mcdonald", "mcgraw", "mckesson", "mckessoncorp", "mclennan", "mcmoran", "mead", "meadjohnson", "meadwestvaco", "meadwestvacocorporation", "medical", "medtronic", "medtronicinc", "mellon", "merck", "merckco", "metlife", "metlifeinc", "mexican", "mgmt", "michael", "microchip", "microchiptechnology", "micron", "microntechnology", "microsoft", "microsoftcorp", "midland", "mills", "mining", "mobil", "mohawk", "molson", "mondelez", "monsanto", "monsantoco", "monster", "monsterbeverage", "moody", "morgan", "morganstanley", "morris", "mosaic", "motor", "motorola", "motors", "mountain", "murphy", "murphyoil", "myers", "mylan", "mylaninc", "nabors", "nasdaq", "national", "natural", "netapp", "netflix", "netflixinc", "networks", "new", "newell", "newfield", "newmont", "news", "newscorporation", "nextera", "nielsen", "nike", "nikeinc", "nisource", "nisourceinc", "noble", "noblecorp", "nordstrom", "norfolk", "northeast", "northeastutilities", "northern", "northrop", "nrg", "nrgenergy", "nucor", "nucorcorp", "nvidia", "nvidiacorporation", "occidental", "occidentalpetroleum", "offshore", "oil", "oilwell", "omnicom", "omnicomgroup", "omx", "one", "oneok", "oracle", "oraclecorp", "outfitters", "owens", "paccar", "paccarinc", "pacific", "packard", "pall", "pallcorp", "palmolive", "paper", "parcel", "parker", "parkerhannifin", "parts", "patterson", "pattersoncompanies", "paychex", "paychexinc", "payne", "peabody", "peabodyenergy", "pentair", "pentairltd", "people", "pepco", "pepper", "pepsico", "pepsicoinc", "perkinelmer", "perrigo", "petroleum", "petsmart", "petsmartinc", "pfizer", "pfizerinc", "pharmaceuticals", "philip", "phillips", "pinnacle", "pioneer", "pitney", "pitneybowes", "platt", "plc", "plum", "pnc", "polo", "pont", "power", "ppg", "ppgindustries", "ppl", "pplcorp", "praxair", "praxairinc", "precision", "precisioncastparts", "price", "priceline", "principal", "processing", "procter", "proctergamble", "products", "progressive", "progressivecorp", "prologis", "properties", "property", "prudential", "prudentialfinancial", "public", "publicstorage", "pulte", "pvh", "pvhcorp", "qep", "qepresources", "qualcomm", "qualcomminc", "quanta", "quest", "questdiagnostics", "ralph", "rand", "range", "raytheon", "raytheonco", "realty", "red", "regeneron", "regions", "reilly", "reit", "republic", "research", "residential", "resorts", "resources", "restaurants", "reynolds", "robert", "robinson", "rockwell", "rockwellcollins", "roper", "roperindustries", "ross", "rowan", "rowancos", "rowe", "rubber", "rubbermaid", "ryder", "rydersystem", "sachs", "safeway", "safewayinc", "salesforce", "salesforcecom", "sandisk", "sandiskcorporation", "scana", "scanacorp", "schlumberger", "schlumbergerltd", "schwab", "sciences", "scientific", "scripps", "scripts", "seagate", "seagatetechnology", "sealed", "sector", "sempra", "sempraenergy", "serv", "service", "services", "sherwin", "sherwinwilliams", "sigma", "sigmaaldrich", "simon", "slm", "slmcorporation", "smucker", "snap", "snapple", "solar", "solutions", "soup", "southern", "southernco", "southwest", "southwestairlines", "southwestern", "southwesternenergy", "spectra", "squibb", "stanley", "staples", "staplesinc", "starbucks", "starbuckscorp", "starwood", "state", "states", "steel", "stericycle", "stericycleinc", "storage", "stores", "street", "stryker", "strykercorp", "suntrust", "suntrustbanks", "supply", "surgical", "svc", "symantec", "symanteccorp", "sysco", "syscocorp", "system", "systems", "target", "targetcorp", "technologies", "technology", "teco", "tecoenergy", "tencor", "tenet", "teradata", "teradatacorp", "tesoro", "texas", "texasinstruments", "textron", "textroninc", "the", "thermo", "third", "tiffany", "tiffanyco", "timber", "time", "tire", "tjx", "tool", "torchmark", "torchmarkcorp", "total", "tower", "tractor", "trade", "transocean", "transoceanltd", "travelers", "tree", "tripadvisor", "trust", "twenty", "tyco", "tycointernational", "tyson", "tysonfoods", "union", "unionpacific", "united", "unitedtechnologies", "unum", "unumgroup", "urban", "urbanoutfitters", "utilities", "valero", "valeroenergy", "varco", "varian", "ventas", "ventasinc", "verisign", "verisigninc", "verizon", "verizoncommunications", "vertex", "viacom", "viacominc", "visa", "visainc", "vornado", "vulcan", "vulcanmaterials", "wal", "walgreen", "walgreenco", "walt", "warner", "waste", "waters", "waterscorporation", "wellpoint", "wellpointinc", "wells", "wellsfargo", "west", "western", "westerndigital", "weyerhaeuser", "weyerhaeusercorp", "whirlpool", "whirlpoolcorp", "whole", "williams", "williamscos", "windstream", "windstreamcorporation", "wisconsin", "works", "worldwide", "wyndham", "wyndhamworldwide", "wynn", "xcel", "xerox", "xeroxcorp", "xilinx", "xilinxinc", "xlcapital", "xylem", "xyleminc", "yahoo", "yahooinc", "york", "yum", "zimmer", "zimmerholdings", "zions", "zionsbancorp", "zoetis", "zoetisinc")

  private def engrishify(username: String): String = { // care of Jared
    import java.text.Normalizer
    val normalized = Normalizer.normalize(username, Normalizer.Form.NFKD)
    normalized.replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
  }
  private val letterDigitRegex = """\p{L}?\d?""".r
  private def removePunctuation(username: String): String = {
    (letterDigitRegex findAllIn username).mkString("")
  }

  private val letterRegex = """\p{L}""".r
  def lettersOnly(username: String) = {
    (letterRegex findAllIn username).mkString("")
  }

  def normalize(username: String): String = {
    removePunctuation(engrishify(username.toLowerCase))
  }

  // any letter or digit, followed by any letter, digit, ., _, or - (1 to 30 times)
  private val usernameRegex = """([\p{L}\d][\p{L}\d\.\_\-]?){2,30}[\p{L}\d]""".r.pattern
  def isValid(username: String): Boolean = {
    if (usernameRegex.matcher(username).matches) {
      val normalized = normalize(username)
      censorList.filter(w => normalized.indexOf(w) >= 0).isEmpty &&
        !topDomains.contains(normalized) &&
        !commonEnglishWords.contains(normalized) &&
        !topBrands.contains(normalized)
    } else {
      false
    }
  }
}
