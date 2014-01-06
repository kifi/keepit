package com.keepit.commanders


import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.abook.ABookServiceClient

import play.api.libs.json._
import com.google.inject.Inject
import com.keepit.common.social.BasicUserRepo
import com.keepit.social._
import scala.concurrent.Future
import com.keepit.common.usersegment.UserSegment
import com.keepit.common.usersegment.UserSegmentFactory
import scala.util.Try
import com.keepit.common.akka.SafeFuture
import com.keepit.heimdal._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import java.text.Normalizer
import com.keepit.common.logging.Logging
import com.keepit.eliza.ElizaServiceClient
import play.api.libs.json.JsSuccess
import com.keepit.model.SocialConnection
import play.api.libs.json.JsString
import com.keepit.model.SocialUserInfoUserKey
import scala.Some
import com.keepit.social.UserIdentity
import play.api.libs.json.JsObject
import securesocial.core.{UserService, Registry, Identity}


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
  socialGraphPlugin: SocialGraphPlugin,
  bookmarkCommander: BookmarksCommander,
  collectionCommander: CollectionCommander,
  eventContextBuilder: HeimdalContextBuilderFactory,
  heimdalServiceClient: HeimdalServiceClient,
  elizaServiceClient: ElizaServiceClient,
  abookServiceClient: ABookServiceClient) extends Logging {

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
    createDefaultKeeps(newUser.id.get)(eventContextBuilder().build)
    newUser
  }

  private def createDefaultKeeps(userId: Id[User])(implicit context: HeimdalContext): Unit = {
    val contextBuilder = new HeimdalContextBuilder()
    contextBuilder.data ++= context.data
    contextBuilder += ("source", BookmarkSource.default.value) // manually set the source so that it appears in tag analytics
    val keepsByTag = bookmarkCommander.keepWithMultipleTags(userId, DefaultKeeps.orderedKeepsWithTags, BookmarkSource.default)(contextBuilder.build)
    val tagsByName = keepsByTag.keySet.map(tag => tag.name -> tag).toMap
    val keepsByUrl = keepsByTag.values.flatten.map(keep => keep.url -> keep).toMap
    db.readWrite { implicit session => collectionCommander.setCollectionOrdering(userId, DefaultKeeps.orderedTags.map(tagsByName(_).externalId)) }
    bookmarkCommander.setKeepOrdering(userId, DefaultKeeps.orderedKeepsWithTags.map { case (keepInfo, _) => keepsByUrl(keepInfo.url) })
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

  // legacy api -- to be replaced after removing dependencies
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

    def getWithInviteStatus(sci: SocialConnectionInfo)(implicit s: RSession): (SocialConnectionInfo, String) =
      sci -> sci.userId.map(_ => "joined").getOrElse {
        invitationRepo.getByRecipientSocialUserId(sci.id) collect {
          case inv if inv.state != InvitationStates.INACTIVE => "invited"
        } getOrElse ""
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

            (true, "acceptedRequest")
          } getOrElse {
            friendRequestRepo.save(FriendRequest(senderId = userId, recipientId = user.id.get))
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
      db.readWrite(attempts = 2) { implicit s =>
        userConnectionRepo.unfriendConnections(userId, user.id.toSet) > 0
      }
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
      // Support Keeps
      (KeepInfo(title = Some("Install Kifi"), url = "https://www.kifi.com/install", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("How to Use Kifi"), url = "https://www.kifi.com/support", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("Contact Us"), url = "", isPrivate = true), Seq(support)),
      (KeepInfo(title = Some("Kifi is better with more friends"), url = "https://www.kifi.com/friends/invite", isPrivate = true), Seq(support)),

      // Example keeps
      (KeepInfo(title = None, url = "http://www.simplyrecipes.com/recipes/bruschetta_with_tomato_and_basil/", isPrivate = true), Seq(example, recipe)),
      (KeepInfo(title = None, url = "http://www.apple.com/ipad/", isPrivate = true), Seq(example, shopping)),
      (KeepInfo(title = None, url = "http://www.fourseasons.com/borabora/", isPrivate = true), Seq(example, travel)),
      (KeepInfo(title = None, url = "http://twistedsifter.com/2013/01/50-life-hacks-to-simplify-your-world/", isPrivate = true), Seq(example, later)),
      (KeepInfo(title = None, url = "http://www.youtube.com/watch?v=_OBlgSz8sSM", isPrivate = true), Seq(example, funny))
    )
  }
}
