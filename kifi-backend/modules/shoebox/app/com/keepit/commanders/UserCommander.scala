package com.keepit.commanders


import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.store.S3ImageStore
import com.keepit.common.akka.SafeFuture
import com.keepit.common.mail._
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.social.BasicUserRepo
import com.keepit.social.{UserIdentity, SocialNetworks}
import com.keepit.common.usersegment.UserSegment
import com.keepit.common.usersegment.UserSegmentFactory
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.abook.{EmailParserUtils, ABookServiceClient}
import com.keepit.social.{BasicUser, SocialGraphPlugin, SocialNetworkType}
import com.keepit.common.time._
import com.keepit.common.performance.timing
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ContextStringData, HeimdalServiceClient, HeimdalContextBuilder}
import com.keepit.search.SearchServiceClient
import com.keepit.typeahead.PrefixFilter
import com.keepit.typeahead.PrefixMatching
import com.keepit.typeahead.TypeaheadHit
import akka.actor.Scheduler
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Try
import securesocial.core.{Identity, UserService, Registry}
import com.keepit.inject.FortyTwoConfig
import com.keepit.typeahead.socialusers.{KifiUserTypeahead, SocialUserTypeahead}
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.model.SocialConnection
import play.api.libs.json.JsString
import com.keepit.model.SocialUserInfoUserKey
import scala.Some
import com.keepit.model.UserEmailAddress
import com.keepit.inject.FortyTwoConfig
import com.keepit.social.UserIdentity
import com.keepit.heimdal.ContextStringData
import play.api.libs.json.JsSuccess
import com.keepit.model.SocialUserConnectionsKey
import com.keepit.common.mail.EmailAddress
import play.api.libs.json.JsObject
import com.keepit.common.cache.TransactionalCaching
import com.keepit.commanders.emails.EmailOptOutCommander
import com.keepit.common.db.slick.Database.Slave

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
  emailRepo: UserEmailAddressRepo,
  userValueRepo: UserValueRepo,
  userConnectionRepo: UserConnectionRepo,
  basicUserRepo: BasicUserRepo,
  keepRepo: KeepRepo,
  keepClickRepo: KeepClickRepo,
  rekeepRepo: ReKeepRepo,
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
  userImageUrlCache: UserImageUrlCache) extends Logging {


  private val emailRegex = """^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r
  def validateEmails(addresses: EmailInfo*): Boolean = {
    !addresses.map(em => emailRegex.findFirstIn(em.address.address).isDefined).contains(false)
  }

  def updateUserDescription(userId: Id[User], description: String): Unit = {
    db.readWrite { implicit session =>
      val trimmed = description.trim
      if (trimmed != "") {
        userValueRepo.setValue(userId, "user_description", trimmed)
      } else {
        userValueRepo.clearValue(userId, "user_description")
      }
      userRepo.save(userRepo.getNoCache(userId)) // update user index sequence number
    }
  }


  def getConnectionsPage(userId: Id[User], page: Int, pageSize: Int): (Seq[ConnectionInfo], Int) = {
    val infos = db.readOnly { implicit s =>
      val searchFriends = searchFriendRepo.getSearchFriends(userId)
      val connectionIds = userConnectionRepo.getConnectedUsers(userId)
      val unfriendedIds = userConnectionRepo.getUnfriendedUsers(userId)
      val connections = connectionIds.map(_ -> false).toSeq ++ unfriendedIds.map(_ -> true).toSeq
      connections.map { case (friendId, unfriended) =>
        ConnectionInfo(basicUserRepo.load(friendId), friendId, unfriended, searchFriends.contains(friendId))
      }
    }
    (infos.drop(page * pageSize).take(pageSize), infos.size)
  }

  def getFriends(user: User, experiments: Set[ExperimentType]): Set[BasicUser] = {
    val basicUsers = db.readOnly { implicit s =>
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
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424201"), "FortyTwo Engineering", "", "0.jpg"),
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424202"), "FortyTwo Family", "", "0.jpg"),
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424203"), "FortyTwo Product", "", "0.jpg")
      )
    } else {
      Seq()
    }


    // This will eventually be a lot more complex. However, for now, tricking the client is the way to go.
    // ^^^^^^^^^ Unrelated to the offensive code above ^^^^^^^^^
    val kifiSupport = Seq(
      BasicUser(ExternalId[User]("aa345838-70fe-45f2-914c-f27c865bdb91"), "Tamila, Kifi Help", "", "tmilz.jpg"))
    basicUsers ++ iNeededToDoThisIn20Minutes ++ kifiSupport
  }

  private def canMessageAllUsers(userId: Id[User]): Boolean = {
    userExperimentCommander.userHasExperiment(userId, ExperimentType.CAN_MESSAGE_ALL_USERS)
  }

  def socialNetworkInfo(userId: Id[User]) = db.readOnly { implicit s =>
    socialUserInfoRepo.getByUser(userId).map(BasicSocialUser.from)
  }

  def abookInfo(userId:Id[User]) = abookServiceClient.getABookInfos(userId)

  def uploadContactsProxy(userId: Id[User], origin: ABookOriginType, payload: JsValue): Future[Try[ABookInfo]] = {
    abookServiceClient.uploadContacts(userId, origin, payload)
  }

  def getUserInfo(user: User): BasicUserInfo = {
    val (basicUser, description, emails, pendingPrimary, notAuthed) = db.readOnly { implicit session =>
      val basicUser = basicUserRepo.load(user.id.get)
      val description =  userValueRepo.getValueStringOpt(user.id.get, "user_description")
      val emails = emailRepo.getAllByUser(user.id.get)
      val pendingPrimary = userValueRepo.getValueStringOpt(user.id.get, "pending_primary_email").map(EmailAddress(_))
      val notAuthed = socialUserRepo.getNotAuthorizedByUser(user.id.get).map(_.networkType.name)
      (basicUser, description, emails, pendingPrimary, notAuthed)
    }

    def isPrimary(address: EmailAddress) = user.primaryEmail.isDefined && address == user.primaryEmail.get
    val emailInfos = emails.sortBy(e => (isPrimary(e.address), !e.verified, e.id.get.id)).map { email =>
      EmailInfo(
        address = email.address,
        isVerified = email.verified,
        isPrimary = isPrimary(email.address),
        isPendingPrimary = pendingPrimary.isDefined && pendingPrimary.get == email.address
      )
    }
    BasicUserInfo(basicUser, UpdatableUserInfo(description, Some(emailInfos)), notAuthed)
  }

  def getHelpCounts(user: Id[User]): (Int, Int) = {
    //unique keeps, total clicks
    db.readOnly { implicit session => bookmarkClicksRepo.getClickCounts(user) }
  }

  def getKeepAttributionCounts(userId: Id[User]): (Int, Int) = { // (clickCount, rekeepCount)
    db.readOnly(dbMasterSlave = Slave) { implicit ro =>
      val clickCount = keepClickRepo.getClickCountByKeeper(userId)
      val rekeepCount = rekeepRepo.getReKeepCountByKeeper(userId)
      (clickCount, rekeepCount)
    }
  }

  def getUserSegment(userId: Id[User]): UserSegment = {
    val (numBms, numFriends) = db.readOnly{ implicit s => //using cache
      (keepRepo.getCountByUser(userId), userConnectionRepo.getConnectionCount(userId))
    }

    val segment = UserSegmentFactory(numBms, numFriends)
    segment
  }

  def createUser(firstName: String, lastName: String, state: State[User]) = {
    val newUser = db.readWrite { implicit session => userRepo.save(User(firstName = firstName, lastName = lastName, state = state)) }
    SafeFuture {
      createDefaultKeeps(newUser.id.get)
      db.readWrite { implicit session =>
        userValueRepo.setValue(newUser.id.get, "ext_show_keeper_intro", true)
        userValueRepo.setValue(newUser.id.get, "ext_show_search_intro", true)
        userValueRepo.setValue(newUser.id.get, "ext_show_ext_msg_intro", true)
        userValueRepo.setValue(newUser.id.get, "ext_show_find_friends", true)
      }
      searchClient.warmUpUser(newUser.id.get)
      searchClient.updateUserIndex()

    }
    newUser
  }

  def tellAllFriendsAboutNewUserImmediate(newUserId: Id[User], additionalRecipients: Seq[Id[User]]): Unit = synchronized {
    val guardKey = "friendsNotifiedAboutJoining"
    if (!db.readOnly{ implicit session => userValueRepo.getValueStringOpt(newUserId, guardKey).exists(_=="true") }) {
      db.readWrite { implicit session => userValueRepo.setValue(newUserId, guardKey, true) }
      val (newUser, toNotify, id2Email) = db.readOnly { implicit session =>
        val newUser = userRepo.get(newUserId)
        val toNotify = userConnectionRepo.getConnectedUsers(newUserId) ++ additionalRecipients
        val id2Email = toNotify.map { userId =>
          (userId, emailRepo.getByUser(userId))
        }.toMap
        (newUser, toNotify, id2Email)
      }
      val imageUrl = s3ImageStore.avatarUrlByExternalId(Some(200), newUser.externalId, newUser.pictureName.getOrElse("0"), Some("https"))
      toNotify.foreach { userId =>
        val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(id2Email(userId)))}"
        db.readWrite{ implicit session =>
          val user = userRepo.get(userId)
          postOffice.sendMail(ElectronicMail(
            senderUserId = None,
            from = SystemEmailAddress.NOTIFICATIONS,
            fromName = Some(s"${newUser.firstName} ${newUser.lastName} (via Kifi)"),
            to = List(id2Email(userId)),
            subject = s"${newUser.firstName} ${newUser.lastName} joined Kifi",
            htmlBody = views.html.email.friendJoinedInlined(user.firstName, newUser.firstName, newUser.lastName, imageUrl, unsubLink).body,
            textBody = Some(views.html.email.friendJoinedText(user.firstName, newUser.firstName, newUser.lastName, imageUrl, unsubLink).body),
            category = NotificationCategory.User.FRIEND_JOINED)
          )
        }
      }

      elizaServiceClient.sendGlobalNotification(
        userIds = toNotify,
        title = s"${newUser.firstName} ${newUser.lastName} joined Kifi!",
        body = s"Enjoy ${newUser.firstName}'s keeps in your search results and message ${newUser.firstName} directly. Invite friends to join Kifi.",
        linkText = "Invite more friends to Kifi.",
        linkUrl = "https://www.kifi.com/friends/invite",
        imageUrl = imageUrl,
        sticky = false,
        category = NotificationCategory.User.FRIEND_JOINED
      )
    }
  }

  def tellAllFriendsAboutNewUser(newUserId: Id[User], additionalRecipients: Seq[Id[User]]): Unit = {
    delay { synchronized {
      tellAllFriendsAboutNewUserImmediate(newUserId, additionalRecipients)
    }}
  }

  def sendWelcomeEmail(newUser: User, withVerification: Boolean = false, targetEmailOpt: Option[EmailAddress] = None): Unit = {
    val olderUser : Boolean = newUser.createdAt.isBefore(currentDateTime.minus(24*3600*1000)) //users older than 24h get the long form welcome email
    if (!db.readOnly{ implicit session => userValueRepo.getValue(newUser.id.get, UserValues.welcomeEmailSent) }) {
      db.readWrite { implicit session => userValueRepo.setValue(newUser.id.get, UserValues.welcomeEmailSent.name, true) }

      if (withVerification) {
        val url = fortytwoConfig.applicationBaseUrl
        db.readWrite { implicit session =>
          val emailAddr = emailRepo.save(emailRepo.getByAddressOpt(targetEmailOpt.get).get.withVerificationCode(clock.now))
          val verifyUrl = s"$url${com.keepit.controllers.core.routes.AuthController.verifyEmail(emailAddr.verificationCode.get)}"
          userValueRepo.setValue(newUser.id.get, "pending_primary_email", emailAddr.address)

          val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(emailAddr))}"

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
        db.readWrite{ implicit session =>
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

  def doChangePassword(userId:Id[User], oldPassword:String, newPassword:String):Try[Identity] = Try {
    val resOpt = db.readOnly { implicit session =>
      socialUserInfoRepo.getByUser(userId).find(_.networkType == SocialNetworks.FORTYTWO)
    } map { sui =>
      val hasher = Registry.hashers.currentHasher
      val identity = sui.credentials.get
      if (!hasher.matches(identity.passwordInfo.get, oldPassword)) throw new IllegalArgumentException("bad_old_password")
      else {
        val pInfo = Registry.hashers.currentHasher.hash(newPassword)
        UserService.save(UserIdentity(
          userId = sui.userId,
          socialUser = sui.credentials.get.copy(passwordInfo = Some(pInfo))
        ))
      }
    }
    resOpt getOrElse { throw new IllegalArgumentException("no_user") }
  }

  def queryContacts(userId:Id[User], search: Option[String], after:Option[String], limit: Int):Future[Seq[JsObject]] = { // TODO: optimize
    @inline def mkId(email: EmailAddress) = s"email/${email.address}"
    @inline def getEInviteStatus(contactIdOpt:Option[Id[EContact]]):String = { // todo: batch
      contactIdOpt flatMap { contactId =>
        db.readOnly { implicit s =>
          invitationRepo.getBySenderIdAndRecipientEContactId(userId, contactId) map { inv =>
            if (inv.state != InvitationStates.INACTIVE) "invited" else ""
          }
        }
      } getOrElse ""
    }

    abookServiceClient.queryEContacts(userId, limit, search, after) map { paged =>
      val objs = paged.take(limit).map { e =>
        Json.obj("label" -> JsString(e.name.getOrElse("")), "value" -> mkId(e.email), "status" -> getEInviteStatus(e.id))
      }
      log.info(s"[queryContacts(id=$userId)] res(len=${objs.length}):${objs.mkString.take(200)}")
      objs
    }
  }

  implicit val hitOrdering = TypeaheadHit.defaultOrdering[SocialUserBasicInfo]

  // todo(ray):removeme
  def getAllConnections(userId:Id[User], search: Option[String], network: Option[String], after: Option[String], limit: Int):Future[Seq[JsObject]] = { // todo: convert to objects
    val contactsF = if (network.isDefined && network.get == "email") { // todo: revisit
      queryContacts(userId, search, after, limit)
    } else Future.successful(Seq.empty[JsObject])
    @inline def socialIdString(sci: SocialUserBasicInfo) = s"${sci.networkType}/${sci.socialId.id}"

    def getWithInviteStatus(sci: SocialUserBasicInfo)(implicit s: RSession): (SocialUserBasicInfo, String) = {
      sci -> sci.userId.map(_ => "joined").getOrElse {
        invitationRepo.getBySenderIdAndRecipientSocialUserId(userId, sci.id) collect {
          case inv if inv.state == InvitationStates.ACCEPTED || inv.state == InvitationStates.JOINED => {
            // This is a hint that that cache may be stale as userId should be set
            socialUserInfoRepo.getByUser(userId).foreach { socialUser =>
              socialUserConnectionsCache.remove(SocialUserConnectionsKey(socialUser.id.get))
            }
            "joined"
          }
          case inv if inv.state != InvitationStates.INACTIVE => "invited"
        } getOrElse ""
      }
    }

    def getFilteredConnections(infos: Seq[SocialUserBasicInfo])(implicit s: RSession): Seq[TypeaheadHit[SocialUserBasicInfo]] = {
      search match {
        case Some(query) =>
          var ordinal = 0
          val queryTerms = PrefixFilter.tokenize(query)
          infos.map{ info =>
            ordinal += 1
            val name = PrefixFilter.normalize(info.fullName)
            TypeaheadHit[SocialUserBasicInfo](PrefixMatching.distanceWithNormalizedName(name, queryTerms), name, ordinal, info)
          }.collect{ case hit if hit.score < 1000000.0d => hit }
        case None =>
          var ordinal = 0
          infos.map{ info =>
            ordinal += 1
            TypeaheadHit[SocialUserBasicInfo](0, PrefixFilter.normalize(info.fullName), ordinal, info)
          }
      }
    }

    val connections = db.readOnly { implicit s =>
      val infos = socialConnectionRepo.getSocialConnectionInfosByUser(userId).filterKeys(networkType => network.forall(_ == networkType.name))
      val filteredConnections = infos.values.map(getFilteredConnections).flatten.toSeq.sorted.map(_.info)

      (after match {
        case Some(id) => filteredConnections.dropWhile(socialIdString(_) != id) match {
          case hd +: tl => tl
          case tl => tl
        }
        case None => filteredConnections
      }).take(limit).map(getWithInviteStatus)
    }

    val jsConns: Seq[JsObject] = connections.map { conn =>
      Json.obj(
        "label" -> conn._1.fullName,
        "image" -> Json.toJson(conn._1.getPictureUrl(75, 75)),
        "value" -> socialIdString(conn._1),
        "status" -> conn._2
      )
    }
    contactsF map { jsContacts =>
      val jsCombined = jsConns ++ jsContacts
      log.info(s"[getAllConnections($userId)] jsContacts(sz=${jsContacts.size}) jsConns(sz=${jsConns.size})")
      jsCombined
    }
  }

  private def sendFriendRequestAcceptedEmailAndNotification(myUserId: Id[User], friend: User): Unit = SafeFuture {
    //sending 'friend request accepted' email && Notification
    val (respondingUser, respondingUserImage) = db.readWrite { implicit session =>
      val respondingUser = userRepo.get(myUserId)
      val destinationEmail = emailRepo.getByUser(friend.id.get)
      val respondingUserImage = s3ImageStore.avatarUrlByExternalId(Some(200), respondingUser.externalId, respondingUser.pictureName.getOrElse("0"), Some("https"))
      val targetUserImage = s3ImageStore.avatarUrlByExternalId(Some(200), friend.externalId, friend.pictureName.getOrElse("0"), Some("https"))
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



  def friend(myUserId:Id[User], friendUserId: ExternalId[User]):(Boolean, String) = {
    db.readWrite { implicit s =>
      userRepo.getOpt(friendUserId) map { recipient =>
        val openFriendRequests = friendRequestRepo.getBySender(myUserId, Set(FriendRequestStates.ACTIVE))

        if (openFriendRequests.size > 40){
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

  def unfriend(userId:Id[User], id:ExternalId[User]):Boolean = {
    db.readOnly(attempts = 2) { implicit ro => userRepo.getOpt(id) } exists { user =>
      val success = db.readWrite(attempts = 2) { implicit s =>
        userConnectionRepo.unfriendConnections(userId, user.id.toSet) > 0
      }
      if (success) {
        db.readOnly{ implicit session =>
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

  def ignoreFriendRequest(userId:Id[User], id: ExternalId[User]):(Boolean, String) = {
    db.readWrite { implicit s =>
      userRepo.getOpt(id) map { sender =>
        friendRequestRepo.getBySenderAndRecipient(sender.id.get, userId) map { friendRequest =>
          friendRequestRepo.save(friendRequest.copy(state = FriendRequestStates.IGNORED))
          (true, "friend_request_ignored")
        } getOrElse (false, "friend_request_not_found")
      } getOrElse (false, "user_not_found")
    }
  }

  def incomingFriendRequests(userId:Id[User]):Seq[BasicUser] = {
    db.readOnly(attempts = 2) { implicit ro =>
      friendRequestRepo.getByRecipient(userId) map { fr => basicUserRepo.load(fr.senderId) }
    }
  }

  def outgoingFriendRequests(userId:Id[User]):Seq[BasicUser] = {
    db.readOnly(attempts = 2) { implicit ro =>
      friendRequestRepo.getBySender(userId) map { fr => basicUserRepo.load(fr.recipientId) }
    }
  }

  def disconnect(userId:Id[User], networkString: String):(Option[SocialUserInfo], String) = {
    val network = SocialNetworkType(networkString)
    val (thisNetwork, otherNetworks) = db.readOnly { implicit s =>
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

  def includeFriend(userId:Id[User], id:ExternalId[User]):Option[Boolean] = {
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

  def excludeFriend(userId:Id[User], id:ExternalId[User]):Option[Boolean] = {
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
      val pendingPrimary = userValueRepo.getValueStringOpt(userId, "pending_primary_email").map(EmailAddress(_))
      val uniqueEmails = emails.map(_.address).toSet
      val (existing, toRemove) = emailRepo.getAllByUser(userId).partition(em => uniqueEmails contains em.address)
      // Remove missing emails
      for (email <- toRemove) {
        val isPrimary = primaryEmail.isDefined && (primaryEmail.get == email.address)
        val isLast = existing.isEmpty
        val isLastVerified = !existing.exists(em => em != email && em.verified)
        if (!isPrimary && !isLast && !isLastVerified) {
          if (pendingPrimary.isDefined && email.address == pendingPrimary.get) {
            userValueRepo.clearValue(userId, "pending_primary_email")
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
          emailRecordOpt.collect { case emailRecord if emailRecord.userId == userId =>
            if (emailRecord.verified) {
              if (primaryEmail.isEmpty || primaryEmail.get != emailRecord.address) {
                updateUserPrimaryEmail(emailRecord)
              }
            } else {
              userValueRepo.setValue(userId, "pending_primary_email", emailInfo.address)
            }
          }
        }
      }

      userValueRepo.getValueStringOpt(userId, "pending_primary_email").map { pp =>
        emailRepo.getByAddressOpt(EmailAddress(pp)) match {
          case Some(em) =>
            if (em.verified && em.address == pp) {
              updateUserPrimaryEmail(em)
            }
          case None => userValueRepo.clearValue(userId, "pending_primary_email")
        }
      }
    }
  }

  def updateUserPrimaryEmail(primaryEmail: UserEmailAddress)(implicit session: RWSession) = {
    require(primaryEmail.verified, s"Suggested primary email $primaryEmail is not verified")
    userValueRepo.clearValue(primaryEmail.userId, "pending_primary_email")
    val currentUser = userRepo.get(primaryEmail.userId)
    userRepo.save(currentUser.copy(primaryEmail = Some(primaryEmail.address)))
    heimdalClient.setUserProperties(primaryEmail.userId, "$email" -> ContextStringData(primaryEmail.address.address))
  }

  def getUserImageUrl(userId: Id[User], width: Int): Future[String] = {
    val user = db.readOnly { implicit session => userRepo.get(userId) }
    implicit val txn = TransactionalCaching.Implicits.directCacheAccess
    userImageUrlCache.getOrElseFuture(UserImageUrlCacheKey(userId, width)) {
      s3ImageStore.getPictureUrl(Some(width), user, user.pictureName.getOrElse("0"))
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
