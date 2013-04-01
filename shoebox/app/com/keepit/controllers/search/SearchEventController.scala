
package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.common.db.Id
import com.keepit.model.{User, NormalizedURI}
import com.keepit.search.ResultClickTracker
import play.api.libs.json.{JsObject, JsString, Json}
import play.api.mvc.Action

case class ResultClicked(userId: Id[User], query: String, uriId: Id[NormalizedURI], isUserKeep: Boolean)

object ResultClickedJson {
  private implicit val uriIdFormat = Id.format[NormalizedURI]
  private implicit val userIdFormat = Id.format[User]
  implicit val resultClickedFormat = Json.format[ResultClicked]
}

class SearchEventController @Inject()(resultClickTracker: ResultClickTracker) extends SearchServiceController {

  import ResultClickedJson._

  def logResultClicked = Action(parse.json) { request =>
    val rc = Json.fromJson[ResultClicked](request.body).get
    resultClickTracker.add(rc.userId, rc.query, rc.uriId, rc.isUserKeep)
    Ok(JsObject(Seq("stored" -> JsString("ok"))))
  }
}
