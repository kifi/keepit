package com.keepit.eliza.model

import com.keepit.common.db.{Id, Model}
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import com.keepit.common.db.slick.{Repo, DbRepo, DataBaseComponent}
import com.keepit.model.User
import com.keepit.common.time._

import com.google.inject.{Inject, Singleton, ImplementedBy}

import play.api.libs.json.{Json, JsArray}



@ImplementedBy(classOf[MessageSearchHistoryRepoImpl])
trait MessageSearchHistoryRepo extends Repo[MessageSearchHistory] {
  def getOrCreate(userId: Id[User])(implicit session: RWSession): MessageSearchHistory
}

@Singleton
class MessageSearchHistoryRepoImpl @Inject() (
    val clock: Clock,
    val db: DataBaseComponent
  ) extends DbRepo[MessageSearchHistory] with MessageSearchHistoryRepo {

  import db.Driver.simple._

  implicit def seqStringMapper[M <: Model[M]] = MappedColumnType.base[Seq[String], String]({ dest =>
    Json.stringify(Json.toJson(dest))
  }, { source =>
    Json.parse(source) match {
      case x: JsArray => {
        x.value.map(_.as[String])
      }
      case _ => throw InvalidDatabaseEncodingException(s"Could not decode JSON for Seq of Strings (message queries)")
    }
  })

  type RepoImpl = MessageSearchHistoryTable

  class MessageSearchHistoryTable(tag: Tag) extends RepoTable[MessageSearchHistory](db, tag, "message_search_history") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def optOut = column[Boolean]("opt_out", O.NotNull)
    def queries = column[Seq[String]]("queries", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, optOut, queries) <> ((MessageSearchHistory.apply _).tupled, MessageSearchHistory.unapply _)
  }
  def table(tag: Tag) = new MessageSearchHistoryTable(tag)


  override def deleteCache(model: MessageSearchHistory)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: MessageSearchHistory)(implicit session: RSession): Unit = {}

  def getOrCreate(userId: Id[User])(implicit session: RWSession): MessageSearchHistory = {
    (for (row <- rows if row.userId === userId) yield row).firstOption.getOrElse{
      save(MessageSearchHistory(userId=userId))
    }
  }

}
