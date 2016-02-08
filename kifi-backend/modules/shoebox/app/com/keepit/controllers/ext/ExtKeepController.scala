package com.keepit.controllers.ext

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext

@Singleton
class ExtKeepController @Inject() (
  discussionCommander: DiscussionCommander,
  val userActionsHelper: UserActionsHelper,
  val db: Database,
  libRepo: LibraryRepo,
  keepInterner: KeepInterner,
  permissionCommander: PermissionCommander,
  implicit val publicIdConfig: PublicIdConfiguration,
  contextBuilder: HeimdalContextBuilderFactory,
  implicit val ec: ExecutionContext)
    extends UserActions with ShoeboxServiceController {

  def createKeep() = UserAction(parse.tolerantJson) { implicit request =>
    implicit val context = contextBuilder.withRequestInfoAndSource(request, KeepSource.keeper).build
    val rawBookmark = request.body.as[RawBookmarkRepresentation]
    val libIds = (request.body \ "libraries").as[Set[PublicId[Library]]]
    if (libIds.size > 1) BadRequest(Json.obj("error" -> "please_no_multilib_keeps_yet"))
    val libOpt = for {
      pubId <- libIds.headOption
      libId <- Library.decodePublicId(pubId).toOption
      lib <- db.readOnlyReplica { implicit s =>
        Some(libRepo.get(libId)).filter(_ => permissionCommander.getLibraryPermissions(libId, request.userIdOpt).contains(LibraryPermission.ADD_KEEPS))
      }
    } yield lib

    val response = keepInterner.internRawBookmarksWithStatus(Seq(rawBookmark), Some(request.userId), libOpt, KeepSource.keeper)

    response.successes.headOption.map { k =>
      Ok(Json.obj("id" -> Keep.publicId(k.id.get)))
    }.get // just throw exceptions if we fail
  }
}
