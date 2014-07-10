package com.keepit.commander

import com.google.inject.{ Inject, ImplementedBy }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.heimdal.{ DelightedAnswerSources, DelightedAnswerSource, BasicDelightedAnswer }
import com.keepit.model._
import com.keepit.common.collection._
import com.ning.http.client.Realm.AuthScheme
import org.joda.time.DateTime
import play.api.libs.json.{ JsString, JsValue, Json }
import play.api.libs.ws.WS.WSRequestHolder
import play.api.libs.ws.WS
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import play.api.http.Status

case class DelightedConfig(url: String, apiKey: String)

@ImplementedBy(classOf[DelightedCommanderImpl])
trait DelightedCommander {
  def getUserLastInteractedDate(userId: Id[User]): Option[DateTime]
  def postDelightedAnswer(userId: Id[User], externalId: ExternalId[User], email: Option[EmailAddress], name: String, answer: BasicDelightedAnswer): Future[JsValue]
  def fetchNewDelightedAnswers()
  def cancelDelightedSurvey(userId: Id[User], externalId: ExternalId[User], email: Option[EmailAddress], name: String): Future[Boolean]
}

class DelightedCommanderImpl @Inject() (
    db: Database,
    delightedUserRepo: DelightedUserRepo,
    delightedAnswerRepo: DelightedAnswerRepo,
    systemValueRepo: SystemValueRepo,
    delightedConfig: DelightedConfig,
    airbrake: AirbrakeNotifier,
    clock: Clock) extends DelightedCommander with Logging {

  private val LAST_DELIGHTED_FETCH_TIME = Name[SystemValue]("last_delighted_fetch_time")

  private def delightedRequest(route: String): WSRequestHolder = {
    WS.url(delightedConfig.url + route).withAuth(delightedConfig.apiKey, "", AuthScheme.BASIC)
  }

  def getUserLastInteractedDate(userId: Id[User]): Option[DateTime] = {
    db.readOnlyReplica { implicit s => delightedUserRepo.getLastInteractedDate(userId) }
  }

  def postDelightedAnswer(userId: Id[User], externalId: ExternalId[User], email: Option[EmailAddress], name: String, answer: BasicDelightedAnswer): Future[JsValue] = {
    getOrCreateDelightedUser(userId, externalId, email, name) flatMap { userOpt =>
      userOpt map { user =>
        val data = Map(
          "person" -> Seq(user.delightedExtUserId),
          "score" -> Seq(answer.score.toString),
          "person_properties[source]" -> Seq(answer.source.value)
        ).withOpt("comment" -> answer.comment.map(Seq(_)))

        delightedRequest("/v1/survey_responses.json").post(data).map { response =>
          if (response.status == Status.OK || response.status == Status.CREATED) {
            saveNewAnswerForResponse(response.json) match {
              case Some(answer) => JsString("success")
              case None => Json.obj("error" -> "Error retrieving Delighted answer")
            }
          } else Json.obj("error" -> "Error sending answer to Delighted")
        }
      } getOrElse Future.successful(Json.obj("error" -> "Error retrieving Delighted user"))
    }
  }

  def cancelDelightedSurvey(userId: Id[User], externalId: ExternalId[User], email: Option[EmailAddress], name: String): Future[Boolean] = {
    getOrCreateDelightedUser(userId, externalId, email, name) map { userOpt =>
      userOpt flatMap (_.id) map { delightedUserId =>
        db.readWrite { implicit session =>
          delightedUserRepo.setLastInteractedDate(delightedUserId, clock.now())
        }
      } nonEmpty
    }
  }

  def fetchNewDelightedAnswers() = {
    val previousDate = db.readOnlyReplica { implicit s => systemValueRepo.getValue(LAST_DELIGHTED_FETCH_TIME) }
    val data = Map("per_page" -> "100").withOpt("since" -> previousDate)
    delightedRequest("/v1/survey_responses.json").withQueryString(data.toList: _*).get() map { response =>
      val jsonValues = response.json.asOpt[Seq[JsValue]].getOrElse {
        airbrake.notify(s"Could not parse response from Delighted: ${response.body}")
        Seq()
      }
      val newAnswers = jsonValues.flatMap(saveNewAnswerForResponse(_))
      log.info(s"Fetched ${newAnswers.length} new answers from Delighted")
      jsonValues.toStream.reverse.flatMap(value => (value \ "created_at").asOpt[String]).headOption map { mostRecentTimeStamp =>
        log.info(s"New most recent timestamp for Delighted answer: $mostRecentTimeStamp")
        db.readWrite { implicit s => systemValueRepo.setValue(LAST_DELIGHTED_FETCH_TIME, mostRecentTimeStamp) }
      }
    }
  }

  private def saveNewAnswerForResponse(json: JsValue): Option[DelightedAnswer] = {
    db.readWrite { implicit s =>
      val answerOpt = for {
        delightedExtAnswerId <- (json \ "id").asOpt[String]
        delightedExtUserId <- (json \ "person").asOpt[String]
        score <- (json \ "score").asOpt[Int]
        date <- (json \ "created_at").asOpt[Int]
        delightedUserId <- delightedUserRepo.getByDelightedExtUserId(delightedExtUserId).flatMap(_.id)
      } yield {
        val source = (json \ "person_properties" \ "source").asOpt[DelightedAnswerSource] getOrElse DelightedAnswerSources.UNKNOWN
        DelightedAnswer(
          delightedExtAnswerId = delightedExtAnswerId,
          delightedUserId = delightedUserId,
          date = new DateTime(date * 1000L),
          score = score,
          comment = (json \ "comment").asOpt[String],
          source = source
        )
      }
      answerOpt map { answer =>
        val existing = delightedAnswerRepo.getByDelightedExtAnswerId(answer.delightedExtAnswerId)
        if (existing.isEmpty) {
          delightedAnswerRepo.save(answer)
          Some(answer)
        } else {
          log.info(s"Delighted answer with id ${answer.id} already exists")
          None
        }
      } getOrElse {
        airbrake.notify(s"Could not parse Delighted answer: ${json}")
        None
      }
    }
  }

  private def getOrCreateDelightedUser(userId: Id[User], externalId: ExternalId[User], email: Option[EmailAddress], name: String): Future[Option[DelightedUser]] = {
    db.readOnlyReplica {
      implicit s => delightedUserRepo.getByUserId(userId)
    } map { user => Future.successful(Some(user)) } getOrElse {
      val data = Map(
        "email" -> Seq(email.map(_.address).getOrElse(s"delighted+$externalId@kifi.com")),
        "name" -> Seq(name),
        "send" -> Seq("false"),
        "properties[customer_id]" -> Seq(externalId.id)
      )
      delightedRequest("/v1/people.json").post(data).map { response =>
        if (response.status == Status.OK || response.status == Status.CREATED) {
          (response.json \ "id").asOpt[String] map { id =>
            val user = DelightedUser(
              delightedExtUserId = id,
              userId = userId,
              email = email,
              lastAnswerDate = None
            )
            db.readWrite { implicit s => delightedUserRepo.save(user) }
          }
        } else None
      }
    }
  }
}

class DevDelightedCommander extends DelightedCommander {

  def getUserLastInteractedDate(userId: Id[User]): Option[DateTime] = None

  def postDelightedAnswer(userId: Id[User], externalId: ExternalId[User], email: Option[EmailAddress], name: String, answer: BasicDelightedAnswer): Future[JsValue] =
    Future.successful(JsString("success"))

  def fetchNewDelightedAnswers() = ()

  def cancelDelightedSurvey(userId: Id[User], externalId: ExternalId[User], email: Option[EmailAddress], name: String): Future[Boolean] = Future.successful(true)
}
