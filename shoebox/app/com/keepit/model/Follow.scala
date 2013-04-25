package com.keepit.model

import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import java.net.URI
import java.security.MessageDigest
import scala.collection.mutable
import play.api.libs.json._

case class Follow (
  id: Option[Id[Follow]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  uriId: Id[NormalizedURI],
  urlId: Option[Id[URL]] = None,
  state: State[Follow] = FollowStates.ACTIVE
) extends Model[Follow] {
  def withId(id: Id[Follow]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def activate = copy(state = FollowStates.ACTIVE)
  def deactivate = copy(state = FollowStates.INACTIVE)
  def isActive = state == FollowStates.ACTIVE
  def withUrlId(urlId: Id[URL]) = copy(urlId = Some(urlId))
  def withNormUriId(normUriId: Id[NormalizedURI]) = copy(uriId = normUriId)
}

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

object FollowStates extends States[Follow]
