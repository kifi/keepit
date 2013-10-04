package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.analytics._
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db.{ExternalId, State, Id}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.common.db.slick.{Database}
import com.keepit.common.akka.SafeFuture
import com.keepit.heimdal.{HeimdalServiceClient, UserEventContextBuilder, UserEvent, UserEventType}

import scala.concurrent.future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.libs.json._
import play.api.mvc.Action

import java.math.BigDecimal

class ExtEventController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  eventPersister: EventPersister,
  db: Database,
  userRepo: UserRepo,
  heimdal: HeimdalServiceClient,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def logEvent = Action { request =>
    SafeFuture{
      val req = request.body.asJson.get.asInstanceOf[JsObject]
      val userId = Id[User]((req \ "userId").as[Long])
      val o = (req \ "event").asInstanceOf[JsObject]

      implicit val experimentFormat = State.format[ExperimentType]
      val eventTime = clock.now.minusMillis((o \ "msAgo").asOpt[Int].getOrElse(0))
      val eventFamily = EventFamilies((o \ "eventFamily").as[String])
      val eventName = (o \ "eventName").as[String]
      val installId = (o \ "installId").asOpt[String].getOrElse("NA")
      val metaData = (o \ "metaData").asOpt[JsObject].getOrElse(Json.obj())
      val prevEvents = (o \ "prevEvents").asOpt[Seq[String]].getOrElse(Seq.empty).map(ExternalId[Event])
      val experiments = (o \ "experiments").as[Seq[State[ExperimentType]]].toSet
      val user = db.readOnly { implicit s => userRepo.get(userId) }
      val event = Events.userEvent(eventFamily, eventName, user, experiments, installId, metaData, prevEvents, eventTime)
      log.debug(s"Created new event: $event")
      eventPersister.persist(event)

      //Mirroring to heimdal (temporary, will be the only destination soon without going through shoebox)
      val contextBuilder = new UserEventContextBuilder()
      experiments.foreach{ experiment =>
        contextBuilder += ("experiment", experiment.toString)
      }
      metaData.fields.foreach{ 
        case (key, jsonValue) => {
          val jsonString = jsonValue match { 
            case JsString(s) => s
            case json => Json.stringify(json)
          }
          val value = try {
            val parsedValue = jsonString.toBoolean
            contextBuilder += (key,parsedValue)
          } catch {
            case _ =>
              try {
                val parsedValue = new BigDecimal(jsonString).doubleValue
                contextBuilder += (key,parsedValue)
              } catch {
                case _ => contextBuilder += (key,jsonString)
              } 
          }
        }
      }
      heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventType(s"old_${eventFamily}_${eventName}")))

    }
    Ok("")
  }

}
