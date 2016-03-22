package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.heimdal._
import com.keepit.model._

class ClientKeepController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  keepRepo: KeepRepo,
  keepDecorator: KeepDecorator,
  collectionRepo: CollectionRepo,
  collectionCommander: CollectionCommander,
  keepsCommander: KeepCommander,
  keepExportCommander: KeepExportCommander,
  permissionCommander: PermissionCommander,
  clock: Clock,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  airbrake: AirbrakeNotifier,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def getExtendedKeepInfo(pubId: PublicId[Keep]) = MaybeUserAction { implicit request =>
    NoContent
  }
}
