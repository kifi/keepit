package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.commanders.KeepImageCommander
import play.api.mvc.{ Action, Controller }
import play.api.Play
import Play.current

class ExtKeepImageController @Inject() (keepImageCommander: KeepImageCommander)
    extends Controller {

  def doIt() = Action(parse.tolerantJson) { request =>
    val url = (request.body \ "url").as[String]
    keepImageCommander.test(url)
    Ok("")
  }

}
