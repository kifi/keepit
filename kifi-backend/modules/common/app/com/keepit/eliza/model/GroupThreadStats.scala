package com.keepit.eliza.model

import com.keepit.common.db.Id
import org.joda.time.DateTime

import play.api.libs.json._
import play.api.libs.functional.syntax._

case class GroupThreadStats(threadId: Long, date: DateTime, numUsers: Int)

object GroupThreadStats {

  implicit val format = (
    (__ \ "thread").format[Long] and
    (__ \ "date").format[DateTime] and
    (__ \ "numUsers").format[Int]
  )(GroupThreadStats.apply, unlift(GroupThreadStats.unapply))

}
