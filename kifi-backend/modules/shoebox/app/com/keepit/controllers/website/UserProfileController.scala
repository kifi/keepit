package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.util.Paginator
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

case class ProfileStats(libs: Int, followers: Int, connections: Int)

case class ProfileMutualStats(libs: Int, connections: Int)

class UserProfileController @Inject() (
    db: Database,
    userRepo: UserRepo,
    userValueRepo: UserValueRepo,
    userCommander: UserCommander,
    friendRequestRepo: FriendRequestRepo,
    userConnectionRepo: UserConnectionRepo,
    abookServiceClient: ABookServiceClient,
    userConnectionsCommander: UserConnectionsCommander,
    userProfileCommander: UserProfileCommander,
    val userActionsHelper: UserActionsHelper,
    friendStatusCommander: FriendStatusCommander,
    libraryCommander: LibraryCommander,
    libraryRepo: LibraryRepo,
    basicUserRepo: BasicUserRepo,
    implicit val config: PublicIdConfiguration) extends UserActions with ShoeboxServiceController {

  def getProfile(username: Username) = MaybeUserAction { request =>
    val viewer = request.userOpt
    userCommander.profile(username, viewer) match {
      case None =>
        log.warn(s"can't find username ${username.value}")
        NotFound(s"username ${username.value}")
      case Some(profile) =>
        val (numLibraries, numCollabLibraries, numFollowedLibraries, numInvitedLibs) = libraryCommander.countLibraries(profile.userId, viewer.map(_.id.get))
        val (numConnections, userBiography) = db.readOnlyMaster { implicit s =>
          val numConnections = userConnectionRepo.getConnectionCount(profile.userId)
          val userBio = userValueRepo.getValueStringOpt(profile.userId, UserValueName.USER_DESCRIPTION)
          (numConnections, userBio)
        }

        val jsonFriendInfo = Json.toJson(profile.basicUserWithFriendStatus).as[JsObject]
        val jsonProfileInfo = Json.toJson(UserProfileStats(
          numLibraries = numLibraries,
          numFollowedLibraries = numFollowedLibraries,
          numCollabLibraries = numCollabLibraries,
          numKeeps = profile.numKeeps,
          numConnections = numConnections,
          numFollowers = libraryCommander.countFollowers(profile.userId, viewer.map(_.id.get)),
          numInvitedLibraries = numInvitedLibs,
          biography = userBiography
        )).as[JsObject]
        Ok(jsonFriendInfo ++ jsonProfileInfo)
    }
  }

  def getProfileLibraries(username: Username, page: Int, pageSize: Int, filter: String) = MaybeUserAction.async { request =>
    userCommander.userFromUsername(username) match {
      case None =>
        log.warn(s"unknown username ${username.value} requested")
        Future.successful(NotFound(username.value))
      case Some(user) =>
        val viewer = request.userOpt
        val paginator = Paginator(page, pageSize)
        val imageSize = ProcessedImageSize.Medium.idealSize
        filter match {
          case "own" =>
            val libs = if (viewer.exists(_.id == user.id)) {
              Json.toJson(libraryCommander.getOwnProfileLibrariesForSelf(user, paginator, imageSize).seq)
            } else {
              Json.toJson(libraryCommander.getOwnProfileLibraries(user, viewer, paginator, imageSize).map(LibraryCardInfo.writesWithoutOwner.writes).seq)
            }
            Future.successful(Ok(Json.obj("own" -> libs)))
          case "following" =>
            val libs = libraryCommander.getFollowingLibraries(user, viewer, paginator, imageSize).seq
            Future.successful(Ok(Json.obj("following" -> libs)))
          case "invited" =>
            val libs = libraryCommander.getInvitedLibraries(user, viewer, paginator, imageSize).seq
            Future.successful(Ok(Json.obj("invited" -> libs)))
          case "all" if page == 0 =>
            val ownLibsF = if (viewer.exists(_.id == user.id)) {
              SafeFuture(Json.toJson(libraryCommander.getOwnProfileLibrariesForSelf(user, paginator, imageSize).seq))
            } else {
              SafeFuture(Json.toJson(libraryCommander.getOwnProfileLibraries(user, viewer, paginator, imageSize).map(LibraryCardInfo.writesWithoutOwner.writes).seq))
            }
            val followLibsF = SafeFuture(libraryCommander.getFollowingLibraries(user, viewer, paginator, imageSize).seq)
            val invitedLibsF = SafeFuture(libraryCommander.getInvitedLibraries(user, viewer, paginator, imageSize).seq)
            for {
              ownLibs <- ownLibsF
              followLibs <- followLibsF
              invitedLibs <- invitedLibsF
            } yield {
              Ok(Json.obj(
                "own" -> ownLibs,
                "following" -> followLibs,
                "invited" -> invitedLibs
              ))
            }
          case "all" if page != 0 =>
            Future.successful(BadRequest(Json.obj("error" -> "cannot_page_all_filters")))
          case _ =>
            Future.successful(BadRequest(Json.obj("error" -> "no_such_filter")))
        }
    }
  }

  def getMutualLibraries(id: ExternalId[User], page: Int = 0, size: Int = 12) = UserAction { request =>
    db.readOnlyMaster { implicit s =>
      userRepo.getOpt(id)
    } match {
      case None =>
        log.warn(s"unknown external userId ${id} requested")
        NotFound(Json.obj("id" -> id))
      case Some(user) =>
        val viewer = request.userId
        val userId = user.id.get
        val (ofUser, ofViewer, mutualFollow, basicUsers) = db.readOnlyReplica { implicit s =>
          val ofUser = libraryRepo.getOwnerLibrariesOtherFollow(userId, viewer)
          val ofViewer = libraryRepo.getOwnerLibrariesOtherFollow(viewer, userId)
          val mutualFollow = libraryRepo.getMutualLibrariesForUser(viewer, userId, page * size, size)
          val mutualFollowOwners = mutualFollow.map(_.ownerId)
          val basicUsers = basicUserRepo.loadAll(Set(userId, viewer) ++ mutualFollowOwners)
          (ofUser, ofViewer, mutualFollow, basicUsers)
        }
        Ok(Json.obj(
          "ofUser" -> Json.toJson(ofUser.map(LibraryInfo.fromLibraryAndOwner(_, None, basicUsers(userId)))),
          "ofOwner" -> Json.toJson(ofViewer.map(LibraryInfo.fromLibraryAndOwner(_, None, basicUsers(viewer)))),
          "mutualFollow" -> Json.toJson(mutualFollow.map(lib => LibraryInfo.fromLibraryAndOwner(lib, None, basicUsers(lib.ownerId))))
        ))
    }
  }

  def getProfileConnections(username: Username, limit: Int) = MaybeUserAction.async { request =>
    userCommander.userFromUsername(username) match {
      case None =>
        log.warn(s"can't find username ${username.value}")
        Future.successful(NotFound(s"username ${username.value}"))
      case Some(user) =>
        val viewerIdOpt = request.userIdOpt
        val invitations: Seq[JsValue] = if (viewerIdOpt.exists(_ == user.id.get)) {
          val viewerId = viewerIdOpt.get
          db.readOnlyMaster { implicit s =>
            val friendRequests = friendRequestRepo.getByRecipient(user.id.get).map(_.senderId) //not cached
            val userMap = basicUserRepo.loadAll(friendRequests.toSet)
            val augmentedFriends = {
              friendStatusCommander.augmentUsers(viewerId, userMap, Set.empty)
            }
            val friendsWithStatus = {
              friendRequests.map(fr => fr -> augmentedFriends(fr))
            }
            friendsWithStatus map {
              case (userId, userWFS) =>
                loadProfileUser(userId, userWFS, viewerIdOpt, user.id)
            }
          }
        } else Seq.empty
        userProfileCommander.getConnectionsSortedByRelationship(viewerIdOpt.orElse(user.id).get, user.id.get) map { connections =>
          val head = connections.take(limit)
          val (headUserJsonObjs, userMap) = db.readOnlyMaster { implicit s =>
            val userMap = basicUserRepo.loadAll(connections.take(200).map(_.userId).toSet)
            val headUserMap = Map(head.map(c => c.userId -> userMap(c.userId)): _*)
            val headUserWithStatus = viewerIdOpt.map { viewerId =>
              val headFriendIdSet = head.filter(_.connected).map(_.userId).toSet
              friendStatusCommander.augmentUsers(viewerId, headUserMap, headFriendIdSet)
            } getOrElse headUserMap.mapValues(BasicUserWithFriendStatus.fromWithoutFriendStatus)
            val sortedHeadUserWithStatus = {
              head.map(id => id.userId -> headUserWithStatus(id.userId))
            }
            val headUserJsonObjs = sortedHeadUserWithStatus map {
              case (userId, userWFS) =>
                loadProfileUser(userId, userWFS, viewerIdOpt, user.id)
            }
            (headUserJsonObjs, userMap)
          }
          val extIds = connections.drop(limit).flatMap(u => userMap.get(u.userId)).map(_.externalId)
          val res = Json.obj("users" -> headUserJsonObjs, "ids" -> extIds, "count" -> connections.size)
          if (invitations.nonEmpty) {
            Ok(res.as[JsObject] ++ Json.obj("invitations" -> invitations))
          } else Ok(res)
        }
    }
  }

  def getProfileFollowers(username: Username, limit: Int) = MaybeUserAction.async { request =>
    userCommander.userFromUsername(username) match {
      case None =>
        log.warn(s"can't find username ${username.value}")
        Future.successful(NotFound(s"username ${username.value}"))
      case Some(user) =>
        val viewerIdOpt = request.userIdOpt
        userProfileCommander.getFollowersSortedByRelationship(viewerIdOpt, user.id.get) map { followers =>
          val head = followers.take(limit)
          val (headUserJsonObjs, userMap) = db.readOnlyMaster { implicit s =>
            val userMap = basicUserRepo.loadAll(followers.take(200).map(_.userId).toSet)
            val headUserMap = Map(head.map(c => c.userId -> userMap(c.userId)): _*)
            val headUserWithStatus = viewerIdOpt.map { viewerId =>
              val headFriendIdSet = head.filter(_.connected).map(_.userId).toSet
              friendStatusCommander.augmentUsers(viewerId, headUserMap, headFriendIdSet)
            } getOrElse headUserMap.mapValues(BasicUserWithFriendStatus.fromWithoutFriendStatus)
            val sortedHeadUserWithStatus = {
              head.map(id => id.userId -> headUserWithStatus(id.userId))
            }
            val headUserJsonObjs = sortedHeadUserWithStatus map {
              case (cardUserId, cardUserWFS) =>
                loadProfileUser(cardUserId, cardUserWFS, viewerIdOpt, user.id)
            }
            (headUserJsonObjs, userMap)
          }
          val extIds = followers.drop(limit).flatMap(u => userMap.get(u.userId)).map(_.externalId)
          Ok(Json.obj("users" -> headUserJsonObjs, "ids" -> extIds, "count" -> followers.size))
        }
    }
  }

  def getProfileUsers(userExtIds: String) = MaybeUserAction { request =>
    Try(userExtIds.split('.').map(ExternalId[User])) match {
      case Success(userIds) =>
        val viewerIdOpt = request.userIdOpt
        val userJsonObjs = db.readOnlyMaster { implicit s =>
          val userMap = userRepo.getAllUsersByExternalId(userIds).map {
            case (extId, user) =>
              user.id.get -> BasicUser.fromUser(user)
          }
          viewerIdOpt.map { viewerId =>
            friendStatusCommander.augmentUsers(viewerId, userMap)
          } getOrElse userMap.mapValues(BasicUserWithFriendStatus.fromWithoutFriendStatus) map {
            case (cardUserId, cardUserWFS) =>
              loadProfileUser(cardUserId, cardUserWFS, viewerIdOpt, None)
          }
        }
        Ok(Json.obj("users" -> userJsonObjs))
      case _ =>
        BadRequest("ids invalid")
    }
  }

  /**
   * @param fullInfoLimit trying to get at least this number of full card info
   * @param maxExtraIds if available, returning external id of additional maxExtraIds ids that the frontend can iterate on as it does with getProfileConnections
   */
  def getFriendRecommendations(fullInfoLimit: Int, maxExtraIds: Int) = UserAction.async { request =>
    abookServiceClient.getFriendRecommendations(request.userId, 0, fullInfoLimit + maxExtraIds, true) map {
      case None => Ok(Json.obj("users" -> JsArray()))
      case Some(recommendedUserIds) => {
        val head = recommendedUserIds.take(fullInfoLimit)
        val (headUserJsonObjs, userMap) = db.readOnlyMaster { implicit s =>
          val userMap = basicUserRepo.loadAll(recommendedUserIds.toSet)
          val headUserMap = Map(head.map(id => id -> userMap(id)): _*)
          val headUserWithStatus = headUserMap.mapValues(BasicUserWithFriendStatus.fromWithoutFriendStatus)
          val sortedHeadUserWithStatus = head.map(id => id -> headUserWithStatus(id))
          val headUserJsonObjs = sortedHeadUserWithStatus map {
            case (userId, userWFS) =>
              loadProfileUser(userId, userWFS, request.userIdOpt, request.userIdOpt)
          }
          (headUserJsonObjs, userMap)
        }
        val extIds = recommendedUserIds.drop(fullInfoLimit).flatMap(u => userMap.get(u)).map(_.externalId)
        Ok(Json.obj("users" -> headUserJsonObjs, "ids" -> extIds, "count" -> recommendedUserIds.size))
      }
    }
  }

  private def loadProfileStats(userId: Id[User], viewerIdOpt: Option[Id[User]])(implicit session: RSession): ProfileStats = {
    val libCount = viewerIdOpt.map(viewerId => libraryRepo.countLibrariesForOtherUser(userId, viewerId)).getOrElse(libraryRepo.countLibrariesOfUserForAnonymous(userId)) //not cached
    //global
    val followersCount = libraryCommander.countFollowers(userId, viewerIdOpt)
    val connectionCount = userConnectionRepo.getConnectionCount(userId) //cached
    ProfileStats(libs = libCount, followers = followersCount, connections = connectionCount)
  }

  private def loadProfileMutualStats(userId: Id[User], viewerId: Id[User])(implicit session: RSession): ProfileMutualStats = {
    val followingLibCount = libraryRepo.countLibrariesOfOwnerUserFollow(userId, viewerId) //not cached
    val mutualConnectionCount = userConnectionRepo.getMutualConnectionCount(userId, viewerId) //cached
    ProfileMutualStats(libs = followingLibCount, connections = mutualConnectionCount)
  }

  private def profileUserJson(user: BasicUserWithFriendStatus, profileStats: ProfileStats, profileMutualStatsOpt: Option[ProfileMutualStats])(implicit session: RSession): JsValue = {
    val json = Json.toJson(user).as[JsObject]
    //global or personalized
    val jsonWithGlobalCounts = json +
      ("libraries" -> JsNumber(profileStats.libs)) +
      ("followers" -> JsNumber(profileStats.followers)) +
      ("connections" -> JsNumber(profileStats.connections))
    //mutual
    profileMutualStatsOpt.map { profileMutualStats =>
      jsonWithGlobalCounts +
        ("mConnections" -> JsNumber(profileMutualStats.connections)) +
        ("mLibraries" -> JsNumber(profileMutualStats.libs))
    } getOrElse jsonWithGlobalCounts
  }

  def loadProfileUser(userId: Id[User], user: BasicUserWithFriendStatus, viewerIdOpt: Option[Id[User]], profilePageOwnerId: Option[Id[User]])(implicit session: RSession): JsValue = {
    val mutualStatus = viewerIdOpt.map { viewerId =>
      if (viewerId == userId && profilePageOwnerId.nonEmpty) {
        loadProfileMutualStats(profilePageOwnerId.get, viewerId)
      } else {
        loadProfileMutualStats(userId, viewerId)
      }
    }
    profileUserJson(user, loadProfileStats(userId, viewerIdOpt), mutualStatus)
  }

  def getMutualConnections(extUserId: ExternalId[User]) = UserAction { request =>
    db.readOnlyMaster { implicit s =>
      userRepo.getOpt(extUserId)
    } match {
      case Some(user) if user.id.get != request.userId =>
        val userIds = userConnectionsCommander.getMutualFriends(request.userId, user.id.get)
        val (userMap, countMap) = userCommander.loadBasicUsersAndConnectionCounts(userIds, userIds)
        val userJsonObjs = userIds.flatMap { id =>
          userMap.get(id).map { basicUser =>
            Json.toJson(basicUser).as[JsObject] + ("connections" -> JsNumber(countMap(id)))
          }
        }
        Ok(Json.obj("users" -> userJsonObjs, "count" -> userIds.size))
      case Some(_) =>
        BadRequest("self")
      case None =>
        NotFound(s"user id $extUserId")
    }
  }
}
