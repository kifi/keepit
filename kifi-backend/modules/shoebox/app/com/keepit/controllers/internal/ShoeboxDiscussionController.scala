package com.keepit.controllers.internal

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.{ PermissionCommander, KeepCommander, RawBookmarkRepresentation, SocialShare }
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.crypto.PublicIdConfiguration
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
    val rawKeep = request.body.as[ExternalRawKeep]

    implicit val context = heimdalContextBuilderFactory.withRequestInfo(request).build

    val rawBookmark = RawBookmarkRepresentation.fromExternalRawKeep(rawKeep)
    val users = db.readOnlyReplica { implicit s =>
      userRepo.getAllUsersByExternalId(rawKeep.users).map { case (extId, u) => extId -> u.id.get }
    }
    val owner = users(rawKeep.owner)
    val libraries = rawKeep.libraries.map(pubId => Library.decodePublicId(pubId).get)
    assert(libraries.size == 1)
    val libPermissions = db.readOnlyReplica { implicit s =>
      permissionCommander.getLibrariesPermissions(libraries, Some(owner))
    }
    val userCanWriteToAllRequiredLibraries = libraries.forall { libId =>
      libPermissions.get(libId).exists(_.contains(LibraryPermission.ADD_KEEPS))
    }
    if (userCanWriteToAllRequiredLibraries) {
      val (keep, _) = keepCommander.keepOne(rawBookmark, owner, libraries.head, KeepSource.keeper, SocialShare.empty)
      val csKeep = keepCommander.getCrossServiceKeeps(Set(keep.id.get)).get(keep.id.get).get
      Ok(Json.toJson(csKeep))
    } else Forbidden(Json.obj("error" -> "cannot_write_to_library"))
  }
}
