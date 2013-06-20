package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{FortyTwoTypeMappers, DbRepo, DataBaseComponent, Repo}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock

@ImplementedBy(classOf[UserTopicRepoImpl])
trait UserTopicRepo extends Repo[UserTopic]{
  def getByUserId(userId: Id[User])(implicit session: RSession):Option[UserTopic]
}

@Singleton
class UserTopicRepoImpl @Inject() (
                                    val db: DataBaseComponent,
                                    val clock: Clock
                                    ) extends DbRepo[UserTopic] with UserTopicRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[UserTopic](db, "user_topic"){
    def userId = column[Id[User]]("user_id", O.NotNull)
    def topic = column[Array[Byte]]("topic", O.NotNull)
    def * = id.? ~ userId ~ topic ~ createdAt ~ updatedAt <> (UserTopic, UserTopic.unapply _)
  }

  def getByUserId(userId: Id[User])(implicit session: RSession): Option[UserTopic] = {
    (for(r <- table if r.userId === userId) yield r).firstOption
  }
}




