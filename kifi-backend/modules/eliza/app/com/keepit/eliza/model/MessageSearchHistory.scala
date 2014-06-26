package com.keepit.eliza.model


import com.keepit.common.db.{Model, Id}
import com.keepit.common.time._
import com.keepit.model.User

import org.joda.time.DateTime

object MessageSearchHistory {
  val MAX_HISTORY_LENGTH = 20
}


case class MessageSearchHistory(
  id: Option[Id[MessageSearchHistory]] = None,
  createdAt: DateTime = currentDateTime,
  updateAt: DateTime = currentDateTime,
  userId: Id[User],
  optOut: Boolean = false,
  queries: Seq[String] = Seq.empty,
  emails: Seq[String] = Seq.empty
) extends Model[MessageSearchHistory] {

  def withId(id: Id[MessageSearchHistory]): MessageSearchHistory = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): MessageSearchHistory = this.copy(updateAt = updateTime)

  def withNewQuery(q: String): MessageSearchHistory = { //Really inefficient, but it should do for a while -Stephen
    this.copy(
      queries = (q +: queries.filter(_!=q)).take(MessageSearchHistory.MAX_HISTORY_LENGTH)
    )
  }

  def withNewEmails(es: Seq[String]): MessageSearchHistory = { //Really inefficient, but it should do for a while -Stephen
    this.copy(
      emails = (es ++ emails.filter(!es.contains(_))).take(MessageSearchHistory.MAX_HISTORY_LENGTH) //ZZZ contains check
    )
  }

  def withOptOut(optOut: Boolean): MessageSearchHistory = {
    if (optOut) {
      this.copy(queries = Seq.empty, optOut = true)
    } else {
      this.copy(optOut = false)
    }
  }

  def withoutHistory(): MessageSearchHistory = {
    this.copy(queries = Seq.empty, emails = Seq.empty)
  }


}
