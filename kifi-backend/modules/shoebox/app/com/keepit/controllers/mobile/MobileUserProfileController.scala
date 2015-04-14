package com.keepit.controllers.mobile

import com.google.inject.{ Provider, Inject }
import com.keepit.commanders.{ UserCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.model._
import play.api.libs.json.{ JsNumber, JsObject, Json, JsValue }

import scala.concurrent.ExecutionContext

case class MobileProfileStats(libs: Int, followers: Int, connections: Int)
case class MobileProfileMutualStats(libs: Int, connections: Int)

class MobileUserProfileController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  userRepo: UserRepo,
  userConnectionRepo: UserConnectionRepo,
  libraryRepo: LibraryRepo,
  userCommander: Provider[UserCommander],
  //userConnectionsCommander: UserConnectionsCommander,
  //libraryCommander: LibraryCommander,
  implicit val executionContext: ExecutionContext)
    extends UserActions with ShoeboxServiceController {

  def getProfileFollowers(username: Username, page: Int = 0, size: Int = 12) = MaybeUserAction { request =>
    Ok

    /*
    userCommander.userFromUsername(username) match {
      case None =>
        log.warn(s"can't find username ${username.value}")
        Future.successful(NotFound(s"username ${username.value}"))
      case Some(user) =>
        val viewerIdOpt = request.userIdOpt
        userProfileCommander.getFollowersSortedByRelationship(viewerIdOpt, user.id.get) map { followers =>
          val offset = page * size
          val followersPage = followers.drop(offset).take(limit)
          val (headUserJsonObjs, userMap) = db.readOnlyMaster { implicit s =>
            val userMap = basicUserRepo.loadAll(followers.take(200).map(_.userId).toSet)
            val followersMap = Map(followersPage.map(c => c.userId -> userMap(c.userId)): _*)
            val followersWithStatus = viewerIdOpt.map { viewerId =>
              val friendIdSet = followersPage.filter(_.connected).map(_.userId).toSet
              friendStatusCommander.augmentUsers(viewerId, followersMap, friendIdSet)
            } getOrElse followersMap.mapValues(BasicUserWithFriendStatus.fromWithoutFriendStatus)
            val sortedFollowersWithStatus = {
              followersPage.map(id => id.userId -> followersWithStatus(id.userId))
            }
            val followerJsons = sortedFollowersWithStatus map {
              case (cardUserId, cardUserWFS) =>
                loadProfileUser(cardUserId, cardUserWFS, viewerIdOpt, user.id)
            }
            (followerJsons, userMap)
          }
          val extIds = followersPage.flatMap(u => userMap.get(u.userId)).map(_.externalId)
          Ok(Json.obj("users" -> followerJsons, "ids" -> extIds, "count" -> followers.size))
        }
    }
     */
  }

  /*
  private def loadProfileStats(userId: Id[User], viewerIdOpt: Option[Id[User]])(implicit session: RSession): MobileProfileStats = {
    val libCount = viewerIdOpt.map(viewerId => libraryRepo.countLibrariesForOtherUser(userId, viewerId)).getOrElse(libraryRepo.countLibrariesOfUserForAnonymous(userId)) //not cached
    //global
    val followersCount = libraryCommander.countFollowers(userId, viewerIdOpt)
    val connectionCount = userConnectionRepo.getConnectionCount(userId) //cached
    MobileProfileStats(libs = libCount, followers = followersCount, connections = connectionCount)
  }

  private def loadProfileMutualStats(userId: Id[User], viewerId: Id[User])(implicit session: RSession): MobileProfileMutualStats = {
    val followingLibCount = libraryRepo.countLibrariesOfOwnerUserFollow(userId, viewerId) //not cached
    val mutualConnectionCount = userConnectionRepo.getMutualConnectionCount(userId, viewerId) //cached
    MobileProfileMutualStats(libs = followingLibCount, connections = mutualConnectionCount)
  }

  private def profileUserJson(user: BasicUserWithFriendStatus, profileStats: ProfileStats, profileMutualStatsOpt: Option[MobileProfileMutualStats])(implicit session: RSession): JsValue = {
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
  */
}