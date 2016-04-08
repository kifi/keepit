package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders.{ KeepQuery, KeepQueryCommander, PermissionCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.core.tryExtensionOps
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.common.util.RightBias
import com.keepit.common.util.RightBias.FromOption
import com.keepit.model._
import com.keepit.shoebox.data.assemblers.KeepInfoAssembler
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

class LibraryKeepsInfoController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  keepRepo: KeepRepo,
  keepQueryCommander: KeepQueryCommander,
  keepInfoAssembler: KeepInfoAssembler,
  permissionCommander: PermissionCommander,
  clock: Clock,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def getKeepsInLibrary(libPubId: PublicId[Library], fromPubKeepIdOpt: Option[String]) = MaybeUserAction.async { implicit request =>
    val resultIfEverythingWentWell = for {
      libId <- Library.decodePublicId(libPubId).toOption.withLeft(LibraryFail.INVALID_LIBRARY_ID: LibraryFail)
      fromIdOpt <- fromPubKeepIdOpt.filter(_.nonEmpty).fold[RightBias[LibraryFail, Option[Id[Keep]]]](RightBias.right(None)) { pubKeepId =>
        Keep.decodePublicIdStr(pubKeepId).airbrakingOption.withLeft(LibraryFail.INVALID_KEEP_ID).map(Some(_))
      }
      permissions = db.readOnlyMaster { implicit s =>
        permissionCommander.getLibraryPermissions(libId, request.userIdOpt)
      }
      _ <- RightBias.unit.filter(_ => permissions.contains(LibraryPermission.VIEW_LIBRARY), LibraryFail.INSUFFICIENT_PERMISSIONS)
    } yield {
      val keepIds = db.readOnlyMaster { implicit s =>
        keepQueryCommander.getKeeps(request.userIdOpt, KeepQuery(
          target = KeepQuery.ForLibrary(libId),
          arrangement = None,
          fromId = fromIdOpt,
          offset = Offset(0),
          limit = Limit(10)
        ))
      }
      keepInfoAssembler.assembleKeepViews(request.userIdOpt, keepSet = keepIds.toSet).map { viewMap =>
        Ok(Json.obj("keeps" -> keepIds.flatMap(kId => viewMap.get(kId).flatMap(_.getRight))))
      }
    }
    resultIfEverythingWentWell.getOrElse { fail => Future.successful(fail.asErrorResponse) }
  }
}
