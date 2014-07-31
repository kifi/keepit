package com.keepit.commander

import com.google.inject.{ Inject, ImplementedBy }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.heimdal.{ DelightedAnswerSources, DelightedAnswerSource, BasicDelightedAnswer }
import com.keepit.model._
import com.keepit.common.collection._
import com.keepit.shoebox.ShoeboxServiceClient
import com.ning.http.client.Realm.AuthScheme
import org.joda.time.DateTime
import play.api.libs.json.JsValue
import play.api.libs.ws.WS.WSRequestHolder
import play.api.libs.ws.WS
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import play.api.http.Status
import com.keepit.common.KestrelCombinator

case class DelightedConfig(url: String, apiKey: String)

@ImplementedBy(classOf[DelightedCommanderImpl])
trait DelightedCommander {
  def getUserLastInteractedDate(userId: Id[User]): Option[DateTime]
  def postDelightedAnswer(userRegistrationInfo: DelightedUserRegistrationInfo, answer: BasicDelightedAnswer): Future[Either[String, DelightedAnswer]]
  def fetchNewDelightedAnswers()
  def scheduleSurveyForLapsedUsers(skipCount: Int): Future[Int]
  def cancelDelightedSurvey(userRegistrationInfo: DelightedUserRegistrationInfo): Future[Boolean]
}

