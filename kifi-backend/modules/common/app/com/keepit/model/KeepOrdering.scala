package com.keepit.model

import com.keepit.common.json.EnumFormat
import com.keepit.common.reflection.Enumerator
import play.api.libs.json.Format
import play.api.mvc.QueryStringBindable

sealed abstract class KeepOrdering(val value: String)

object KeepOrdering extends Enumerator[KeepOrdering] {
  case object LAST_ACTIVITY_AT extends KeepOrdering("last_activity_at")
  case object KEPT_AT extends KeepOrdering("kept_at")

  val all = _all
  def fromStr(str: String): Option[KeepOrdering] = all.find(_.value == str)
  def apply(str: String): KeepOrdering = fromStr(str).getOrElse(throw new Exception(s"could not extract keep ordering from $str"))

  implicit val format: Format[KeepOrdering] = EnumFormat.format(fromStr, _.value)

  implicit def queryStringBinder(implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[KeepOrdering] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, KeepOrdering]] = {
      stringBinder.bind(key, params).map {
        case Left(_) => Left("Could not bind a KeepOrdering")
        case Right(str) => fromStr(str).map(ord => Right(ord)).getOrElse(Left("Could not bind a KeepOrdering"))
      }
    }
    override def unbind(key: String, ordering: KeepOrdering): String = {
      stringBinder.unbind(key, ordering.value)
    }
  }

}

