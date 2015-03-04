package com.keepit.controllers.website

import java.util.concurrent.atomic.AtomicBoolean

import com.google.inject.Inject
import com.keepit.abook.{ ABookServiceClient, ABookUploadConf }
import com.keepit.commanders.emails.EmailSenderProvider
import com.keepit.commanders.{ ConnectionInfo, _ }
import com.keepit.common.controller._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.http._
import com.keepit.common.mail.{ EmailAddress, _ }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ ImageCropAttributes, S3ImageStore }
import com.keepit.common.time._
import com.keepit.controllers.core.NetworkInfoLoader
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ BasicDelightedAnswer, DelightedAnswerSources }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.social.BasicUser
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.Comet
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent.{ Promise => PlayPromise }
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.Json.toJson
import play.api.libs.json.{ JsBoolean, JsNumber, _ }
import play.api.mvc.{ MaxSizeExceeded, Request }
import play.twirl.api.Html
import play.api.libs.json._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

class UserProfileController @Inject() (
    db: Database,
    userRepo: UserRepo,
    userCommander: UserCommander,
    userConnectionRepo: UserConnectionRepo,
    userConnectionsCommander: UserConnectionsCommander,
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
        userConnectionsCommander.getConnectionsSortedByRelationship(viewerIdOpt.orElse(user.id).get, user.id.get) map { connections =>
          val head = connections.take(limit)
          val (headUserJsonObjs, userMap) = db.readOnlyMaster { implicit s =>
            val userMap = basicUserRepo.loadAll(connections.take(200).map(_.userId).toSet)
            val headUserMap = Map(head.map(c => c.userId -> userMap(c.userId)): _*)
            val headUserJsonObjs = viewerIdOpt.map { viewerId =>
              val headFriendIdSet = head.filter(_.connected).map(_.userId).toSet
              friendStatusCommander.augmentUsers(viewerId, headUserMap, headFriendIdSet)
            } getOrElse headUserMap.mapValues(BasicUserWithFriendStatus.fromWithoutFriendStatus) map {
              case (userId, userWFS) =>
                loadProfileUser(userId, userWFS, viewerIdOpt)
            }
            (headUserJsonObjs, userMap)
          }
          val extIds = connections.drop(limit).flatMap(u => userMap.get(u.userId)).map(_.externalId)
          Ok(Json.obj("users" -> headUserJsonObjs, "ids" -> extIds, "count" -> connections.size))
        }
    }
  }

  def getProfileFollowers(username: Username, limit: Int) = MaybeUserAction { request =>
    userCommander.userFromUsername(username) match {
      case None =>
        log.warn(s"can't find username ${username.value}")
        NotFound(s"username ${username.value}")
      case Some(user) =>
        val viewerIdOpt = request.userIdOpt
        val followerIds = libraryCommander.getFollowersByViewer(user.id.get, viewerIdOpt) // todo (aaron): If there is some order by social graph, this will be a future!
        val head = followerIds.take(limit)
        val (headUserJsonObjs, userMap) = db.readOnlyMaster { implicit s =>
          val userMap = basicUserRepo.loadAll(followerIds.toSet)
          val headUserMap = Map(head.map(userId => userId -> userMap(userId)): _*)
          val headUserJsonObjs = viewerIdOpt.map { viewerId =>
            friendStatusCommander.augmentUsers(viewerId, headUserMap)
          } getOrElse headUserMap.mapValues(BasicUserWithFriendStatus.fromWithoutFriendStatus) map {
            case (userId, userWFS) =>
              loadProfileUser(userId, userWFS, viewerIdOpt)
          }
          (headUserJsonObjs, userMap)
        }
        val ids = followerIds.drop(limit).flatMap(userId => userMap.get(userId)).map(_.externalId)
        Ok(Json.obj("users" -> headUserJsonObjs, "ids" -> ids, "count" -> followerIds.size))
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
            case (userId, userWFS) =>
              loadProfileUser(userId, userWFS, viewerIdOpt)
          }
        }
        Ok(Json.obj("users" -> userJsonObjs))
      case _ =>
        BadRequest("ids invalid")
    }
  }

  // visible for testing
  //todo(eishay): caching work!
  def loadProfileUser(userId: Id[User], user: BasicUserWithFriendStatus, viewerIdOpt: Option[Id[User]])(implicit session: RSession): JsValue = {
    val json = Json.toJson(user).as[JsObject]
    //global or personalized
    val libCount = viewerIdOpt.map(viewerId => libraryRepo.countLibrariesForOtherUser(userId, viewerId)).getOrElse(libraryRepo.countLibrariesOfUserFromAnonymous(userId)) //not cached
    //global
    val followersCount = libraryCommander.countFollowers(userId, viewerIdOpt)
    val connectionCount = userConnectionRepo.getConnectionCount(userId) //cached
    val jsonWithGlobalCounts = json +
      ("libraries" -> JsNumber(libCount)) +
      ("followers" -> JsNumber(followersCount)) +
      ("connections" -> JsNumber(connectionCount))
    //mutual
    viewerIdOpt.map { viewerId =>
      val followingLibCount = libraryRepo.countLibrariesOfOwnerUserFollow(userId, viewerId) //not cached
      val mutualConnectionCount = userConnectionRepo.getMutualConnectionCount(userId, viewerId) //cached
      jsonWithGlobalCounts +
        ("mConnections" -> JsNumber(mutualConnectionCount)) +
        ("mLibraries" -> JsNumber(followingLibCount))
    } getOrElse jsonWithGlobalCounts
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