class DelightedCommanderImpl @Inject() (
    db: Database,
    delightedUserRepo: DelightedUserRepo,
    delightedAnswerRepo: DelightedAnswerRepo,
    systemValueRepo: SystemValueRepo,
    delightedConfig: DelightedConfig,
    airbrake: AirbrakeNotifier,
    clock: Clock,
    shoebox: ShoeboxServiceClient) extends DelightedCommander with Logging {

  private val LAST_DELIGHTED_FETCH_TIME = Name[SystemValue]("last_delighted_fetch_time")

  private def delightedRequest(route: String): WSRequestHolder = {
    WS.url(delightedConfig.url + route).withAuth(delightedConfig.apiKey, "", AuthScheme.BASIC)
  }

  def getUserLastInteractedDate(userId: Id[User]): Option[DateTime] = {
    db.readOnlyReplica { implicit s => delightedUserRepo.getLastInteractedDateForUserId(userId) }
  }

  def postDelightedAnswer(userRegistrationInfo: DelightedUserRegistrationInfo, answer: BasicDelightedAnswer): Future[Either[String, DelightedAnswer]] = {
    if (answer.score.isEmpty && answer.answerId.isEmpty) {
      return Future.successful(Left("Delighted answer must contain at least a score or the id of an existing answer"))
    }
    getOrCreateDelightedUser(userRegistrationInfo) flatMap { userOpt =>
      userOpt map { user =>
        val data = Map(
          "person" -> Seq(user.delightedExtUserId),
          "person_properties[source]" -> Seq(answer.source.value)
        ).withOpt(
            "score" -> answer.score.map(s => Seq(s.toString)),
            "comment" -> answer.comment.map(Seq(_))
          )
        val answerIdOpt = answer.answerId flatMap { answerId =>
          // Could have been created a short time ago
          db.readOnlyMaster { implicit session =>
            delightedAnswerRepo.getByExternalId(answerId)
          } map (_.delightedExtAnswerId)
        }
        val response = answerIdOpt map { answerId =>
          delightedRequest(s"/v1/survey_responses/$answerId.json").put(data)
        } getOrElse delightedRequest("/v1/survey_responses.json").post(data)

        response.map { response =>
          if (response.status == Status.OK || response.status == Status.CREATED) {
            saveAnswerForResponse(response.json) match {
              case Some(answer) => Right(answer)
              case None => Left("Error retrieving Delighted answer")
            }
          } else Left("Error sending answer to Delighted")
        }
      } getOrElse Future.successful(Left("Error retrieving Delighted user"))
    }
  }

  def cancelDelightedSurvey(userRegistrationInfo: DelightedUserRegistrationInfo): Future[Boolean] = {
    getOrCreateDelightedUser(userRegistrationInfo) map { userOpt =>
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
      val newAnswers = jsonValues.flatMap(saveAnswerForResponse(_))
      log.info(s"Fetched ${newAnswers.length} new answers from Delighted")
      // By default, answers are returned in ascending chronological order
      jsonValues.reverse.collectFirst {
        case value if (value \ "created_at").asOpt[Long].isDefined => (value \ "created_at").as[Long]
      } map { mostRecentTimeStamp =>
        log.info(s"New most recent timestamp for Delighted answer: $mostRecentTimeStamp")
        db.readWrite { implicit s => systemValueRepo.setValue(LAST_DELIGHTED_FETCH_TIME, mostRecentTimeStamp.toString) }
      }
    }
  }

  def scheduleSurveyForLapsedUsers(skipCount: Int): Future[Int] = {
    val now = clock.now()
    shoebox.getLapsedUsersForDelighted(100, skipCount, now.minusDays(8), Some(now.minusDays(7))) map { userInfos =>
      userInfos map { info =>
        SafeFuture { /*getOrCreateDelightedUser(info, true)*/ }
      }
      userInfos.size
    }
  }

  private def saveAnswerForResponse(json: JsValue): Option[DelightedAnswer] = {
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
        val answerToSave = delightedAnswerRepo.getByDelightedExtAnswerId(answer.delightedExtAnswerId) match {
          case Some(existing) => answer.copy(id = existing.id, externalId = existing.externalId)
          case None => answer
        }
        delightedAnswerRepo.save(answerToSave)
      } orElse {
        airbrake.notify(s"Could not parse Delighted answer: ${json}")
        None
      }
    }
  }

  private def getOrCreateDelightedUser(userRegistrationInfo: DelightedUserRegistrationInfo, scheduleSurvey: Boolean = false): Future[Option[DelightedUser]] = {
    db.readOnlyReplica {
      implicit s => delightedUserRepo.getByUserId(userRegistrationInfo.userId)
    } map { user => Future.successful(Some(user)) } getOrElse {
      val data = Map(
        "email" -> Seq(userRegistrationInfo.email.map(_.address).getOrElse(s"delighted+${userRegistrationInfo.externalId}@kifi.com")),
        "name" -> Seq(userRegistrationInfo.name),
        "properties[customer_id]" -> Seq(userRegistrationInfo.externalId.id)
      ).withOpt(
          "send" -> (if (scheduleSurvey) None else Some(Seq("false")))
        )
      delightedRequest("/v1/people.json").post(data).map { response =>
        val delightedUserOpt = if (response.status == Status.OK || response.status == Status.CREATED) {
          (response.json \ "id").asOpt[String] map { id =>
            val user = DelightedUser(
              delightedExtUserId = id,
              userId = userRegistrationInfo.userId,
              email = userRegistrationInfo.email,
              userLastInteracted = None
            )
            db.readWrite { implicit s => delightedUserRepo.save(user) }
          }
        } else None
        delightedUserOpt tap {
          case Some(delightedUser) => log.info(s"Created delighted user with delighted id ${delightedUser.delightedExtUserId}")
          case None => log.info(s"Could no register delighted user with info $userRegistrationInfo")
        }
      }
    }
  }
}

class DevDelightedCommander extends DelightedCommander with Logging {

  def getUserLastInteractedDate(userId: Id[User]): Option[DateTime] = None

  def postDelightedAnswer(userRegistrationInfo: DelightedUserRegistrationInfo, answer: BasicDelightedAnswer): Future[Either[String, DelightedAnswer]] =
    Future.successful(Left("Cannot create Delighted answers in dev mode"))

  def fetchNewDelightedAnswers() = log.info("Fake fetching new Delighted answers")

  def scheduleSurveyForLapsedUsers(skipCount: Int): Future[Int] = {
    log.info("Fake scheduling survey for lapsed users")
    Future.successful(0)
  }

  def cancelDelightedSurvey(userRegistrationInfo: DelightedUserRegistrationInfo): Future[Boolean] = Future.successful(true)
}
