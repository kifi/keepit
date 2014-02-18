package com.keepit.eliza.controllers.internal

import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza._
import com.keepit.common.controller.ElizaServiceController
import com.keepit.common.logging.Logging
import com.keepit.model.{ExperimentType, NormalizedURI, User}
import com.keepit.common.db.{Id}

import scala.concurrent.{Future, future}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.mvc.Action
import play.api.libs.json.{JsObject, JsArray}

import com.google.inject.Inject
import com.keepit.eliza.commanders.ElizaStatsCommander
import com.keepit.eliza.model.{MessageThreadRepo, UserThreadRepo, UserThreadStats}
import com.keepit.common.db.slick.Database
import com.keepit.commanders.RemoteUserExperimentCommander

class ElizaController @Inject() (
  userThreadRepo: UserThreadRepo,
  messageThreadRepo: MessageThreadRepo,
  db: Database,
  experimentCommander: RemoteUserExperimentCommander,
  notificationRouter: WebSocketRouter,
  elizaStatsCommander: ElizaStatsCommander)
    extends ElizaServiceController with Logging {

  def getUserThreadStats(userId: Id[User]) = Action { request =>
    Ok(UserThreadStats.format.writes(elizaStatsCommander.getUserThreadStats(userId)))
  }

  def sendToUserNoBroadcast() = Action.async { request =>
    future{
      val req = request.body.asJson.get.asInstanceOf[JsObject]
      val userId = Id[User]((req \ "userId").as[Long])
      val data = (req \ "data").asInstanceOf[JsArray]
      notificationRouter.sendToUserNoBroadcast(userId, data)
      Ok("")
    }
  }

  def sendToUser() = Action.async { request =>
    future{
      val req = request.body.asJson.get.asInstanceOf[JsObject]
      val userId = Id[User]((req \ "userId").as[Long])
      val data = (req \ "data").asInstanceOf[JsArray]
      notificationRouter.sendToUser(userId, data)
      Ok("")
    }
  }

  def sendToAllUsers() = Action.async { request =>
    future{
      val req = request.body.asJson.get.asInstanceOf[JsArray]
      notificationRouter.sendToAllUsers(req)
      Ok("")
    }
  }

  def alertAboutRekeeps() = Action.async(parse.tolerantJson) { request =>
    future{
      implicit val idFormat = Id.format[User]
      val req = request.body.asInstanceOf[JsObject]
      val keeperUserId = (req \ "userId").as[Id[User]]
      val uriIds = (req \ "uriIds").as[Seq[Id[NormalizedURI]]]

      experimentCommander.getExperimentsByUser(keeperUserId).map { userExperiments =>
        if (!userExperiments.contains(ExperimentType.ADMIN)) {
              // no-op for now.
            } else {
          val threadIdsWithStarter = db.readOnly { implicit session =>
          // These are the user's threads (they are a member of) from pages they just kept
            val threadIds = uriIds.map { uriId =>
              userThreadRepo.getThreadIds(keeperUserId, Some(uriId))
            }.flatten
            threadIds.map { threadId =>
              (threadId, userThreadRepo.getThreadStarter(threadId))
            }
          }

          val p = Future.sequence(threadIdsWithStarter.map { case (threadId, starterUserId) =>
            if (starterUserId == keeperUserId) {
              Future.successful(None)
            } else {
              experimentCommander.getExperimentsByUser(starterUserId).map { experiments =>
                if (experiments.contains(ExperimentType.ADMIN)) {
                  Some((threadId, starterUserId))
                } else {
                  None
                }
              }
            }
          }).map(_.flatten)
        }
      }



      Ok("")
    }
  }

  def connectedClientCount() = Action { request =>
    Ok(notificationRouter.connectedSockets.toString)
  }

}
