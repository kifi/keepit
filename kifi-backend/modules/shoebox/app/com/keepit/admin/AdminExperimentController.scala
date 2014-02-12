package com.keepit.controllers.admin


import com.keepit.common.controller.{AdminController, ActionAuthenticator, AuthenticatedRequest}
import com.keepit.common.db.slick.Database
import com.keepit.model.{UserExperimentRepo, UserRepo, UserStates, ExperimentType}

import com.google.inject.Inject


case class AdminExperimentInfo(name: String, numUsers: Int, percentage: Float)


class AdminExperimentController @Inject() (actionAuthenticator: ActionAuthenticator, experimentRepo: UserExperimentRepo, userRepo: UserRepo, db: Database) extends AdminController(actionAuthenticator) {


  def overview = AdminHtmlAction.authenticated { request =>
    val (totalUserCount, experimentsWithCount) = db.readOnly { implicit session =>
      (userRepo.countExcluding(UserStates.PENDING, UserStates.INACTIVE, UserStates.BLOCKED),
      experimentRepo.getDistinctExperimentsWithCounts().toMap)
    }

    val experimentInfos = (ExperimentType._ALL.zip(ExperimentType._ALL.map(_ => 0)).toMap ++ experimentsWithCount).map{ case (experimentType, userCount) =>
      AdminExperimentInfo(experimentType.value, userCount, (100*userCount.toFloat/totalUserCount))
    }.toSeq.sortBy(_.name)

    Ok(views.html.admin.experimentOverview(experimentInfos))


  }

}
