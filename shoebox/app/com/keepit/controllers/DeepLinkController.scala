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

  def handle(token: String) = Action(parse.anyContent) { request =>
    Ok("<html data-kifi-deep-link='{\"locator\":\"/messages/201fe7f1-aed6-4711-af7e-926118763b96\",\"uri\":\"http://www.mozilla.org/en-US/firefox/fx/\"}'><script>setTimeout(function(){window.location=\"http://www.mozilla.org/en-US/firefox/fx/\"},1000);</script><span class=kifi-deep-link-no-extension>Hi!</span></html>").as(ContentTypes.HTML)
  }

}
