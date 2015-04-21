package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import play.api.libs.json.{ JsArray, Json }

class MobileMutualUserController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo,
    userConnectionRepo: UserConnectionRepo,
    libraryRepo: LibraryRepo,
    val config: PublicIdConfiguration) extends UserActions with ShoeboxServiceController {

  def getMutualConnections(userId: ExternalId[User], page: Int = 0, size: Int = 10) = UserAction { request =>
    db.readOnlyMaster { implicit s =>
      userRepo.getOpt(userId) match {
        case None =>
          BadRequest(Json.obj("error" -> "user_not_recognized"))
        case Some(user) =>
          val offset = page * size
          val allConnections = userConnectionRepo.getConnectedUsersForUsers(Set(request.userId, user.id.get))
          val mutualConnections = allConnections(request.userId) intersect allConnections(user.id.get)
          val friendsJson = basicUserRepo.loadAll(mutualConnections).toSeq.drop(offset).take(size) map {
            case (userId, basicUser) =>
              Json.toJson(basicUser)
          }
          Ok(Json.obj("mutualConnections" -> JsArray(friendsJson)))
      }
    }
  }

  def getMutualLibraries(userId: ExternalId[User], page: Int = 0, size: Int = 10) = UserAction { request =>
    db.readOnlyMaster { implicit s =>
      userRepo.getOpt(userId) match {
        case None =>
          BadRequest(Json.obj("error" -> "user_not_recognized"))
        case Some(user) =>
          val offset = page * size
          val mutualLibraries = libraryRepo.getMutualLibrariesForUser(request.userId, user.id.get, offset, size)
          val libOwners = basicUserRepo.loadAll(mutualLibraries.map(_.ownerId).toSet)
          val libsJson = mutualLibraries.map { lib =>
            val owner = libOwners(lib.ownerId)
            Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, None, owner)(config))
          }
          Ok(Json.obj("mutualLibraries" -> JsArray(libsJson)))
      }
    }
  }

}
