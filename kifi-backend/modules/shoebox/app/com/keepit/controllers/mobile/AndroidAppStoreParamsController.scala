package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ UserValueRepo, UserValueName, User }

class AndroidAppStoreParamsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    airbreak: AirbrakeNotifier,
    userValueRepo: UserValueRepo) extends ShoeboxServiceController with UserActions {

  def processAppStoreParams() = UserAction { request =>
    val query: Map[String, Seq[String]] = request.request.queryString
    query foreach { case (key, params) => if (params.nonEmpty) processQuery(request.userId, key, params.head) }
    Ok
  }

  private def processQuery(userId: Id[User], key: String, param: String): Unit = key match {
    case "kcid" => processKcid(userId, param)
    case other => airbreak.notify(s"unrecognized key $key with param: $param")
  }

  private def processKcid(userId: Id[User], kcid: String): Unit = {
    db.readWrite { implicit session =>
      if (userValueRepo.getValueStringOpt(userId, UserValueName.KIFI_CAMPAIGN_ID).isEmpty) {
        userValueRepo.setValue(userId, UserValueName.KIFI_CAMPAIGN_ID, kcid)
      }
    }
  }

}
