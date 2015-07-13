package com.keepit.controllers.admin

import java.util.regex.{ PatternSyntaxException, Pattern }

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import play.api.libs.json.Json

class AdminGoodiesController @Inject() (
    override val userActionsHelper: UserActionsHelper) extends AdminUserActions {

  def testRegex = AdminUserPage { implicit request =>
    Ok(views.html.admin.roverTestRegex())
  }

  def testRegexFilled(regex: String, test: Option[String] = None) = AdminUserPage { implicit request =>
    Ok(views.html.admin.roverTestRegex(Some(regex), test.map(t => List(t))))
  }

  def performRegexTest = AdminUserPage(parse.json) { implicit request =>
    val body = request.body
    try {
      val regex = (body \ "regex").as[String]
      val pattern = Pattern.compile(regex)
      val tests = (body \ "tests").as[List[String]]
      val results = tests.map { pattern.matcher(_).matches }
      Ok(Json.toJson(results))
    } catch {
      case e: PatternSyntaxException =>
        BadRequest(Json.toJson(e.toString))
    }
  }

}
