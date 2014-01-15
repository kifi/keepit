package com.keepit.commanders


import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.store.S3ImageStore
import com.keepit.common.akka.SafeFuture
import com.keepit.common.mail.{PostOffice, LocalPostOffice, ElectronicMail, EmailAddresses, EmailAddressHolder}
import com.keepit.common.social.BasicUserRepo
import com.keepit.social.{UserIdentity, SocialNetworks}
import com.keepit.common.usersegment.UserSegment
import com.keepit.common.usersegment.UserSegmentFactory
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.abook.ABookServiceClient
import com.keepit.social.{BasicUser, SocialGraphPlugin, SocialNetworkType}
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal._

import play.api.Play.current
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject

import java.text.Normalizer

import scala.concurrent.Future
import scala.util.Try
import scala.Some





import securesocial.core.{Identity, UserService, Registry, SocialUser}



case class BasicSocialUser(network: String, profileUrl: Option[String], pictureUrl: Option[String])
object BasicSocialUser {
  implicit val writesBasicSocialUser = Json.writes[BasicSocialUser]
  def from(sui: SocialUserInfo): BasicSocialUser =
    BasicSocialUser(network = sui.networkType.name, profileUrl = sui.getProfileUrl, pictureUrl = sui.getPictureUrl())
}

case class EmailInfo(address: String, isPrimary: Boolean, isVerified: Boolean, isPendingPrimary: Boolean)
object EmailInfo {
  implicit val format = new Format[EmailInfo] {
    def reads(json: JsValue): JsResult[EmailInfo] = {
      Try(new EmailInfo(
        (json \ "address").as[String],
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

case class BasicUserInfo(basicUser: BasicUser, info: UpdatableUserInfo)


class UserCommander @Inject() (
  db: Database,
  userRepo: UserRepo,
  emailRepo: EmailAddressRepo,
  userValueRepo: UserValueRepo,
  userConnectionRepo: UserConnectionRepo,
  basicUserRepo: BasicUserRepo,
  bookmarkRepo: BookmarkRepo,
  userExperimentRepo: UserExperimentRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  socialConnectionRepo: SocialConnectionRepo,
  invitationRepo: InvitationRepo,
  friendRequestRepo: FriendRequestRepo,
  userCache: SocialUserInfoUserCache,
  socialUserConnectionsCache: SocialUserConnectionsCache,
  socialGraphPlugin: SocialGraphPlugin,
  bookmarkCommander: BookmarksCommander,
  collectionCommander: CollectionCommander,
  eventContextBuilder: HeimdalContextBuilderFactory,
  heimdalServiceClient: HeimdalServiceClient,
  abookServiceClient: ABookServiceClient,
  postOffice: LocalPostOffice,
  clock: Clock,
  elizaServiceClient: ElizaServiceClient,
  s3ImageStore: S3ImageStore,
  emailOptOutCommander: EmailOptOutCommander) extends Logging {


  def getFriends(user: User, experiments: Set[ExperimentType]): Set[BasicUser] = {
    val basicUsers = db.readOnly { implicit s =>
      if (canMessageAllUsers(user.id.get)) {
        userRepo.allExcluding(UserStates.PENDING, UserStates.BLOCKED, UserStates.INACTIVE)
          .collect { case u if u.id.get != user.id.get => BasicUser.fromUser(u) }.toSet
      } else {
        userConnectionRepo.getConnectedUsers(user.id.get).map(basicUserRepo.load)
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
      BasicUser(ExternalId[User]("742fa97c-c12a-4dcf-bff5-0f33280ef35a"), "Noah, Kifi Help", "", "Vjy5S.jpg"),
      BasicUser(ExternalId[User]("aa345838-70fe-45f2-914c-f27c865bdb91"), "Tamila, Kifi Help", "", "tmilz.jpg"))
    basicUsers ++ iNeededToDoThisIn20Minutes ++ kifiSupport
  }

  private def canMessageAllUsers(userId: Id[User])(implicit s: RSession): Boolean = {
    userExperimentRepo.hasExperiment(userId, ExperimentType.CAN_MESSAGE_ALL_USERS)
  }

  def socialNetworkInfo(userId: Id[User]) = db.readOnly { implicit s =>
    socialUserInfoRepo.getByUser(userId).map(BasicSocialUser.from)
  }

  def abookInfo(userId:Id[User]) = abookServiceClient.getABookInfos(userId)

  def uploadContactsProxy(userId: Id[User], origin: ABookOriginType, payload: JsValue): Future[Try[ABookInfo]] = {
    abookServiceClient.uploadContacts(userId, origin, payload)
  }

  def getUserInfo(user: User): BasicUserInfo = {
    val (basicUser, description, emails, pendingPrimary) = db.readOnly { implicit session =>
      val basicUser = basicUserRepo.load(user.id.get)
      val description =  userValueRepo.getValue(user.id.get, "user_description")
      val emails = emailRepo.getAllByUser(user.id.get)
      val pendingPrimary = userValueRepo.getValue(user.id.get, "pending_primary_email")
      (basicUser, description, emails, pendingPrimary)
    }

    val primary = user.primaryEmailId.map(_.id).getOrElse(0L)
    val emailInfos = emails.sortBy(e => (e.id.get.id != primary, !e.verified, e.id.get.id)).map { email =>
      EmailInfo(
        address = email.address,
        isVerified = email.verified,
        isPrimary = user.primaryEmailId.isDefined && user.primaryEmailId.get.id == email.id.get.id,
        isPendingPrimary = pendingPrimary.isDefined && pendingPrimary.get == email.address
      )
    }
    BasicUserInfo(basicUser, UpdatableUserInfo(description, Some(emailInfos)))
  }

  def getUserSegment(userId: Id[User]): UserSegment = {
    val (numBms, numFriends) = db.readOnly{ implicit s => //using cache
      (bookmarkRepo.getCountByUser(userId), userConnectionRepo.getConnectionCount(userId))
    }

    val segment = UserSegmentFactory(numBms, numFriends)
    segment
  }

  def createUser(firstName: String, lastName: String, state: State[User]) = {
    val newUser = db.readWrite { implicit session => userRepo.save(User(firstName = firstName, lastName = lastName, state = state)) }
    SafeFuture {
      val contextBuilder = eventContextBuilder()
      contextBuilder += ("action", "registered")
      // more properties to be added after some refactoring in SecureSocialUserServiceImpl
      // requestInfo ???
      // val socialUser: SocialUser = ???
      // contextBuilder += ("identityProvider", socialUser.identityId.providerId)
      // contextBuilder += ("authenticationMethod", socialUser.authMethod.method)
      heimdalServiceClient.trackEvent(UserEvent(newUser.id.get, contextBuilder.build, UserEventTypes.JOINED, newUser.createdAt))
    }
    SafeFuture {
      db.readWrite { implicit session =>
        userValueRepo.setValue(newUser.id.get, "ext_show_keeper_intro", "true")
        userValueRepo.setValue(newUser.id.get, "ext_show_search_intro", "true")
        userValueRepo.setValue(newUser.id.get, "ext_show_find_friends", "true")
      }
      Unit
    }
    newUser
  }

  def tellAllFriendsAboutNewUser(newUserId: Id[User], additionalRecipients: Seq[Id[User]]): Unit = {
    val guardKey = "friendsNotifiedAboutJoining"
    if (!db.readOnly{ implicit session => userValueRepo.getValue(newUserId, guardKey).exists(_=="true") }) {
      db.readWrite { implicit session => userValueRepo.setValue(newUserId, guardKey, "true") }
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
            from = EmailAddresses.NOTIFICATIONS,
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
        body = s"Enjoy ${newUser.firstName}'s keeps in your search results and message ${newUser.firstName} directly.",
        linkText = "Invite more friends to Kifi.",
        linkUrl = "https://www.kifi.com/friends/invite",
        imageUrl = imageUrl,
        sticky = false,
        category = NotificationCategory.User.FRIEND_JOINED
      )
    }
  }

  def sendWelcomeEmail(newUser: User, withVerification: Boolean = false, targetEmailOpt: Option[EmailAddressHolder] = None): Unit = {
    val guardKey = "welcomeEmailSent"
    val olderUser : Boolean = newUser.createdAt.isBefore(currentDateTime.minus(24*3600*1000)) //users older than 24h get the long form welcome email
    if (!db.readOnly{ implicit session => userValueRepo.getValue(newUser.id.get, guardKey).exists(_=="true") }) {
      db.readWrite { implicit session => userValueRepo.setValue(newUser.id.get, guardKey, "true") }

      if (withVerification) {
        val url = current.configuration.getString("application.baseUrl").get
        db.readWrite { implicit session =>
          val emailAddr = emailRepo.save(emailRepo.getByAddressOpt(targetEmailOpt.get.address).get.withVerificationCode(clock.now))
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
            from = EmailAddresses.NOTIFICATIONS,
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
            from = EmailAddresses.NOTIFICATIONS,
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

  def createDefaultKeeps(userId: Id[User])(implicit context: HeimdalContext): Unit = {
    val contextBuilder = new HeimdalContextBuilder()
    contextBuilder.data ++= context.data
    contextBuilder += ("source", BookmarkSource.default.value) // manually set the source so that it appears in tag analytics
    val keepsByTag = bookmarkCommander.keepWithMultipleTags(userId, DefaultKeeps.orderedKeepsWithTags, BookmarkSource.default)(contextBuilder.build)
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

  @inline def normalize(str: String) = Normalizer.normalize(str, Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase

  def queryContacts(userId:Id[User], search: Option[String], after:Option[String], limit: Int):Future[Seq[JsObject]] = { // TODO: optimize
    @inline def mkId(email:String) = s"email/$email"
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

  // todo(eishay): legacy api -- to be replaced after removing dependencies
  def getAllConnections(userId:Id[User], search: Option[String], network: Option[String], after: Option[String], limit: Int):Future[Seq[JsObject]] = { // todo: convert to objects
    val contactsF = if (network.isDefined && network.get == "email") { // todo: revisit
      queryContacts(userId, search, after, limit)
    } else Future.successful(Seq.empty[JsObject])
    @inline def socialIdString(sci: SocialConnectionInfo) = s"${sci.networkType}/${sci.socialId.id}"
    val searchTerms = search.toSeq.map(_.split("\\s+")).flatten.filterNot(_.isEmpty).map(normalize)
    @inline def searchScore(sci: SocialConnectionInfo): Int = {
      if (network.exists(sci.networkType.name !=)) 0
      else if (searchTerms.isEmpty) 1
      else {
        val name = normalize(sci.fullName)
        if (searchTerms.exists(!name.contains(_))) 0
        else {
          val names = name.split("\\s+").filterNot(_.isEmpty)
          names.count(n => searchTerms.exists(n.startsWith))*2 +
            names.count(n => searchTerms.exists(n.contains)) +
            (if (searchTerms.exists(name.startsWith)) 1 else 0)
        }
      }
    }

    def getWithInviteStatus(sci: SocialConnectionInfo)(implicit s: RSession): (SocialConnectionInfo, String) = {
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

    def getFilteredConnections(sui: SocialUserInfo)(implicit s: RSession): Seq[SocialConnectionInfo] =
      if (sui.networkType == SocialNetworks.FORTYTWO) Nil
      else socialConnectionRepo.getSocialConnectionInfo(sui.id.get) filter (searchScore(_) > 0)

    val connections = db.readOnly { implicit s =>
      val filteredConnections = socialUserInfoRepo.getByUser(userId)
        .flatMap(getFilteredConnections)
        .sortBy { case sui => (-searchScore(sui), normalize(sui.fullName)) }

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
      log.info(s"[getAllConnections(${userId})] jsContacts(sz=${jsContacts.size}) jsConns(sz=${jsConns.size})")
      jsCombined
    }
  }

  def friend(userId:Id[User], id: ExternalId[User]):(Boolean, String) = {
    db.readWrite { implicit s =>
      userRepo.getOpt(id) map { user =>
        if (friendRequestRepo.getBySenderAndRecipient(userId, user.id.get).isDefined) {
          (true, "alreadySent")
        } else {
          friendRequestRepo.getBySenderAndRecipient(user.id.get, userId) map { friendReq =>
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

            SafeFuture{
              //sending 'friend request accepted' email && Notification
              val (respondingUser, respondingUserImage) = db.readWrite{ session =>
                val respondingUser = userRepo.get(userId)(session)
                val destinationEmail = emailRepo.getByUser(user.id.get)(session)
                val respondingUserImage = s3ImageStore.avatarUrlByExternalId(Some(200), respondingUser.externalId, respondingUser.pictureName.getOrElse("0"), Some("https"))
                val targetUserImage = s3ImageStore.avatarUrlByExternalId(Some(200), user.externalId, user.pictureName.getOrElse("0"), Some("https"))
                val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(destinationEmail))}"

                postOffice.sendMail(ElectronicMail(
                  senderUserId = None,
                  from = EmailAddresses.NOTIFICATIONS,
                  fromName = Some(s"${respondingUser.firstName} ${respondingUser.lastName} (via Kifi)"),
                  to = List(destinationEmail),
                  subject = s"${respondingUser.firstName} ${respondingUser.lastName} accepted your Kifi friend request",
                  htmlBody = views.html.email.friendRequestAcceptedInlined(user.firstName, respondingUser.firstName, respondingUser.lastName, targetUserImage, respondingUserImage, unsubLink).body,
                  textBody = Some(views.html.email.friendRequestAcceptedText(user.firstName, respondingUser.firstName, respondingUser.lastName, targetUserImage, respondingUserImage, unsubLink).body),
                  category = NotificationCategory.User.FRIEND_ACCEPTED)
                )(session)

                (respondingUser, respondingUserImage)


              }

              elizaServiceClient.sendGlobalNotification(
                userIds = Set(user.id.get),
                title = s"${respondingUser.firstName} ${respondingUser.lastName} accepted your friend request!",
                body = s"Now you will enjoy ${respondingUser.firstName}'s keeps in your search results and you can message ${respondingUser.firstName} directly.",
                linkText = "Invite more friends to kifi",
                linkUrl = "https://www.kifi.com/friends/invite",
                imageUrl = respondingUserImage,
                sticky = false,
                category = NotificationCategory.User.FRIEND_ACCEPTED
              )

            }


            (true, "acceptedRequest")
          } getOrElse {
            friendRequestRepo.save(FriendRequest(senderId = userId, recipientId = user.id.get))

            SafeFuture{
              //sending 'friend request' email && Notification
              val (requestingUser, requestingUserImage) = db.readWrite{ session =>
                val requestingUser = userRepo.get(userId)(session)
                val destinationEmail = emailRepo.getByUser(user.id.get)(session)
                val requestingUserImage = s3ImageStore.avatarUrlByExternalId(Some(200), requestingUser.externalId, requestingUser.pictureName.getOrElse("0"), Some("https"))
                val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(destinationEmail))}"
                postOffice.sendMail(ElectronicMail(
                  senderUserId = None,
                  from = EmailAddresses.NOTIFICATIONS,
                  fromName = Some(s"${requestingUser.firstName} ${requestingUser.lastName} (via Kifi)"),
                  to = List(destinationEmail),
                  subject = s"${requestingUser.firstName} ${requestingUser.lastName} sent you a friend request.",
                  htmlBody = views.html.email.friendRequestInlined(user.firstName, requestingUser.firstName + " " + requestingUser.lastName, requestingUserImage, unsubLink).body,
                  textBody = Some(views.html.email.friendRequestText(user.firstName, requestingUser.firstName + " " + requestingUser.lastName, requestingUserImage, unsubLink).body),
                  category = NotificationCategory.User.FRIEND_REQUEST)
                )(session)

                (requestingUser, requestingUserImage)

              }

              elizaServiceClient.sendGlobalNotification(
                userIds = Set(user.id.get),
                title = s"${requestingUser.firstName} ${requestingUser.lastName} sent you a friend request",
                body = s"Enjoy ${requestingUser.firstName}'s keeps in your search results and message ${requestingUser.firstName} directly.",
                linkText = s"Respond to ${requestingUser.firstName}'s friend request",
                linkUrl = "https://kifi.com/friends/requests",
                imageUrl = requestingUserImage,
                sticky = false,
                category = NotificationCategory.User.FRIEND_REQUEST
              )

            }

            (true, "sentRequest")
          }
        }
      } getOrElse {
        (false, s"User with id $id not found.")
      }
    }
  }

  def unfriend(userId:Id[User], id:ExternalId[User]):Boolean = {
    db.readOnly(attempts = 2) { implicit ro => userRepo.getOpt(id) } map { user =>
      val success = db.readWrite(attempts = 2) { implicit s =>
        userConnectionRepo.unfriendConnections(userId, user.id.toSet) > 0
      }
      if (success) {
        db.readOnly{ implicit session =>
          elizaServiceClient.sendToUser(userId, Json.arr("lost_friends", Set(basicUserRepo.load(user.id.get))))
          elizaServiceClient.sendToUser(user.id.get, Json.arr("lost_friends", Set(basicUserRepo.load(userId))))
        }
      }
      success
    } getOrElse false
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
    userCache.remove(SocialUserInfoUserKey(userId))
    val network = SocialNetworkType(networkString)
    val (thisNetwork, otherNetworks) = db.readOnly { implicit s =>
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
      }
      userCache.remove(SocialUserInfoUserKey(userId))
      val newLoginUser = otherNetworks.find(_.networkType == SocialNetworks.FORTYTWO).getOrElse(otherNetworks.head)
      (Some(newLoginUser), "disconnected")
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
    "Kifi Support"
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
      (KeepInfo(title = Some("kifi • Install Kifi on Firefox and Chrome"), url = "https://www.kifi.com/install", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("kifi • How to Use Kifi"), url = "http://support.kifi.com/customer/portal/articles/1397866-introduction-to-kifi-", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("kifi • Contact Us"), url = "http://support.kifi.com/customer/portal/emails/new", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("kifi • Find friends your friends on Kifi"), url = "https://www.kifi.com/friends/invite", isPrivate = true), Seq(support))
    )
  }
}
