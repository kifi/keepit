package com.keepit.controllers.routing

import com.google.inject.Inject
import com.keepit.common.controller.MaybeUserRequest
import com.keepit.controllers.website.UserController
import play.api.libs.json.{ Json, JsObject }
import play.api.mvc.{ Result, Request, Action, AnyContent }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

class SitePreloader @Inject() (
    userController: UserController,
    implicit val ec: ExecutionContext) {

  def preload(requests: Seq[PreloadRequest])(implicit request: MaybeUserRequest[_]) = {
    requests.map { r =>
      Future { Try(requestToData(r)(request)).toOption.flatten.map(buildPayload(r.path, _)).getOrElse("") }
    }
  }
  private def buildPayload(path: String, payload: JsObject) = {
    s"<script>preload('$path', ${Json.stringify(payload)});</script>"
  }

  private def requestToData[T](preload: PreloadRequest)(request: MaybeUserRequest[_]): Option[JsObject] = {
    import com.keepit.controllers.routing.PreloadRequest._

    preload match {
      case Me => request.userIdOpt.map(userController.getUserInfo)
    }
  }
}

sealed trait PreloadRequest {
  def path: String
}
object PreloadRequest {
  case object Me extends PreloadRequest {
    val path = "/site/user/me"
  }
}
