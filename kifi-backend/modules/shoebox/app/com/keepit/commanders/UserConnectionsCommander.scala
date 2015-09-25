package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.commanders.emails.EmailSenderProvider
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.eliza.{ UserPushNotificationCategory, PushNotificationExperiment, ElizaServiceClient }
import com.keepit.graph.GraphServiceClient
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.{ ConnectionInviteAccepted, NewConnectionInvite }
import com.keepit.search.SearchServiceClient
import com.keepit.social.{ BasicUser, SocialGraphPlugin, SocialNetworkType, SocialNetworks }
import com.keepit.typeahead.{ KifiUserTypeahead, SocialUserTypeahead }
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext

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
    socialConnectionRepo: SocialConnectionRepo,
    socialGraphPlugin: SocialGraphPlugin,
    kifiUserTypeahead: KifiUserTypeahead,
    socialUserTypeahead: SocialUserTypeahead,
    emailSender: EmailSenderProvider,
    s3ImageStore: S3ImageStore,
    kifiInstallationCommander: KifiInstallationCommander,
    implicit val defaultContext: ExecutionContext,
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

  def unfriend(userId: Id[User], friendExternalId: ExternalId[User]): Boolean = {
    val friendId = db.readOnlyMaster(attempts = 2) { implicit ro => userRepo.get(friendExternalId).id.get }
    val success = db.readWrite(attempts = 2) { implicit s =>
      userConnectionRepo.unfriendConnections(userId, Set(friendId)) > 0
    }
    if (success) {
      val (friend, user) = db.readOnlyMaster { implicit session =>
        (basicUserRepo.load(friendId), basicUserRepo.load(userId))
      }
      elizaServiceClient.sendToUser(userId, Json.arr("lost_friends", Set(friend)))
      elizaServiceClient.sendToUser(userId, Json.arr("lost_friends", Set(user)))
      Seq(userId, friendId) foreach { id =>
        socialUserTypeahead.refresh(id)
        kifiUserTypeahead.refresh(id)
      }
      searchClient.updateUserGraph()
    }
    success
  }

  def getConnectionsPage(userId: Id[User], page: Int, pageSize: Int): (Seq[ConnectionInfo], Int) = {
    db.readOnlyMaster { implicit s =>
      val searchFriends = searchFriendRepo.getSearchFriends(userId)
      val basicConnections = userConnectionRepo.getBasicUserConnection(userId).sortWith { (c1, c2) => c1.createdAt.isAfter(c2.createdAt) }
      val connectionIds = basicConnections.map(_.userId)
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

  private def sendFriendRequestAcceptedEmailAndNotification(myUserId: Id[User], friend: User): Unit = SafeFuture {
    //sending 'friend request accepted' email && Notification
    val (respondingUser, respondingUserImage) = db.readWrite { implicit session =>
      val respondingUser = userRepo.get(myUserId)
      val respondingUserImage = s3ImageStore.avatarUrlByUser(respondingUser)
      (respondingUser, respondingUserImage)
    }

    val emailF = emailSender.connectionMade(friend.id.get, myUserId, NotificationCategory.User.FRIEND_ACCEPTED)

    val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(friend.id.get, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
    if (canSendPush) {
      elizaServiceClient.sendUserPushNotification(
        userId = friend.id.get,
        message = s"${respondingUser.firstName} ${respondingUser.lastName} accepted your invitation to connect",
        recipient = respondingUser,
        pushNotificationExperiment = PushNotificationExperiment.Experiment1,
        category = UserPushNotificationCategory.UserConnectionAccepted)
    }
    val notifF = elizaServiceClient.sendNotificationEvent(ConnectionInviteAccepted(
      Recipient(friend),
      currentDateTime,
      myUserId
    ))

    emailF flatMap (_ => notifF)
  }

  def sendingFriendRequestEmailAndNotification(request: FriendRequest, myUser: User, recipient: User): Unit = SafeFuture {
    val myUserId = myUser.id.get
    val (requestingUser, requestingUserImage) = db.readWrite { implicit session =>
      val requestingUser = userRepo.get(myUserId)
      val requestingUserImage = s3ImageStore.avatarUrlByExternalId(Some(200), requestingUser.externalId, requestingUser.pictureName.getOrElse("0"), Some("https"))
      (requestingUser, requestingUserImage)
    }

    val emailF = emailSender.friendRequest(recipient.id.get, myUserId)

    val friendReqF = elizaServiceClient.sendNotificationEvent(NewConnectionInvite(
      Recipient(recipient),
      currentDateTime,
      myUser.id.get
    )) map { id =>
      val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(recipient.id.get, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
      if (canSendPush) {
        elizaServiceClient.sendUserPushNotification(
          userId = recipient.id.get,
          message = s"${myUser.fullName} invited you to connect",
          recipient = myUser,
          pushNotificationExperiment = PushNotificationExperiment.Experiment1,
          category = UserPushNotificationCategory.UserConnectionRequest)
      }
    }
    emailF flatMap (_ => friendReqF)
  }

  def friend(sender: User, recipientExtId: ExternalId[User]): (Boolean, String) = {
    val senderId = sender.id.get
    db.readWrite { implicit s =>
      userRepo.getOpt(recipientExtId) map { recipient =>
        val activeOrIgnored = Set(FriendRequestStates.ACTIVE, FriendRequestStates.IGNORED)
        if (userConnectionRepo.areConnected(senderId, recipient.id.get)) {
          (true, "alreadyConnected")
        } else if (friendRequestRepo.getBySenderAndRecipient(senderId, recipient.id.get, activeOrIgnored).isDefined) {
          (true, "alreadySent")
        } else if (friendRequestRepo.getCountBySender(senderId) > 500) {
          (false, "tooManySent")
        } else {
          friendRequestRepo.getBySenderAndRecipient(recipient.id.get, senderId, activeOrIgnored) map { friendReq =>
            userConnectionRepo.addConnections(friendReq.senderId, Set(friendReq.recipientId), requested = true)

            s.onTransactionSuccess {
              log.info("just made a friend! updating typeahead, user graph index now.")
              searchClient.updateUserGraph()
              Seq(friendReq.senderId, friendReq.recipientId) foreach { id =>
                socialUserTypeahead.refresh(id)
                kifiUserTypeahead.refresh(id)
              }
              elizaServiceClient.sendToUser(friendReq.senderId, Json.arr("new_friends", Set(basicUserRepo.load(friendReq.recipientId))))
              elizaServiceClient.sendToUser(friendReq.recipientId, Json.arr("new_friends", Set(basicUserRepo.load(friendReq.senderId))))
              sendFriendRequestAcceptedEmailAndNotification(senderId, recipient)
            }
            (true, "acceptedRequest")
          } getOrElse {
            val request = friendRequestRepo.save(FriendRequest(senderId = senderId, recipientId = recipient.id.get, messageHandle = None))
            sendingFriendRequestEmailAndNotification(request, sender, recipient)
            (true, "sentRequest")
          }
        }
      } getOrElse {
        (false, "noSuchUser")
      }
    }
  }
}
