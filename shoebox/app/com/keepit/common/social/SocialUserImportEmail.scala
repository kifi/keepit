package com.keepit.common.social

import scala.collection.mutable.MutableList
import com.keepit.search.ArticleStore
import com.keepit.common.logging.Logging
import com.keepit.search.Article
import com.keepit.model.SocialUserInfo
import com.keepit.model.NormalizedURI
import play.api.Plugin
import play.api.templates.Html
import akka.util.Timeout
import akka.actor._
import akka.actor.Actor._
import akka.actor.ActorRef
import akka.util.duration._
import akka.pattern.ask
import akka.dispatch.Await
import play.api.libs.concurrent._
import org.joda.time.DateTime
import akka.dispatch.Future
import scala.collection.mutable.{Map => MutableMap}
import com.keepit.model.{User, EmailAddress, UserCxRepo}
import com.keepit.inject._
import com.keepit.common.db.CX
import com.keepit.common.db.CX._
import com.keepit.common.db._
import play.api.Play.current
import play.api.libs.json.JsArray
import securesocial.core.{SocialUser, UserId, AuthenticationMethod, OAuth2Info}
import play.api.libs.json.JsValue
import java.sql.Connection
import com.keepit.common.logging.Logging

class SocialUserImportEmail() extends Logging {

  def importEmail(userId: Id[User], parentJsons: Seq[JsValue]): Option[EmailAddress] = importEmailFromJson(userId, parentJsons.head)

  private def importEmailFromJson(userId: Id[User], json: JsValue): Option[EmailAddress] = {
    (json \ "email").asOpt[String].map {emailString =>
      CX.withConnection { implicit conn =>
        EmailAddress.getByAddressOpt(emailString) match {
          case Some(email) =>
            if (email.userId != userId) throw new IllegalStateException("email %s is not associated with user %s".format(email, UserCxRepo.get(userId)))
            log.info("email %s for user %s already exist".format(email, UserCxRepo.get(userId)))
            email
          case None =>
            log.info("creating new email %s for user %s already exist".format(emailString, UserCxRepo.get(userId)))
            EmailAddress(userId = userId, address = emailString).save
        }
      }
    }
  }

}
