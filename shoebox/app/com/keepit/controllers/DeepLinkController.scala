package com.keepit.controllers

import play.api.data._
import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.http.ContentTypes
import com.keepit.controllers.CommonActions._
import com.keepit.common.db.CX
import com.keepit.common.db._
import com.keepit.common.db.ExternalId
import com.keepit.common.async._
import com.keepit.model._
import com.keepit.inject._
import com.keepit.serializer.{PersonalSearchResultPacketSerializer => RPS}
import java.sql.Connection
import com.keepit.common.logging.Logging
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.Hit
import com.keepit.search.graph._
import com.keepit.search._
import com.keepit.common.social.UserWithSocial
import org.apache.commons.lang3.StringEscapeUtils
import com.keepit.search.ArticleSearchResultStore
import com.keepit.common.controller.FortyTwoController
import play.api.libs.json._
import com.keepit.common.analytics._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.common.analytics.reports._


object DeepLinkController extends FortyTwoController {

  def handle(token: String) = AuthenticatedHtmlAction { request =>
    val deepLink = CX.withConnection { implicit conn => DeepLink.getOpt(DeepLinkToken(token)) }

    deepLink match {
      case Some(deep) =>
        Ok(views.html.deeplink(deep))
      case None =>
        NotFound
    }
  }

  def create() = AuthenticatedHtmlAction { request =>
    CX.withConnection { implicit co =>
      DeepLink(initatorUserId = Option(request.userId), recipientUserId = None, uriId = Option(Id[NormalizedURI](0)), deepLocator = DeepLocator.toMessageThread(Id[Comment](0))).save
    }
    // DeepLink(None,2012-12-19T13:09:37.082-08:00,2012-12-19T13:09:37.103-08:00,None,None,None,DeepLocator(/messages/threads/0),DeepLinkToken(ced91bda-f97b-48c4-9c32-2e3f36177fb0),active)
    Ok
  }

}