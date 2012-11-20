package com.keepit.controllers

import play.api.libs.json._
import play.api.mvc._
import play.api.Play.current
import play.api.http.ContentTypes


object CommonActions {

  val TEN_MB = 1024 * 1024 * 10

  /**
   * An action that requires valid machine and user cookies
   */
  def JsonAction[A](block: Request[JsValue] => PlainResult): Action[JsValue] = {
    Action(BodyParsers.parse.tolerantJson(maxLength = TEN_MB)) { request =>
      block(request).as(ContentTypes.JSON)
    }
  }

}
