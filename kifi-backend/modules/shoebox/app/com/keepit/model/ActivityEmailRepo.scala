package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.{ States, Model, Id, State }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.time._
import org.joda.time.DateTime

@ImplementedBy(classOf[ActivityEmailRepoImpl])
trait ActivityEmailRepo extends Repo[ActivityEmail] {
}

@Singleton
class ActivityEmailRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[ActivityEmail] with ActivityEmailRepo {

  import db.Driver.simple._

  type RepoImpl = ActivityEmailTable
  class ActivityEmailTable(tag: Tag) extends RepoTable[ActivityEmail](db, tag, "activity_email") {
    def * = (id.?, createdAt, updatedAt, state) <> ((ActivityEmail.apply _).tupled, ActivityEmail.unapply)
  }

  def table(tag: Tag) = new ActivityEmailTable(tag)
  initTable()

  override def invalidateCache(model: ActivityEmail)(implicit session: RSession): Unit = {}

  override def deleteCache(model: ActivityEmail)(implicit session: RSession): Unit = {}

}

object ActivityEmailStates extends States[ActivityEmail] {
  val PENDING = State[ActivityEmail]("pending")
}
