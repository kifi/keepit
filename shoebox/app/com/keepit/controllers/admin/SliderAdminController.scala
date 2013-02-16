package com.keepit.controllers.admin

import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db.slick.DBConnection
import com.keepit.inject.inject
import com.keepit.model.SliderRuleRepo
import com.keepit.model.SliderRuleStates._
import com.keepit.model.URLPatternRepo

import play.api.libs.json.{JsObject, Json, JsArray}
import play.api.Play.current
import play.api.mvc.Action
import com.keepit.classify.DomainTagImporter

object SliderAdminController extends FortyTwoController {

  def getRules = AdminHtmlAction { implicit request =>
    val groupName = "default"
    val group = inject[DBConnection].readOnly { implicit session =>
      inject[SliderRuleRepo].getGroup(groupName)
    }
    Ok(views.html.sliderRules(groupName, group.rules.map(r => r.name -> r).toMap))
  }

  def saveRules = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get
    val groupName = body("group").head
    inject[DBConnection].readWrite { implicit session =>
      val repo = inject[SliderRuleRepo]
      repo.getGroup(groupName).rules.foreach { rule =>
        val newRule = rule
          .withState(if (body.contains(rule.name)) ACTIVE else INACTIVE)
          .withParameters(body.get(rule.name + "Params").map { arr => JsArray(arr.map(Json.parse)) })
        if (newRule != rule) repo.save(newRule)
      }
    }
    Redirect(routes.SliderAdminController.getRules)
  }

  def getPatterns = AdminHtmlAction { implicit request =>
    val patterns = inject[DBConnection].readOnly { implicit session =>
      inject[URLPatternRepo].all
    }
    Ok(views.html.sliderPatterns(patterns))
  }

  def savePatterns = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get
    inject[DBConnection].readWrite { implicit session =>
      val repo = inject[URLPatternRepo]
      // TODO: iterate over all parameter keys and act on them if they match pattern_(\d+|new)
    }
    Redirect(routes.SliderAdminController.getPatterns)
  }

  def refetchClassifications = Action { implicit request =>
    inject[DomainTagImporter].refetchClassifications()
    Ok(JsObject(Seq()))
  }
}
