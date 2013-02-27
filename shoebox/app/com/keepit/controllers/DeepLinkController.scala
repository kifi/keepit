package com.keepit.controllers

import play.api.data._
import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.http.ContentTypes

import com.keepit.common.db._
import com.keepit.common.db.slick._
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
import com.keepit.search.ArticleSearchResultStore
import com.keepit.common.controller.FortyTwoController
import play.api.libs.json._
import com.keepit.common.analytics._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.common.analytics.reports._


object DeepLinkController extends FortyTwoController {

  def handle(token: String) = AuthenticatedHtmlAction { request =>
    val db = inject[Database]
    val deepLink = db.readOnly { implicit session => inject[DeepLinkRepo].getByToken(DeepLinkToken(token)) }

    deepLink match {
      case Some(deep) =>
        deep.recipientUserId match {
          case Some(recip) if request.userId != recip =>
            Forbidden
          case _ =>
            val uri = deep.uriId.map(uri => db.readOnly { implicit session => inject[NormalizedURIRepo].get(uri).url }) getOrElse ("")
            val locator = deep.deepLocator.value
            val isSecure = deep.recipientUserId.isDefined
            Ok(views.html.deeplink(uri, locator, isSecure))
        }
      case None =>
        NotFound
    }
  }

}
