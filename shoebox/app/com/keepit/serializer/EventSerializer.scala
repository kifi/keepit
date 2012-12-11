package com.keepit.serializer

import com.keepit.common.db.{Id, ExternalId, State}
import com.keepit.common.time._
import securesocial.core._
import securesocial.core.AuthenticationMethod._
import play.api.libs.json._
import com.keepit.common.analytics._
import com.mongodb.casbah.Imports._
import com.mongodb.casbah.commons.conversions.scala._
import com.keepit.model._
import com.mongodb.BasicDBObject

class EventSerializer extends Format[Event] {
  // Play! 2.0 Json serializer
  def writes(event: Event): JsValue =
    JsObject(Seq(
     "id" -> JsString(event.externalId.id),
     "createdAt" -> JsString((event.createdAt).toStandardTimeString),
     "serverVersion" -> JsString(event.serverVersion),
     "eventFamily" -> JsString(event.metaData.eventFamily.name),
     "metaData" -> EventSerializer.eventMetadataSerializer.writes(event.metaData)
    ))

  def reads(json: JsValue): Event =
    Event(
      externalId = ExternalId[Event]((json \ "id").as[String]),
      metaData = EventSerializer.eventMetadataSerializer.reads(json \ "metaData"),
      createdAt = parseStandardTime((json \ "createdAt").as[String]),
      serverVersion = (json \ "serverVersion").as[String]
    )

  // MongoDb Casbah serializer
  RegisterJodaTimeConversionHelpers()

  def mongoWrites(event: Event): DBObject = {
    // To make future compatibility easier, serializing to a Map first
    val dbo: DBObject = Map[String,Any](
      "id" -> event.externalId.id,
      "createdAt" -> (event.createdAt).toStandardTimeString,
      "serverVersion" -> event.serverVersion,
      "eventFamily" -> event.metaData.eventFamily.name,
      "metaData" -> jsonToMap(EventSerializer.eventMetadataSerializer.writes(event.metaData))
    )
    dbo
  }

  def mongoReads(dbo: DBObject) = {
    reads(Json.parse(dbo.toString))
  }

  private def jsonToMap(json: JsObject): Map[String,Any] = {
    val fields = json.fields map { o =>
      val value = o._2 match {
        case s: JsNumber =>
          s.value
        case s: JsString =>
          s.value
        case s: JsBoolean =>
          s.value
        case s: JsObject =>
          jsonToMap(s)
        case s: JsArray =>
          s.value map ( s =>
            s match {
              case c: JsString =>
                c.value
              case c: JsNumber =>
                c.value
              case c: JsBoolean =>
                c.value
              case c =>
                c.toString
            }
          )
        case s =>
          s.toString
      }
      (o._1, value)
    }
    fields.toMap
  }

  private def mapToJson(map: Map[String,Any]): JsObject = {
    val jsMap = map map { k =>
      val value: JsValue = k._2 match {
        case n: Number => JsNumber(n.doubleValue)
        case b: Boolean => JsBoolean(b)
        case s: String => JsString(s)
        case x => JsString(x.toString)
      }
      (k._1, value)
    }
    JsObject(jsMap.toSeq)
  }
}

class EventMetadataSerializer extends Format[EventMetadata] {
  def writes(metadata: EventMetadata): JsObject = {
    metadata match {
      case UserEventMetadata(eventFamily, eventName, userId, installId, userExperiments, jsonMetaData) =>
        JsObject(Seq(
          "eventFamily" -> JsString(eventFamily.name),
          "eventName" -> JsString(eventName),
          "userId" -> JsString(userId.id),
          "installId" -> JsString(installId.id),
          "userExperiments" -> JsArray(userExperiments.map { p => JsString(p.value) }),
          "metaData" -> jsonMetaData
        ))
      case ServerEventMetadata(eventFamily, eventName, jsonMetaData) =>
        JsObject(Seq(
          "eventFamily" -> JsString(eventFamily.name),
          "eventName" -> JsString(eventName),
          "metaData" -> jsonMetaData
        ))
    }
  }

  def reads(json: JsValue): EventMetadata = {
    EventFamilies((json \ "eventFamily").as[String]) match {
      case UserEventFamily(name) =>
        UserEventMetadata(
          eventFamily = EventFamilies((json \ "eventFamily").as[String]),
          eventName = (json \ "eventName").as[String],
          userId = ExternalId[User]((json \ "userId").as[String]),
          installId = ExternalId[KifiInstallation]((json \ "installId").as[String]),
          userExperiments = (json \ "userExperiments").as[List[String]] map {ux => UserExperiment.ExperimentTypes(ux)},
          metaData = (json \ "metaData").as[JsObject]
        )
      case ServerEventFamily(name) =>
        ServerEventMetadata(
          eventFamily = EventFamilies((json \ "eventFamily").as[String]),
          eventName = (json \ "eventName").as[String],
          metaData = (json \ "metaData").as[JsObject]
        )
    }
  }

}

object EventSerializer {
  implicit val eventSerializer = new EventSerializer
  implicit val eventMetadataSerializer = new EventMetadataSerializer
}
