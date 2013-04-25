package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import play.api.libs.json._
import com.keepit.common.analytics._
import com.mongodb.casbah.Imports._
import com.mongodb.casbah.commons.conversions.scala._
import com.keepit.model._
import org.joda.time.format.ISODateTimeFormat
import org.joda.time.DateTime

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

  def reads(json: JsValue): JsResult[Event] =
    JsSuccess(Event(
      externalId = ExternalId[Event]((json \ "id").as[String]),
      metaData = EventSerializer.eventMetadataSerializer.reads(json \ "metaData").get,
      createdAt = (json \ "createdAt") match {
        case s: JsString => s.as[DateTime]
        case s: JsObject =>
          val parser = ISODateTimeFormat.dateTime()
          parser.parseDateTime(s.values.head.as[String]) // necessary for Mongo parsing
        case s: JsValue => s.as[DateTime]
      },
      serverVersion = (json \ "serverVersion").as[String]
    ))

  // MongoDb Casbah serializer
  RegisterJodaTimeConversionHelpers()

  def mongoWrites(event: Event): DBObject = {
    // To make future compatibility easier, serializing to a Map first
    val dbo: DBObject = Map[String,Any](
      "id" -> event.externalId.id,
      "createdAt" -> (event.createdAt),
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
    // Mongo Casbah conveniently gives us a Map[String,Any] -> DBObject implicit converter
    // The converter calls the toString method on fields it doesn't have an explicit conversion for.
    // Unfortunately, that means we need to visit every Jerkson JS field and convert to its Scala type,
    // or else we'd end up with Horrible Nastiness™
    val fields = json.fields map { o =>
      val value = o._2 match {
        case s: JsNumber => s.value.toDouble
        case s: JsString => s.value
        case s: JsBoolean => s.value
        case s: JsObject => jsonToMap(s)
        case s: JsArray => jsArrayToScala(s)
        case s => s.toString
      }
      (o._1, value)
    }
    fields.toMap
  }

  private def jsArrayToScala(arr: JsArray): Seq[Any] = {
    arr.value map ( s =>
      s match {
        case c: JsObject => jsonToMap(c)
        case c: JsArray => jsArrayToScala(c)
        case c: JsString => c.value
        case c: JsNumber => c.value.toDouble
        case c: JsBoolean => c.value
        case c => c.toString
      }
    )
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
      case UserEventMetadata(eventFamily, eventName, userId, installId, userExperiments, jsonMetaData, prevEvents) =>
        JsObject(Seq(
          "eventFamily" -> JsString(eventFamily.name),
          "eventName" -> JsString(eventName),
          "userId" -> JsString(userId.id),
          "installId" -> JsString(installId),
          "userExperiments" -> JsArray(userExperiments.map { p => JsString(p.value) } toSeq),
          "metaData" -> jsonMetaData,
          "prevEvents" -> JsArray(prevEvents map { p => JsString(p.id)})
        ))
      case ServerEventMetadata(eventFamily, eventName, jsonMetaData, prevEvents) =>
        JsObject(Seq(
          "eventFamily" -> JsString(eventFamily.name),
          "eventName" -> JsString(eventName),
          "metaData" -> jsonMetaData,
          "prevEvents" -> JsArray(prevEvents map { p => JsString(p.id)})
        ))
    }
  }

  def reads(json: JsValue): JsResult[EventMetadata] = 
    JsSuccess(EventFamilies((json \ "eventFamily").as[String]) match {
      case UserEventFamily(name) =>
        UserEventMetadata(
          eventFamily = EventFamilies((json \ "eventFamily").as[String]),
          eventName = (json \ "eventName").as[String],
          userId = ExternalId[User]((json \ "userId").as[String]),
          installId = (json \ "installId").asOpt[String].getOrElse(""),
          userExperiments = (json \ "userExperiments").as[Seq[String]] map {ux => ExperimentTypes(ux)} toSet,
          metaData = (json \ "metaData").asOpt[JsObject].getOrElse(JsObject(Seq())),
          prevEvents = (json \ "prevEvents").asOpt[Seq[String]] match {
            case Some(ev) => ev map { i => ExternalId[Event](i) }
            case None => Seq()
          }
        )
      case ServerEventFamily(name) =>
        ServerEventMetadata(
          eventFamily = EventFamilies((json \ "eventFamily").as[String]),
          eventName = (json \ "eventName").as[String],
          metaData = (json \ "metaData").as[JsObject],
          prevEvents = (json \ "prevEvents").as[Seq[String]] map { i => ExternalId[Event](i) }
        )
    }
  )

}

object EventSerializer {
  implicit val eventSerializer = new EventSerializer
  implicit val eventMetadataSerializer = new EventMetadataSerializer
}
