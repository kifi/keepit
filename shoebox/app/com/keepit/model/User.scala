package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db._
import com.keepit.common.db.NotFoundException
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import ru.circumflex.orm._
import play.api.libs.json._
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.cache.ShoeboxCache
import play.api.Play.current

case class User(
  id: Option[Id[User]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[User] = ExternalId(),
  firstName: String,
  lastName: String,
  state: State[User] = UserStates.ACTIVE
) extends ModelWithExternalId[User] {
  def withId(id: Id[User]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withName(firstName: String, lastName: String) = copy(firstName = firstName, lastName = lastName)
  def withExternalId(id: ExternalId[User]) = copy(externalId = id)

  def save(implicit conn: Connection) = {
    val entity = UserEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
}

@ImplementedBy(classOf[UserRepoImpl])
trait UserRepo extends Repo[User] with ExternalIdColumnFunction[User] {
}

@Singleton
class UserRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[User] with UserRepo with ExternalIdColumnDbFunction[User] {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[User]("user")(db) with ExternalIdColumn[User] {
    def firstName = column[String]("first_name", O.NotNull)
    def lastName = column[String]("last_name", O.NotNull)
    def state = column[State[User]]("state", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ firstName ~ lastName ~ state <> (User, User.unapply _)
  }
}

object UserCxRepo {

  //slicked
  def all(implicit conn: Connection): Seq[User] =
    UserEntity.all.map(_.view)

  //slicked
  def get(id: Id[User])(implicit conn: Connection): User =
    inject[ShoeboxCache].getOrElse(3600)(id) {
      UserEntity.get(id).get.view
    }

  //slicked
  def get(externalId: ExternalId[User])(implicit conn: Connection): User =
    getOpt(externalId).getOrElse(throw NotFoundException(externalId))

  //slicked
  def getOpt(externalId: ExternalId[User])(implicit conn: Connection): Option[User] =
    inject[ShoeboxCache].getOrElse(3600)(externalId) {
      (UserEntity AS "u").map { u => SELECT (u.*) FROM u WHERE (u.externalId EQ externalId) unique }.map(_.view)
    }

}

object UserStates {
  val ACTIVE = State[User]("active")
  val INACTIVE = State[User]("inactive")
}

private[model] class UserEntity extends Entity[User, UserEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val externalId = "external_id".EXTERNAL_ID[User].NOT_NULL(ExternalId())
  val firstName = "first_name".VARCHAR(256).NOT_NULL
  val lastName = "last_name".VARCHAR(256).NOT_NULL
  val state = "state".STATE[User].NOT_NULL(UserStates.ACTIVE)

  def relation = UserEntity

  def view(implicit conn: Connection): User = User(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    externalId = externalId(),
    firstName = firstName(),
    lastName = lastName(),
    state = state()
  )
}

private[model] object UserEntity extends UserEntity with EntityTable[User, UserEntity] {
  override def relationName = "user"

  def apply(view: User): UserEntity = {
    val user = new UserEntity
    user.id.set(view.id)
    user.createdAt := view.createdAt
    user.updatedAt := view.updatedAt
    user.externalId := view.externalId
    user.firstName := view.firstName
    user.lastName := view.lastName
    user.state := view.state
    user
  }
}


