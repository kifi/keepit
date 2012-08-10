package com.keepit.controllers

import play.api.libs.json._
import play.api.mvc._
import play.api.Play.current
import play.api.http.ContentTypes


object CommonActions {
  /**
   * An action that requires valid machine and user cookies
   */
  def JsonAction[A](block: Request[JsValue] => PlainResult): Action[JsValue] = {
    Action(BodyParsers.parse.tolerantJson) { request =>
      block(request).as(ContentTypes.JSON)
    }
  }
  
}