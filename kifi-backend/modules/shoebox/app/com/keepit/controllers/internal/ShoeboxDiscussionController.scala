package com.keepit.controllers.internal

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.{ PermissionCommander, KeepCommander, RawBookmarkRepresentation, SocialShare }
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import play.api.libs.json._
import play.api.mvc.Action

@Singleton
class ShoeboxDiscussionController @Inject() (
  db: Database,
  clock: Clock,
  userRepo: UserRepo,
  keepCommander: KeepCommander,
  heimdalContextBuilderFactory: HeimdalContextBuilderFactory, // seriously? builder factory?
  permissionCommander: PermissionCommander,
  implicit val config: PublicIdConfiguration,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends ShoeboxServiceController with Logging {

  def internKeep() = Action(parse.tolerantJson) { request =>
    val createReq = request.body.as[KeepCreateRequest]
    assert(createReq.libraries.size == 1)
    assert(createReq.users.size == 1)

    implicit val context = heimdalContextBuilderFactory.withRequestInfo(request).build

    val libPermissions = db.readOnlyReplica { implicit s =>
      permissionCommander.getLibrariesPermissions(createReq.libraries, Some(createReq.owner))
    }
    val userCanWriteToAllRequiredLibraries = createReq.libraries.forall { libId =>
      libPermissions.get(libId).exists(_.contains(LibraryPermission.ADD_KEEPS))
    }

    if (userCanWriteToAllRequiredLibraries) {
      val rawBookmark = RawBookmarkRepresentation.fromCreateRequest(createReq)
      val (keep, _) = keepCommander.keepOne(rawBookmark, createReq.owner, createReq.libraries.head, KeepSource.keeper, SocialShare.empty)
      val csKeep = keepCommander.getCrossServiceKeeps(Set(keep.id.get)).get(keep.id.get).get
      Ok(Json.toJson(csKeep))
    } else Forbidden(Json.obj("error" -> "cannot_write_to_library"))
  }

  def canCommentOnKeep(userId: Id[User], keepId: Id[Keep]) = Action { request =>
    Ok(Json.obj("canComment" -> Json.toJson(keepCommander.canCommentOnKeep(userId, keepId))))
  }
}
