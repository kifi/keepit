package com.keepit.heimdal

import com.keepit.serializer.TypeCode
import play.api.libs.json._
import org.apache.commons.codec.binary.Base64
import play.api.libs.ws.WS
import com.keepit.model.{Gender, User}
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.db.Id
import scala.concurrent.Future

class MixpanelClient(projectToken: String, shoebox: ShoeboxServiceClient) {

  def track[E <: HeimdalEvent: TypeCode](event: E) = {
    val eventCode = implicitly[TypeCode[E]]
    val eventName = s"${eventCode}_${event.eventType.name}"
    val distinctId = event match {
      case e: UserEvent => s"${eventCode}_${e.userId.toString}"
      case _ => eventCode.toString
    }

    val superPropertiesFuture = event match {
      case e: UserEvent => getSuperProperties(Id[User](e.userId))
      case _ => Future.successful(Seq.empty)
    }

    val properties = getProperties(event.context) :+ ("distinct_id" -> JsString(distinctId)) :+ ("token", JsString(projectToken)) :+ ("time", JsNumber(event.time.getMillis))

    for (superProperties <- superPropertiesFuture recover { case _ : Throwable => Seq.empty } ) yield {
      val data = Json.obj("event" -> JsString(eventName), "properties" -> JsObject(superProperties ++ properties))
      sendData("http://api.mixpanel.com/track", data)
    }
  }

  private def getProperties(context: EventContext) : Seq[(String, JsValue)] = context.data.map {
      case ("remoteAddress", Seq(ContextStringData(ip))) => "ip" -> JsString(ip)
      case ("experiment", experiments) => "experiment" -> toJsArray(experiments)
      case (key, Seq(ContextStringData(s))) => key -> JsString(s)
      case (key, Seq(ContextDoubleData(x))) => key -> JsNumber(x)
      case (key, seq) => key -> toJsArray(seq)
  }.toSeq

  private def getSuperProperties(userId: Id[User]): Future[Seq[(String, JsValue)]] =
    shoebox.getUserValue(userId, Gender.key).map(_.map { gender =>
      Seq(Gender.key -> JsString(Gender(gender).toString))
    } getOrElse(Seq.empty))

  private def toJsArray(seq: Seq[ContextData]): JsArray = JsArray(seq.map {
    case ContextStringData(s) => JsString(s)
    case ContextDoubleData(x) => JsNumber(x)
  })

  def engage(user: User) = getSuperProperties(user.id.get) foreach { superProperties =>
    val data = Json.obj(
      "$token" -> JsString(projectToken),
      "$distinct_id" -> JsString(s"${UserEvent.typeCode.code}_${user.id.get}"),
      "$ip" -> JsNumber(0),
      "$set" -> JsObject(superProperties ++ Seq(
        "$first_name" -> JsString(user.firstName),
        "$last_name" -> JsString(user.lastName),
        "$created" -> JsString(user.createdAt.toString),
        "state" -> JsString(user.state.value)
      ))
    )
    sendData("http://api.mixpanel.com/engage", data)
  }

  def delete(user: User) = {
    val data = Json.obj(
      "$token" -> JsString(projectToken),
      "$distinct_id" -> JsString(s"${UserEvent.typeCode.code}_${user.id.get}"),
      "$delete" -> JsString("")
    )
    sendData("http://api.mixpanel.com/engage", data)
  }


  private def sendData(url: String, data: JsObject) = {
    val request = WS.url(url).withQueryString(("data", Base64.encodeBase64String(Json.stringify(data).getBytes)))
    new SafeFuture(
      request.get().map {
        case response if response.body == "0\n" => throw new Exception(s"Mixpanel endpoint $url refused data: $data")
        case response => response
      }
    )
  }

}