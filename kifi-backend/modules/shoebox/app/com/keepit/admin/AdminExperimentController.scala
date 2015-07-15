package com.keepit.controllers.admin

import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db.Id
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
import play.api.libs.json._

import com.google.inject.Inject

import play.api.libs.json.{ JsObject }
import com.keepit.common.math.{ Probability, ProbabilityDensity }

case class AdminExperimentInfo(name: String, numUsers: Int, percentage: Float, variations: Seq[(String, Double)])

class AdminExperimentController @Inject() (
    val userActionsHelper: UserActionsHelper,
    experimentRepo: UserExperimentRepo,
    userRepo: UserRepo,
    db: Database,
    generatorRepo: ProbabilisticExperimentGeneratorRepo) extends AdminUserActions {

  def overview = AdminUserPage { implicit request =>
    val (totalUserCount, experimentsWithCount) = db.readOnlyMaster { implicit session =>
      (userRepo.countIncluding(UserStates.PENDING, UserStates.INACTIVE, UserStates.BLOCKED),
        experimentRepo.getDistinctExperimentsWithCounts().toMap)
    }

    val experimentInfos = (ExperimentType._ALL.zip(ExperimentType._ALL.map(_ => 0)).toMap ++ experimentsWithCount).map {
      case (experimentType, userCount) =>
        db.readOnlyMaster { implicit session =>
          val defaultDensity = generatorRepo.getByName(Name[ProbabilisticExperimentGenerator](experimentType.value + "-default"), None).map(_.density.density.map {
            case Probability(cond, prob) => (cond.value, prob)
          })
          AdminExperimentInfo(experimentType.value, userCount, (100 * userCount.toFloat / totalUserCount), defaultDensity getOrElse Seq.empty)
        }
    }.toSeq.sortBy(_.name)

    Ok(views.html.admin.experimentOverview(experimentInfos))
  }

  def saveGenerator = AdminUserAction { request =>
    val data = request.body.asJson.get.as[JsObject]
    val condition = (data \ "condition").as[String]
    val density = (data \ "density").as[JsObject].fields.map {
      case (name, percentage) =>
        Probability(ExperimentType(name), percentage.as[Double] / 100)
    }
    db.readWrite { implicit session =>
      generatorRepo.internByName(Name[ProbabilisticExperimentGenerator](condition + "-default"), ProbabilityDensity[ExperimentType](density), None, Some(ExperimentType(condition)))
    }
    Ok(data)
  }

  def removeConditions(exId: Id[ProbabilisticExperimentGenerator]) = AdminUserAction { request =>
    db.readWrite { implicit s =>
      val model = generatorRepo.get(exId).copy(condition = None)
      generatorRepo.save(model)
      Ok(Json.toJson(model))
    }
  }

}
