package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.util.Paginator
import com.keepit.model._
import play.api.libs.json.{ JsNumber, JsObject, Json, JsValue }

import scala.concurrent.{ Future, ExecutionContext }

case class MobileProfileStats(libs: Int, followers: Int, connections: Int)
case class MobileProfileMutualStats(libs: Int, connections: Int)

class MobileUserProfileController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  userRepo: UserRepo,
  basicUserRepo: BasicUserRepo,
  userConnectionRepo: UserConnectionRepo,
  libraryRepo: LibraryRepo,
  userValueRepo: UserValueRepo,
  userCommander: UserCommander,
  userProfileCommander: UserProfileCommander,
  userConnectionsCommander: UserConnectionsCommander,
  friendStatusCommander: FriendStatusCommander,
  libraryCommander: LibraryCommander,
  implicit val executionContext: ExecutionContext)
    extends UserActions with ShoeboxServiceController {

  def profile(username: String) = MaybeUserAction { request =>
    val viewer = request.userOpt
    userCommander.profile(Username(username), viewer) match {
      case None => NotFound(s"can't find username $username")
      case Some(profile) =>
        val (numLibraries, numCollabLibraries, numFollowedLibs, numInvitedLibs) = userProfileCommander.countLibraries(profile.userId, viewer.map(_.id.get))
        val (numConnections, userBiography) = db.readOnlyMaster { implicit s =>
          val numConnections = userConnectionRepo.getConnectionCount(profile.userId)
          val userBio = userValueRepo.getValueStringOpt(profile.userId, UserValueName.USER_DESCRIPTION)
          (numConnections, userBio)
        }

        val jsonFriendInfo = Json.toJson(profile.basicUserWithFriendStatus).as[JsObject]
        val jsonProfileInfo = Json.toJson(UserProfileStats(
          numLibraries = numLibraries,
          numFollowedLibraries = numFollowedLibs,
          numCollabLibraries = numCollabLibraries,
          numKeeps = profile.numKeeps,
          numConnections = numConnections,
          numFollowers = userProfileCommander.countFollowers(profile.userId, viewer.map(_.id.get)),
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
        val imageSize = ProcessedImageSize.Large.idealSize
        filter match {
          case "own" =>
            val libs = if (viewer.exists(_.id == user.id)) {
              Json.toJson(userProfileCommander.getOwnLibrariesForSelf(user, paginator, imageSize).seq)
            } else {
              Json.toJson(userProfileCommander.getOwnLibraries(user, viewer, paginator, imageSize).map(LibraryCardInfo.writesWithoutOwner.writes).seq)
            }
            Future.successful(Ok(Json.obj("own" -> libs)))
          case "following" =>
            val libs = userProfileCommander.getFollowingLibraries(user, viewer, paginator, imageSize).seq
            Future.successful(Ok(Json.obj("following" -> libs)))
          case "invited" =>
            val libs = userProfileCommander.getInvitedLibraries(user, viewer, paginator, imageSize).seq
            Future.successful(Ok(Json.obj("invited" -> libs)))
          case "all" if page == 0 =>
            val ownLibsF = if (viewer.exists(_.id == user.id)) {
              SafeFuture(Json.toJson(userProfileCommander.getOwnLibrariesForSelf(user, paginator, imageSize).seq))
            } else {
              SafeFuture(Json.toJson(userProfileCommander.getOwnLibraries(user, viewer, paginator, imageSize).map(LibraryCardInfo.writesWithoutOwner.writes).seq))
            }
            val followLibsF = SafeFuture(userProfileCommander.getFollowingLibraries(user, viewer, paginator, imageSize).seq)
            val invitedLibsF = SafeFuture(userProfileCommander.getInvitedLibraries(user, viewer, paginator, imageSize).seq)
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

  def getProfileFollowers(username: Username, page: Int = 0, size: Int = 12) = MaybeUserAction.async { request =>
    userCommander.userFromUsername(username) match {
      case None =>
        log.warn(s"can't find username ${username.value}")
        Future.successful(NotFound(s"username ${username.value}"))
      case Some(user) =>
        val viewerIdOpt = request.userIdOpt
        userProfileCommander.getFollowersSortedByRelationship(viewerIdOpt, user.id.get) map { followers =>
          val offset = page * size
          val followersPage = followers.drop(offset).take(size)
          val (followerJsons, userMap) = db.readOnlyMaster { implicit s =>
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
  }

  private def loadProfileStats(userId: Id[User], viewerIdOpt: Option[Id[User]])(implicit session: RSession): MobileProfileStats = {
    val libCount = viewerIdOpt.map(viewerId => libraryRepo.countOwnerLibrariesForOtherUser(userId, viewerId)).getOrElse(libraryRepo.countOwnerLibrariesForAnonymous(userId)) //not cached
    //global
    val followersCount = userProfileCommander.countFollowers(userId, viewerIdOpt)
    val connectionCount = userConnectionRepo.getConnectionCount(userId) //cached
    MobileProfileStats(libs = libCount, followers = followersCount, connections = connectionCount)
  }

  private def loadProfileMutualStats(userId: Id[User], viewerId: Id[User])(implicit session: RSession): MobileProfileMutualStats = {
    val followingLibCount = libraryRepo.countOwnerLibrariesUserFollows(userId, viewerId) //not cached
    val mutualConnectionCount = userConnectionRepo.getMutualConnectionCount(userId, viewerId) //cached
    MobileProfileMutualStats(libs = followingLibCount, connections = mutualConnectionCount)
  }

  private def profileUserJson(user: BasicUserWithFriendStatus, profileStats: MobileProfileStats, profileMutualStatsOpt: Option[MobileProfileMutualStats])(implicit session: RSession): JsValue = {
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
}
