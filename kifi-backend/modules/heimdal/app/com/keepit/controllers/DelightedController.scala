package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.{DelightedAnswerRepo, User}
import play.api.libs.json.Json
import play.api.mvc.Action

class DelightedController @Inject() (
  db: Database,
  delightedAnswerRepo: DelightedAnswerRepo) extends HeimdalServiceController {

  def getLastDelightedAnswerDate(userId: Id[User]) = Action { request =>
    val lastDelightedAnswerDate = db.readOnly { implicit s =>
      delightedAnswerRepo.getLastAnswerDateForUser(userId)
    }
    Ok(Json.toJson(lastDelightedAnswerDate))
  }
}
