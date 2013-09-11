package com.keepit.heimdal



import org.joda.time.DateTime

import play.api.libs.json.{Json, Format, JsResult, JsError, JsSuccess, JsObject, JsValue, JsArray, JsNumber, JsString}

case class UserEventType(name: String)

object UserEventType {
  implicit val format = Json.format[UserEventType]
}

case class UserEventContext(data: Map[String, Seq[Either[String, Double]]])

// object UserEventContext {
//   implicit val format = new Format[UserEventContext] {

//     def reads(json: JsValue): JsResult[UserEventContext] = {
//       val map : Map[String, Seq[Either[String, Double]]] = json match {
//         case obj: JsObject => obj.value.mapValues{ _ match {
//             case arr: JsArray => arr.value.map{ _ match{
//                 case JsNumber(x) => Right[String, Double](x.doubleValue)
//                 case JsString(s) => Left[String, Double](s)
//                 case _ => return JsError()
//               } 
//             }
//             case _ => return JsError()
//           }
//         }
//         case _ => return JsError()
//       }
//       JsSuccess(UserEventContext(map))
//     }

//   }
// }


case class UserEvent(
  userId: Long,
  context: UserEventContext,
  eventType: UserEventType,
  time: DateTime
)


// object UserEvent {
//   implicit val format = Json.format[UserEvent]
// }
