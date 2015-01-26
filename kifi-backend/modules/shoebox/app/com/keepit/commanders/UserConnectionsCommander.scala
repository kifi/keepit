package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.commanders.emails.EmailSenderProvider
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.social.{ BasicUser, SocialGraphPlugin, SocialNetworkType, SocialNetworks }
import com.keepit.typeahead.{ KifiUserTypeahead, SocialUserTypeahead }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import scala.concurrent.Future

case class ConnectionInfo(user: BasicUser, userId: Id[User], unfriended: Boolean, unsearched: Boolean)

class UserConnectionsCommander @Inject() (
    abookServiceClient: ABookServiceClient,
    userConnectionRepo: UserConnectionRepo,
    userExperimentCommander: LocalUserExperimentCommander,
    basicUserRepo: BasicUserRepo,
    userRepo: UserRepo,
    searchFriendRepo: SearchFriendRepo,
    searchClient: SearchServiceClient,
    elizaServiceClient: ElizaServiceClient,
    friendRequestRepo: FriendRequestRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    userCache: SocialUserInfoUserCache,
    socialConnectionRepo: SocialConnectionRepo,
    socialGraphPlugin: SocialGraphPlugin,
    kifiUserTypeahead: KifiUserTypeahead,
    socialUserTypeahead: SocialUserTypeahead,
    emailSender: EmailSenderProvider,
    s3ImageStore: S3ImageStore,
    db: Database) extends Logging {

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

  def getMutualFriends(user1Id: Id[User], user2Id: Id[User]): Set[Id[User]] = {
    val (user1FriendIds, user2FriendIds) = db.readOnlyReplica { implicit session =>
      (userConnectionRepo.getConnectedUsers(user1Id), userConnectionRepo.getConnectedUsers(user2Id))
    }
    user1FriendIds intersect user2FriendIds
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

  def getConnectionsPage(userId: Id[User], page: Int, pageSize: Int): (Seq[ConnectionInfo], Int) = {
    db.readOnlyMaster { implicit s =>
      val searchFriends = searchFriendRepo.getSearchFriends(userId)
      val basicConnections = userConnectionRepo.getBasicUserConnection(userId)
      val connectionIds = basicConnections.sortWith { (c1, c2) => c2.createdAt.isAfter(c1.createdAt) }.map(_.userId)
      val unfriendedIds = userConnectionRepo.getUnfriendedUsers(userId)
      val connections = connectionIds.map(_ -> false) ++ unfriendedIds.map(_ -> true).toSeq

      val list = connections.drop(page * pageSize).take(pageSize)
      val basicUsers = basicUserRepo.loadAll(list.map(_._1).toSet)
      val infos = list.map {
        case (friendId, unfriended) =>
          ConnectionInfo(basicUsers(friendId), friendId, unfriended, searchFriends.contains(friendId))
      }
      (infos, connectionIds.size)
    }
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
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424201"), "FortyTwo Engineering", "", "0.jpg", Username("foo1")),
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424202"), "FortyTwo Family", "", "0.jpg", Username("foo2")),
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424203"), "FortyTwo Product", "", "0.jpg", Username("foo3"))
      )
    } else {
      Seq()
    }

    // This will eventually be a lot more complex. However, for now, tricking the client is the way to go.
    // ^^^^^^^^^ Unrelated to the offensive code above ^^^^^^^^^
    val kifiSupport = Seq(
      BasicUser(ExternalId[User]("aa345838-70fe-45f2-914c-f27c865bdb91"), "Tamila, Kifi Help", "", "tmilz.jpg", Username("foo4")))
    basicUsers ++ iNeededToDoThisIn20Minutes ++ kifiSupport
  }

  private def canMessageAllUsers(userId: Id[User]): Boolean = {
    userExperimentCommander.userHasExperiment(userId, ExperimentType.CAN_MESSAGE_ALL_USERS)
  }

  private def sendFriendRequestAcceptedEmailAndNotification(myUserId: Id[User], friend: User): Unit = SafeFuture {
    //sending 'friend request accepted' email && Notification
    val (respondingUser, respondingUserImage) = db.readWrite { implicit session =>
      val respondingUser = userRepo.get(myUserId)
      val respondingUserImage = s3ImageStore.avatarUrlByUser(respondingUser)
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
      category = NotificationCategory.User.FRIEND_ACCEPTED,
      extra = Some(Json.obj("friend" -> BasicUser.fromUser(respondingUser)))
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
      category = NotificationCategory.User.FRIEND_REQUEST,
      extra = Some(Json.obj("friend" -> BasicUser.fromUser(requestingUser)))
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
        if (userConnectionRepo.areConnected(myUserId, recipient.id.get)) {
          (true, "alreadyConnected")
        } else {
          val openFriendRequests = friendRequestRepo.getBySender(myUserId, Set(FriendRequestStates.ACTIVE))

          if (openFriendRequests.size > 500) {
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
        }
      } getOrElse {
        (false, s"User with id $myUserId not found.")
      }
    }
  }
}
