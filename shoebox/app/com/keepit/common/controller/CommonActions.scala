package com.keepit.common.controller

import play.api.libs.json._
import play.api.mvc._
import play.api.http.ContentTypes


object CommonActions {
  
  /**
   * Apply a function that expects a synchronous PlainResult to any Result, synchronous or asynchronous.
   */
  def mapResult(result: Result)(f: PlainResult => Result): Result = {
    result match {
      case AsyncResult(future) => AsyncResult(future map { mapResult(_)(f) })
      case result: PlainResult => f(result)
    }
  }
  
  /**
   * An action that requires valid machine and user cookies
   */
  def JsonAction(block: Request[JsValue] => Result): Action[JsValue] = {
    Action(BodyParsers.parse.tolerantJson) { request =>
      mapResult(block(request)) { _.as(ContentTypes.JSON) }
    }
  }
  
  def JsOK() = Results.Ok(JsString("ok")).as(ContentTypes.JSON)
  
}