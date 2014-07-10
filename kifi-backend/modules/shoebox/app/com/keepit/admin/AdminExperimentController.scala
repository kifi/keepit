package com.keepit.controllers.admin

import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.keepit.common.db.slick.Database
import com.keepit.model.{
  UserExperimentRepo,
  UserRepo,
  UserStates,
  ExperimentType,
  ProbabilisticExperimentGenerator,
  ProbabilisticExperimentGeneratorRepo,
  Name
}

import com.google.inject.Inject

import play.api.libs.json.{ JsObject }
import com.keepit.common.math.ProbabilityDensity

case class AdminExperimentInfo(name: String, numUsers: Int, percentage: Float, variations: Seq[(String, Double)])

class AdminExperimentController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    experimentRepo: UserExperimentRepo,
    userRepo: UserRepo,
    db: Database,
    generatorRepo: ProbabilisticExperimentGeneratorRepo) extends AdminController(actionAuthenticator) {

  def overview = AdminHtmlAction.authenticated { request =>
    val (totalUserCount, experimentsWithCount) = db.readOnlyMaster { implicit session =>
      (userRepo.countIncluding(UserStates.PENDING, UserStates.INACTIVE, UserStates.BLOCKED),
        experimentRepo.getDistinctExperimentsWithCounts().toMap)
    }

    val experimentInfos = (ExperimentType._ALL.zip(ExperimentType._ALL.map(_ => 0)).toMap ++ experimentsWithCount).map {
      case (experimentType, userCount) =>
        db.readOnlyMaster { implicit session =>
          val defaultDensity = generatorRepo.getByName(Name[ProbabilisticExperimentGenerator](experimentType.value + "-default"), None).map(_.density.density.map {
            case (cond, prob) => (cond.value, prob)
          })
          AdminExperimentInfo(experimentType.value, userCount, (100 * userCount.toFloat / totalUserCount), defaultDensity getOrElse Seq.empty)
        }
    }.toSeq.sortBy(_.name)

    Ok(views.html.admin.experimentOverview(experimentInfos))
  }

  def saveGenerator = AdminJsonAction.authenticated { request =>
    val data = request.body.asJson.get.as[JsObject]
    val condition = (data \ "condition").as[String]
    val density = (data \ "density").as[JsObject].fields.map {
      case (name, percentage) =>
        (ExperimentType(name), percentage.as[Double] / 100)
    }
    db.readWrite { implicit session =>
      generatorRepo.internByName(Name[ProbabilisticExperimentGenerator](condition + "-default"), ProbabilityDensity[ExperimentType](density), None, Some(ExperimentType(condition)))
    }
    Ok(data)
  }

}
