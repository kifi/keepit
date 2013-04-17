package com.keepit.controllers.admin

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model.NormalizedURIStates._
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import play.api.libs.json.{JsNumber, JsObject}
import scala.concurrent.ExecutionContext.Implicits.global

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.{Inject, Singleton}

@Singleton
class AmazonInstanceController @Inject()(
    amazonInstanceInfo: AmazonInstanceInfo
  ) extends AdminController(actionAuthenticator) {

  def instanceInfo = AdminHtmlAction { implicit request =>
    Ok(html.admin.amazonInstanceInfo(amazonInstanceInfo))
  }

}
