package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.crypto._
import com.keepit.inject._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import play.api.Play.current
import ru.circumflex.orm._
import java.net.URI
import java.security.MessageDigest
import scala.collection.mutable
import com.keepit.common.logging.Logging

case class Follow (
  id: Option[Id[Follow]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  uriId: Id[NormalizedURI],
  state: State[Follow] = FollowCxRepo.States.ACTIVE
) extends Model[Follow] {
  def withId(id: Id[Follow]) = this.copy(id = Some(id))
  def updateTime(now: DateTime) = this.copy(updatedAt = now)
  def activate = copy(state = FollowCxRepo.States.ACTIVE)
  def deactivate = copy(state = FollowCxRepo.States.INACTIVE)
  def isActive = state == FollowCxRepo.States.ACTIVE
  def save(implicit session: RWSession): Follow = inject[Repo[Follow]].save(this)
}

class FollowRepoImpl extends DbRepo[Follow] {
  import db.Driver.Implicit._ // here's the driver, abstracted away

  override val table = new RepoTable[Follow]("follow") {
    import FortyTwoTypeMappers._
    import org.scalaquery.ql._
    import org.scalaquery.ql.ColumnOps._
    import org.scalaquery.ql.TypeMapper._
    import org.scalaquery.ql.basic.BasicProfile
    import org.scalaquery.ql.extended.ExtendedTable
    import org.scalaquery.util.{Node, UnaryNode, BinaryNode}

    def userId = column[Id[User]]("user_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def state = column[State[Follow]]("state", O.NotNull)
    def * = idCreateUpdateBase ~ userId ~ uriId ~ state <> (Follow, Follow.unapply _)
  }
}

object FollowCxRepo {

  def all(implicit conn: Connection): Seq[Follow] =
    FollowEntity.all.map(_.view)

  def all(userId: Id[User])(implicit conn: Connection): Seq[Follow] =
    (FollowEntity AS "f").map { f => SELECT (f.*) FROM f WHERE (f.userId EQ userId) list }.map(_.view)

  def get(uriId: Id[NormalizedURI])(implicit conn: Connection): Seq[Follow] =
    (FollowEntity AS "f").map { f => SELECT (f.*) FROM f WHERE (f.uriId EQ uriId AND (f.state EQ FollowCxRepo.States.ACTIVE)) list }.map(_.view)

  def get(id: Id[Follow])(implicit conn: Connection): Follow =
    FollowEntity.get(id).map(_.view).getOrElse(throw NotFoundException(id))

  def get(userId: Id[User], uri: NormalizedURI)(implicit conn: Connection): Option[Follow] =
    get(userId, uri.id.get)

  def get(userId: Id[User], uriId: Id[NormalizedURI])(implicit conn: Connection): Option[Follow] =
    (FollowEntity AS "f").map { f => SELECT (f.*) FROM f WHERE (f.userId EQ userId AND (f.uriId EQ uriId)) unique }.map(_.view)

  def getOrThrow(userId: Id[User], uriId: Id[NormalizedURI])(implicit conn: Connection): Follow =
    get(userId, uriId).getOrElse(throw NotFoundException(classOf[Follow], userId, uriId))


  object States {
    val ACTIVE = State[Follow]("active")
    val INACTIVE = State[Follow]("inactive")
  }
}

private[model] class FollowEntity extends Entity[Follow, FollowEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val userId = "user_id".ID[User].NOT_NULL
  val uriId = "uri_id".ID[NormalizedURI].NOT_NULL
  val state = "state".STATE[Follow].NOT_NULL(FollowCxRepo.States.ACTIVE)

  def relation = FollowEntity

  def view(implicit conn: Connection): Follow = Follow(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    userId = userId(),
    uriId = uriId(),
    state = state()
  )
}

private[model] object FollowEntity extends FollowEntity with EntityTable[Follow, FollowEntity] {
  override def relationName = "follow"

  def apply(view: Follow): FollowEntity = {
    val uri = new FollowEntity
    uri.id.set(view.id)
    uri.createdAt := view.createdAt
    uri.updatedAt := view.updatedAt
    uri.userId := view.userId
    uri.uriId := view.uriId
    uri.state := view.state
    uri
  }
}
