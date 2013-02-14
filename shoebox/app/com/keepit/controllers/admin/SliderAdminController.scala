package com.keepit.controllers.admin

import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db.slick.DBConnection
import com.keepit.inject.inject
import com.keepit.model.SliderRuleRepo
import com.keepit.model.SliderRuleStates._

import play.api.libs.json.{Json, JsArray}
import play.api.Play.current

object SliderAdminController extends FortyTwoController {

  def index = AdminHtmlAction { implicit request =>
    val groupName = "default"
    val group = inject[DBConnection].readOnly { implicit session =>
      inject[SliderRuleRepo].getGroup(groupName)
    }
    Ok(views.html.sliderAdmin(groupName, group.rules.map(r => r.name -> r).toMap))
  }

  def save = AdminHtmlAction { implicit request =>
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
    Redirect(routes.SliderAdminController.index)
  }

}
