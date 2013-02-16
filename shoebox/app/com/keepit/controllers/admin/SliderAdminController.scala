package com.keepit.controllers.admin

import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBConnection
import com.keepit.inject.inject
import com.keepit.model.{SliderRuleRepo, SliderRuleStates}
import com.keepit.model.{URLPattern, URLPatternRepo, URLPatternStates}

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
          .withState(if (body.contains(rule.name)) SliderRuleStates.ACTIVE else SliderRuleStates.INACTIVE)
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
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    inject[DBConnection].readWrite { implicit session =>
      val repo = inject[URLPatternRepo]
      for (key <- body.keys.filter(_.startsWith("pattern_")).map(_.substring(8))) {
        val id = Id[URLPattern](key.toLong)
        val oldPat = repo.get(id)
        val newPat = oldPat
          .withPattern(body("pattern_" + key))
          .withExample(Some(body("example_" + key)).filter(!_.isEmpty))
          .withState(if (body.contains("active_" + key)) URLPatternStates.ACTIVE else URLPatternStates.INACTIVE)
        if (newPat != oldPat) {
          repo.save(newPat)
        }
      }
      val newPat = body("new_pattern")
      if (!newPat.isEmpty) {
        repo.save(URLPattern(None, newPat,
          Some(body("new_example")).filter(!_.isEmpty),
          state = if (body.contains("new_active")) URLPatternStates.ACTIVE else URLPatternStates.INACTIVE))
      }
    }
    Redirect(routes.SliderAdminController.getPatterns)
  }

  def refetchClassifications = Action { implicit request =>
    inject[DomainTagImporter].refetchClassifications()
    Ok(JsObject(Seq()))
  }
}
