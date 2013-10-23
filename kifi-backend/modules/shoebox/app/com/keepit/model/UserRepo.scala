package com.keepit.model

import scala.concurrent._
import com.google.inject.{Inject, Singleton, ImplementedBy, Provider}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.db.{ExternalId, Id, State}
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.shoebox.usersearch._
import com.keepit.social._
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.db.SequenceNumber

@ImplementedBy(classOf[UserRepoImpl])
trait UserRepo extends Repo[User] with ExternalIdColumnFunction[User] {
  def allExcluding(excludeStates: State[User]*)(implicit session: RSession): Seq[User]
  def pageExcluding(excludeStates: State[User]*)(page: Int, size: Int)(implicit session: RSession): Seq[User]
  def countExcluding(excludeStates: State[User]*)(implicit session: RSession): Int
  def getOpt(id: Id[User])(implicit session: RSession): Option[User]
  def getAllIds()(implicit session: RSession): Set[Id[User]] //Note: Need to revisit when we have >50k users.
}

@Singleton
class UserRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val externalIdCache: UserExternalIdCache,
    val idCache: UserIdCache,
    basicUserCache: BasicUserUserIdCache,
    userIndexProvider: Provider[UserIndex])
  extends DbRepo[User] with UserRepo with ExternalIdColumnDbFunction[User] with Logging {

  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._
  import FortyTwoTypeMappers._
  
  private val sequence = db.getSequence("user_sequence")


  override val table = new RepoTable[User](db, "user") with ExternalIdColumn[User] {
    def firstName = column[String]("first_name", O.NotNull)
    def lastName = column[String]("last_name", O.NotNull)
    def pictureName = column[String]("picture_name", O.Nullable)
    def userPictureId = column[Id[UserPicture]]("user_picture_id", O.Nullable)
    def seq = column[SequenceNumber]("seq", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ firstName ~ lastName ~ state ~ pictureName.? ~ userPictureId.? ~ seq <> (User.apply _, User.unapply _)
  }
  
  override def save(user: User)(implicit session: RWSession): User = {
    val toSave = user.copy(seq = sequence.incrementAndGet())
    super.save(toSave)
  }

  def allExcluding(excludeStates: State[User]*)(implicit session: RSession): Seq[User] =
    (for (u <- table if !(u.state inSet excludeStates)) yield u).list

  def pageExcluding(excludeStates: State[User]*)(page: Int = 0, size: Int = 20)(implicit session: RSession): Seq[User] = {
    val q = for {
      t <- table if !(t.state inSet excludeStates)
    } yield t
    q.sortBy(_.id desc).drop(page * size).take(size).list
  }

  def countExcluding(excludeStates: State[User]*)(implicit session: RSession): Int = {
    val q = (for (u <- table if !(u.state inSet excludeStates)) yield u)
    Query(q.length).first
  }

  override def invalidateCache(user: User)(implicit session: RSession) = {
    for (id <- user.id) {
      idCache.set(UserIdKey(id), user)
      basicUserCache.set(BasicUserUserIdKey(id), BasicUser.fromUser(user))
    }
    externalIdCache.set(UserExternalIdKey(user.externalId), user)
    future {
      userIndexProvider.get.addUser(user)
    }
    user
  }

  override def get(id: Id[User])(implicit session: RSession): User = {
    idCache.getOrElse(UserIdKey(id)) {
      (for(f <- table if f.id is id) yield f).first
    }
  }

  def getOpt(id: Id[User])(implicit session: RSession): Option[User] =
    idCache.getOrElseOpt(UserIdKey(id)) { (for(f <- table if f.id is id) yield f).firstOption }

  override def getOpt(id: ExternalId[User])(implicit session: RSession): Option[User] = {
    externalIdCache.getOrElseOpt(UserExternalIdKey(id)) {
      (for(f <- externalIdColumn if f.externalId === id) yield f).firstOption
    }
  }

  def getAllIds()(implicit session: RSession): Set[Id[User]] = { //Note: Need to revisit when we have >50k users.
    (for (row <- table) yield row.id).list.toSet
  }
}
