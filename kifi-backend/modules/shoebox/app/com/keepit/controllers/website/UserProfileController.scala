package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.social.BasicUserRepo
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
    userCommander: UserCommander,
    userConnectionRepo: UserConnectionRepo,
    userConnectionsCommander: UserConnectionsCommander,
    userProfileCommander: UserProfileCommander,
    val userActionsHelper: UserActionsHelper,
    friendStatusCommander: FriendStatusCommander,
    libraryCommander: LibraryCommander,
    libraryRepo: LibraryRepo,
    basicUserRepo: BasicUserRepo) extends UserActions with ShoeboxServiceController {

  def getProfile(username: Username) = MaybeUserAction { request =>
    val viewer = request.userOpt
    userCommander.profile(username, viewer) match {
      case None =>
        log.warn(s"can't find username ${username.value}")
        NotFound(s"username ${username.value}")
      case Some(profile) =>
        val (numLibraries, numInvitedLibs) = libraryCommander.countLibraries(profile.userId, viewer.map(_.id.get))
        val numConnections = db.readOnlyMaster { implicit s =>
          userConnectionRepo.getConnectionCount(profile.userId)
        }

        val json = Json.toJson(profile.basicUserWithFriendStatus).as[JsObject] ++ Json.obj(
          "numLibraries" -> numLibraries,
          "numKeeps" -> profile.numKeeps,
          "numConnections" -> numConnections,
          "numFollowers" -> libraryCommander.countFollowers(profile.userId, viewer.map(_.id.get))
        )
        numInvitedLibs match {
          case Some(numInvited) =>
            Ok(json ++ Json.obj("numInvitedLibraries" -> numInvited))
          case _ =>
            Ok(json)
        }
    }
  }

  def getProfileConnections(username: Username, limit: Int) = MaybeUserAction.async { request =>
    userCommander.userFromUsername(username) match {
      case None =>
        log.warn(s"can't find username ${username.value}")
        Future.successful(NotFound(s"username ${username.value}"))
      case Some(user) =>
        val viewerIdOpt = request.userIdOpt
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
          Ok(Json.obj("users" -> headUserJsonObjs, "ids" -> extIds, "count" -> connections.size))
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

  private def loadProfileStats(userId: Id[User], viewerIdOpt: Option[Id[User]])(implicit session: RSession): ProfileStats = {
    val libCount = viewerIdOpt.map(viewerId => libraryRepo.countLibrariesForOtherUser(userId, viewerId)).getOrElse(libraryRepo.countLibrariesOfUserFromAnonymous(userId)) //not cached
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
