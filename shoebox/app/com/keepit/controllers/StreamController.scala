package com.keepit.controllers

import play.api.mvc.Action
import play.api.mvc.WebSocket
import play.api.libs.json._
import play.api.mvc.Controller
import com.keepit.inject._
import com.keepit.common.controller.FortyTwoController
import com.keepit.realtime.StreamManager

class StreamController extends FortyTwoController {

//  def ws() = WebSocket.async[JsValue] { implicit request  =>
//    inject[StreamManager
//  }

  def giveMeA200 = Action { request =>
    Ok("You got it!")
  }

}
