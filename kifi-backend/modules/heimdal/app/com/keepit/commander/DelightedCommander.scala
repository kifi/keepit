package com.keepit.commander

import com.google.inject.{Inject, ImplementedBy}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.model._
import com.keepit.common.collection._
import com.ning.http.client.Realm.AuthScheme
import org.joda.time.DateTime
import play.api.libs.json.{JsString, JsValue, Json}
import play.api.libs.ws.{WS, Response}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future

case class DelightedConfig(url: String, apiKey: String)

@ImplementedBy(classOf[DelightedCommanderImpl])
trait DelightedCommander {
  def getLastDelightedAnswerDate(userId: Id[User]): Option[DateTime]
  def postDelightedAnswer(userId: Id[User], email: EmailAddress, score: Int, comment: Option[String]): Future[JsValue]
}

class DelightedCommanderImpl @Inject() (
  db: Database,
  delightedUserRepo: DelightedUserRepo,
  delightedAnswerRepo: DelightedAnswerRepo,
  delightedConfig: DelightedConfig) extends DelightedCommander {

  private def delightedRequest(route: String, data: Map[String, Seq[String]]): Future[Response] = {
    WS.url(delightedConfig.url + route).withAuth(delightedConfig.apiKey, "", AuthScheme.BASIC).post(data)
  }

  def getLastDelightedAnswerDate(userId: Id[User]): Option[DateTime] = {
    db.readOnly { implicit s => delightedAnswerRepo.getLastAnswerDateForUser(userId) }
  }

  def postDelightedAnswer(userId: Id[User], email: EmailAddress, score: Int, comment: Option[String]): Future[JsValue] = {
    import play.api.http.Status

    getOrCreateDelightedUser(userId, email) flatMap { userOpt =>
      userOpt map { user =>
        val data = Map(
          "person" -> Seq(user.delightedExtUserId),
          "score" -> Seq(score.toString)
        ).withOpt("comment" -> comment.map(Seq(_)))

        delightedRequest("/v1/survey_responses.json", data).map { response =>
          if (response.status == Status.OK || response.status == Status.CREATED) {
            delightedAnswerFromResponse(response.json) map { delightedAnswer =>
              db.readWrite { implicit s => delightedAnswerRepo.save(delightedAnswer) }
              JsString("success")
            } getOrElse Json.obj("error" -> "Error retrieving Delighted answer")
          } else Json.obj("error" -> "Error sending answer to Delighted")
        }
      } getOrElse Future.successful(Json.obj("error" -> "Error retrieving Delighted user"))
    }
  }

  private def delightedAnswerFromResponse(json: JsValue): Option[DelightedAnswer] = {
    for {
      delightedExtAnswerId <- (json \ "id").asOpt[String]
      delightedExtUserId <- (json \ "person").asOpt[String]
      score <- (json \ "score").asOpt[Int]
      date <- (json \ "created_at").asOpt[Int]
      delightedUserId <- db.readOnly { implicit s => delightedUserRepo.getByDelightedExtUserId(delightedExtUserId).flatMap(_.id) }
    } yield {
      DelightedAnswer(
        delightedExtAnswerId = delightedExtAnswerId,
        delightedUserId = delightedUserId,
        date = new DateTime(date * 1000L),
        score = score,
        comment = (json \ "comment").asOpt[String]
      )
    }
  }

  private def getOrCreateDelightedUser(userId: Id[User], email: EmailAddress): Future[Option[DelightedUser]] = {
    import play.api.http.Status

    db.readOnly {
      implicit s => delightedUserRepo.getByUserId(userId)
    } map { user => Future.successful(Some(user)) } getOrElse {
      val data = Map(
        "email" -> Seq(email.address),
        "send" -> Seq("false")
      )
      delightedRequest("/v1/people.json", data).map { response =>
        if (response.status == Status.OK || response.status == Status.CREATED) {
          (response.json \ "id").asOpt[String] map { id =>
            val user = DelightedUser(
              delightedExtUserId = id,
              userId = userId,
              email = (response.json \ "email").asOpt[EmailAddress] getOrElse email
            )
            db.readWrite { implicit s => delightedUserRepo.save(user) }
          }
        } else None
      }
    }
  }
}

class DevDelightedCommander extends DelightedCommander {

  def getLastDelightedAnswerDate(userId: Id[User]): Option[DateTime] = None

  def postDelightedAnswer(userId: Id[User], email: EmailAddress, score: Int, comment: Option[String]): Future[JsValue] =
    Future.successful(JsString("success"))
}
