package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.mvc.{PathBindable, QueryStringBindable}
import play.api.libs.json.JsArray

sealed abstract class ABookOriginType(val name:String) {
  override def toString:String = name
}

object ABookOriginType {
  def apply(name: String):ABookOriginType = {
    val trimmed = name.toLowerCase.trim
    ABookOrigins.ALL.find(_.name == trimmed).getOrElse(throw new IllegalArgumentException(s"unrecognized abook origin type: $name"))
  }
  def unapply(aot:ABookOriginType):Option[String] = Some(aot.name)

  implicit def queryStringBinder(implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[ABookOriginType] {
    override def bind(key:String, params: Map[String, Seq[String]]): Option[Either[String, ABookOriginType]] = {
      stringBinder.bind(key, params) map {
        case Right(value) => Right(ABookOriginType(value))
        case _ => Left(s"Unable to bind $key $params")
      }
    }
    override def unbind(key:String, state: ABookOriginType):String = stringBinder.unbind(key, state.name)
  }

  implicit def pathBinder = new PathBindable[ABookOriginType] {
    override def bind(key: String, value:String): Either[String, ABookOriginType] = Right(ABookOriginType(value))
    override def unbind(key: String, state:ABookOriginType): String = state.toString
  }

  import play.api.libs.json._
  implicit val format:Format[ABookOriginType] = Format(__.read[String].map(ABookOriginType(_)), new Writes[ABookOriginType] { def writes(o: ABookOriginType) = JsString(o.name) })
}

object ABookOrigins {
  case object IOS extends ABookOriginType("ios")
  case object GMAIL extends ABookOriginType("gmail")
  val ALL:Seq[ABookOriginType] = Seq(IOS, GMAIL)
}

case class ABookRawInfo(userId:Option[Id[User]], origin:ABookOriginType, contacts:JsArray)

object ABookRawInfo {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import com.keepit.common.db.Id

  implicit val format = (
      (__ \ 'userId).formatNullable(Id.format[User]) and
      (__ \ 'origin).format[String].inmap(ABookOriginType.apply _, unlift(ABookOriginType.unapply)) and
      (__ \ 'contacts).format[JsArray]
    )(ABookRawInfo.apply _, unlift(ABookRawInfo.unapply))

}

case class ABookInfo(id: Option[Id[ABookInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[ABookInfo] = ABookStates.ACTIVE,
    userId: Id[User],
    origin: ABookOriginType,
    rawInfoLoc: Option[String] = None
  ) extends Model[ABookInfo] {
  def withId(id: Id[ABookInfo]): ABookInfo = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): ABookInfo = this.copy(updatedAt = now)
}

object ABookStates extends States[ABookInfo]

object ABookInfo {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import com.keepit.common.db.Id

  implicit val format = (
      (__ \ 'id).formatNullable(Id.format[ABookInfo]) and
      (__ \ 'createdAt).format[DateTime] and
      (__ \ 'updatedAt).format[DateTime] and
      (__ \ 'state).format(State.format[ABookInfo]) and
      (__ \ 'userId).format(Id.format[User]) and
      (__ \ 'origin).format[ABookOriginType] and
      (__ \ 'rawInfoLoc).formatNullable[String]
    )(ABookInfo.apply, unlift(ABookInfo.unapply))
}
