package com.keepit.model

import com.google.inject.{Provider, Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.db.{ExternalId, Id, State, SequenceNumber, NotFoundException}
import com.keepit.common.logging.Logging
import com.keepit.common.time.{zones, Clock}
import com.keepit.social._
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.heimdal.{HeimdalContextBuilder, HeimdalServiceClient}
import com.keepit.common.akka.SafeFuture
import scala.slick.jdbc.{PositionedResult, GetResult, StaticQuery}
import org.joda.time.DateTime
import scala.slick.lifted.{TableQuery, Tag}
import scala.slick.jdbc.{StaticQuery => Q}
import Q.interpolation


@ImplementedBy(classOf[UserRepoImpl])
trait UserRepo extends Repo[User] with RepoWithDelete[User] with ExternalIdColumnFunction[User] with SeqNumberFunction[User]{
  def allExcluding(excludeStates: State[User]*)(implicit session: RSession): Seq[User]
  def allActiveTimes()(implicit session: RSession): Seq[DateTime]
  def pageIncluding(includeStates: State[User]*)(page: Int, size: Int)(implicit session: RSession): Seq[User]
  def pageIncludingWithExp(includeStates: State[User]*)(includeExp: ExperimentType*)(page: Int, size: Int)(implicit session: RSession): Seq[User]
  def pageIncludingWithoutExp(includeStates: State[User]*)(excludeExp: ExperimentType*)(page: Int, size: Int)(implicit session: RSession): Seq[User]
  def countIncluding(includeStates: State[User]*)(implicit session: RSession): Int
  def countIncludingWithExp(includeStates: State[User]*)(includeExp: ExperimentType*)(implicit session: RSession): Int
  def countIncludingWithoutExp(includeStates: State[User]*)(excludeExp: ExperimentType*)(implicit session: RSession): Int
  def countNewUsers(implicit session: RSession): Int
  def getNoCache(id: Id[User])(implicit session: RSession): User
  def getOpt(id: Id[User])(implicit session: RSession): Option[User]
  def getAllIds()(implicit session: RSession): Set[Id[User]] //Note: Need to revisit when we have >50k users.
  def getUsersSince(seq: SequenceNumber, fetchSize: Int)(implicit session: RSession): Seq[User]
}

@Singleton
class UserRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val externalIdCache: UserExternalIdCache,
    val idCache: UserIdCache,
    basicUserCache: BasicUserUserIdCache,
    heimdal: HeimdalServiceClient,
    expRepoProvider: Provider[UserExperimentRepoImpl])
  extends DbRepo[User] with DbRepoWithDelete[User] with UserRepo with ExternalIdColumnDbFunction[User] with SeqNumberDbFunction[User] with Logging {

  import scala.slick.lifted.Query
  import DBSession._
  import db.Driver.simple._

  private val sequence = db.getSequence("user_sequence")

  private lazy val expRepo = expRepoProvider.get

  type RepoImpl = UserTable
  class UserTable(tag: Tag) extends RepoTable[User](db, tag, "user") with ExternalIdColumn[User] with SeqNumberColumn[User] {
    def firstName = column[String]("first_name", O.NotNull)
    def lastName = column[String]("last_name", O.NotNull)
    def pictureName = column[String]("picture_name", O.Nullable)
    def userPictureId = column[Id[UserPicture]]("user_picture_id", O.Nullable)
    def primaryEmailId = column[Id[EmailAddress]]("primary_email_id", O.Nullable)
    def * = (id.?, createdAt, updatedAt, externalId, firstName, lastName, state, pictureName.?, userPictureId.?, seq, primaryEmailId.?) <> ((User.apply _).tupled, User.unapply)
  }

  def table(tag: Tag) = new UserTable(tag)
  initTable()

  def allActiveTimes()(implicit session: RSession): Seq[DateTime] = {
    implicit val GetDateTime: GetResult[DateTime] = new GetResult[DateTime] {
      def apply(r: PositionedResult) = new DateTime(r.nextTimestamp getTime, zones.UTC)
    }
    StaticQuery.queryNA[(DateTime)](s"""select created_at from user u where state = 'active' and not exists (select id from user_experiment x where u.id = x.user_id and x.experiment_type = 'fake');""").list
  }

  override def save(user: User)(implicit session: RWSession): User = {
    val toSave = user.copy(seq = sequence.incrementAndGet())
    super.save(toSave)
  }

  def allExcluding(excludeStates: State[User]*)(implicit session: RSession): Seq[User] =
    (for (u <- rows if !(u.state inSet excludeStates)) yield u).list

  def pageIncluding(includeStates: State[User]*)(page: Int = 0, size: Int = 20)(implicit session: RSession): Seq[User] = {
    val q = for {
      t <- rows if (t.state inSet includeStates)
    } yield t
    q.sortBy(_.id desc).drop(page * size).take(size).list
  }

  def pageIncludingWithExp(includeStates: State[User]*)(includeExp: ExperimentType*)(page: Int = 0, size: Int = 20)
                          (implicit session: RSession): Seq[User] = {
    val q = for {
      u <- rows if ((u.state inSet includeStates) &&
        (for { e <- expRepo.rows if e.userId === u.id && (e.experimentType inSet includeExp) && e.state === UserExperimentStates.ACTIVE } yield e).exists)
    } yield u
    q.sortBy(_.id desc).drop(page * size).take(size).list
  }

  def pageIncludingWithoutExp(includeStates: State[User]*)(excludeExp: ExperimentType*)(page: Int = 0, size: Int = 20)
                             (implicit session: RSession): Seq[User] = {
    val q = for {
      u <- rows if ((u.state inSet includeStates) &&
        !(for { e <- expRepo.rows if u.id === e.userId && (e.experimentType inSet excludeExp) && e.state === UserExperimentStates.ACTIVE } yield e).exists)
    } yield u
    q.sortBy(_.id desc).drop(page * size).take(size).list
  }

  def countIncluding(includeStates: State[User]*)(implicit session: RSession): Int = {
    val q = (for (u <- rows if (u.state inSet includeStates)) yield u)
    Query(q.length).first
  }

  def countIncludingWithExp(includeStates: State[User]*)(includeExp: ExperimentType*)
                           (implicit session: RSession): Int = {
    val q = for {
      u <- rows if ((u.state inSet includeStates) &&
        (for { e <- expRepo.rows if e.userId === u.id && (e.experimentType inSet includeExp) && e.state === UserExperimentStates.ACTIVE } yield e).exists)
    } yield u
    Query(q.length).first
  }

  def countIncludingWithoutExp(includeStates: State[User]*)(excludeExp: ExperimentType*)
                           (implicit session: RSession): Int = {
    val q = for {
      u <- rows if ((u.state inSet includeStates) &&
        !(for { e <- expRepo.rows if u.id === e.userId && (e.experimentType inSet excludeExp) && e.state === UserExperimentStates.ACTIVE } yield e).exists)
    } yield u
    Query(q.length).first
  }

  def countNewUsers(implicit session: RSession): Int = {
    sql"select count(distinct u.id) from kifi_installation ki, user u where ki.user_id = u.id".as[Int].first
  }

  override def deleteCache(user: User)(implicit session: RSession): Unit = {
    for (id <- user.id) {
      idCache.remove(UserIdKey(id))
      basicUserCache.remove(BasicUserUserIdKey(id))
      externalIdCache.remove(UserExternalIdKey(user.externalId))
    }
    invalidateMixpanel(user.withState(UserStates.INACTIVE))
  }

  override def invalidateCache(user: User)(implicit session: RSession) = {
    for (id <- user.id) {
      idCache.set(UserIdKey(id), user)
      basicUserCache.set(BasicUserUserIdKey(id), BasicUser.fromUser(user))
    }

    externalIdCache.set(UserExternalIdKey(user.externalId), user)
    invalidateMixpanel(user)
  }

  private def invalidateMixpanel(user: User) = SafeFuture {
    if (user.state == UserStates.INACTIVE)
      heimdal.deleteUser(user.id.get)
    else {
      heimdal.setUserAlias(user.id.get, user.externalId)
      val properties = new HeimdalContextBuilder
      properties += ("$first_name", user.firstName)
      properties += ("$last_name", user.lastName)
      properties += ("$created", user.createdAt)
      properties += ("state", user.state.value)
      properties += ("userId", user.id.get.id)
      properties += ("admin", "https://admin.kifi.com" + com.keepit.controllers.admin.routes.AdminUserController.userView(user.id.get).url)
      heimdal.setUserProperties(user.id.get, properties.data.toSeq: _*)
    }
  }

  override def get(id: Id[User])(implicit session: RSession): User = {
    idCache.getOrElse(UserIdKey(id)) {
      (for(f <- rows if f.id is id) yield f).firstOption.getOrElse(throw NotFoundException(id))
    }
  }

  def getNoCache(id: Id[User])(implicit session: RSession): User = {
    (for(f <- rows if f.id is id) yield f).firstOption.getOrElse(throw NotFoundException(id))
  }

  def getOpt(id: Id[User])(implicit session: RSession): Option[User] = {
    idCache.getOrElseOpt(UserIdKey(id)) {
      (for(f <- rows if f.id is id) yield f).firstOption
    }
  }

  override def getOpt(id: ExternalId[User])(implicit session: RSession): Option[User] = {
    externalIdCache.getOrElseOpt(UserExternalIdKey(id)) {
      (for(f <- rows if f.externalId === id) yield f).firstOption
    }
  }

  def getAllIds()(implicit session: RSession): Set[Id[User]] = { //Note: Need to revisit when we have >50k users.
    (for (row <- rows) yield row.id).list.toSet
  }

  def getUsersSince(seq: SequenceNumber, fetchSize: Int)(implicit session: RSession): Seq[User] = super.getBySequenceNumber(seq, fetchSize)
}
