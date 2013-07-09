package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, Id}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock
import scala.Some

@ImplementedBy(classOf[FollowRepoImpl])
trait FollowRepo extends Repo[Follow] {
  def get(userId: Id[User], uriId: Id[NormalizedURI], excludeState: Option[State[Follow]] = Some(FollowStates.INACTIVE))(implicit session: RSession): Option[Follow]
  def getByUser(userId: Id[User], excludeState: Option[State[Follow]] = Some(FollowStates.INACTIVE))(implicit session: RSession): Seq[Follow]
  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Follow]] = Some(FollowStates.INACTIVE))(implicit session: RSession): Seq[Follow]
  def getByUrl(urlId: Id[URL], excludeState: Option[State[Follow]] = Some(FollowStates.INACTIVE))(implicit session: RSession): Seq[Follow]
}

@Singleton
class FollowRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[Follow] with FollowRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[Follow](db, "follow") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def urlId = column[Id[URL]]("url_id", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ uriId ~ urlId.? ~ state <> (Follow, Follow.unapply _)
  }

  def get(userId: Id[User], uriId: Id[NormalizedURI], excludeState: Option[State[Follow]])(implicit session: RSession): Option[Follow] =
    (for (f <- table if f.uriId === uriId && f.userId === userId && f.state =!= excludeState.getOrElse(null)) yield f).firstOption

  def getByUser(userId: Id[User], excludeState: Option[State[Follow]])(implicit session: RSession): Seq[Follow] =
    (for (f <- table if f.userId === userId && f.state =!= excludeState.getOrElse(null)) yield f).list

  def getByUri(uriId: Id[NormalizedURI], excludeState: Option[State[Follow]])(implicit session: RSession): Seq[Follow] =
    (for (f <- table if f.uriId === uriId && f.state =!= excludeState.getOrElse(null)) yield f).list

  def getByUrl(urlId: Id[URL], excludeState: Option[State[Follow]])(implicit session: RSession): Seq[Follow] =
    (for (f <- table if f.urlId === urlId && f.state =!= excludeState.getOrElse(null)) yield f).list
}
