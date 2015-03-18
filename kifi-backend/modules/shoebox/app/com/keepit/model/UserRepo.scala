package com.keepit.model

import com.google.inject.{ Provider, Inject, Singleton, ImplementedBy }
import com.keepit.commanders.{ UserProfileTab, UserMetadataKey, UserMetadataCache, UsernameOps }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.db._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulingProperties, SequencingActor, SequencingPlugin }
import com.keepit.common.time.{ zones, Clock }
import com.keepit.social._
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.heimdal.{ HeimdalContextBuilder, HeimdalServiceClient }
import com.keepit.common.akka.SafeFuture
import scala.slick.jdbc.{ PositionedResult, GetResult, StaticQuery }
import org.joda.time.DateTime
import scala.slick.lifted.{ TableQuery, Tag }
import scala.slick.jdbc.{ StaticQuery => Q }
import Q.interpolation
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._

@ImplementedBy(classOf[UserRepoImpl])
trait UserRepo extends Repo[User] with RepoWithDelete[User] with ExternalIdColumnFunction[User] with SeqNumberFunction[User] {
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
  def getAllIds()(implicit session: RSession): Set[Id[User]] //Note: Need to revisit when we have >50k users.
  def getAllActiveIds()(implicit session: RSession): Seq[Id[User]]
  def getUsersSince(seq: SequenceNumber[User], fetchSize: Int)(implicit session: RSession): Seq[User]
  def getUsers(ids: Seq[Id[User]])(implicit session: RSession): Map[Id[User], User]
  def getAllUsers(ids: Seq[Id[User]])(implicit session: RSession): Map[Id[User], User]
  def getAllUsersByExternalId(ids: Seq[ExternalId[User]])(implicit session: RSession): Map[ExternalId[User], User]
  def getByUsername(username: Username)(implicit session: RSession): Option[User]
  def getRecentActiveUsers(since: DateTime = currentDateTime.minusDays(1))(implicit session: RSession): Seq[Id[User]]
}

@Singleton
class UserRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val externalIdCache: UserExternalIdCache,
  val idCache: UserIdCache,
  airbrake: AirbrakeNotifier,
  basicUserCache: BasicUserUserIdCache,
  userMetadataCache: UserMetadataCache,
  usernameCache: UsernameCache,
  heimdal: HeimdalServiceClient,
  expRepoProvider: Provider[UserExperimentRepoImpl])
    extends DbRepo[User] with DbRepoWithDelete[User] with UserRepo with ExternalIdColumnDbFunction[User] with SeqNumberDbFunction[User] with Logging {

  import scala.slick.lifted.Query
  import DBSession._
  import db.Driver.simple._

  private lazy val expRepo = expRepoProvider.get

  type RepoImpl = UserTable
  class UserTable(tag: Tag) extends RepoTable[User](db, tag, "user") with ExternalIdColumn[User] with SeqNumberColumn[User] {
    def firstName = column[String]("first_name", O.NotNull)
    def lastName = column[String]("last_name", O.NotNull)
    def pictureName = column[String]("picture_name", O.Nullable)
    def userPictureId = column[Id[UserPicture]]("user_picture_id", O.Nullable)
    def primaryEmail = column[EmailAddress]("primary_email", O.Nullable)
    def username = column[Username]("username", O.NotNull)
    def normalizedUsername = column[String]("normalized_username", O.NotNull)
    def * = (id.?, createdAt, updatedAt, externalId, firstName, lastName, state, pictureName.?, userPictureId.?, seq, primaryEmail.?, username, normalizedUsername) <> ((User.apply _).tupled, User.unapply)
  }

  def table(tag: Tag) = new UserTable(tag)
  initTable()

  def allActiveTimes()(implicit session: RSession): Seq[DateTime] = {
    StaticQuery.queryNA[(DateTime)](s"""select created_at from user u where state = 'active' and not exists (select id from user_experiment x where u.id = x.user_id and x.experiment_type = 'fake');""").list
  }

  override def save(user: User)(implicit session: RWSession): User = {
    val toSave = user.copy(seq = deferredSeqNum())
    user.id foreach { id =>
      val currentUser = get(id)
      if (currentUser.username != user.username && currentUser.createdAt.isBefore(clock.now.minusHours(1))) {
        airbrake.notify(s"username changes for user ${user.id.get}. $currentUser -> $user")
      }
    }
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

  def pageIncludingWithExp(includeStates: State[User]*)(includeExp: ExperimentType*)(page: Int = 0, size: Int = 20)(implicit session: RSession): Seq[User] = {
    val q = for {
      u <- rows if ((u.state inSet includeStates) &&
        (for { e <- expRepo.rows if e.userId === u.id && (e.experimentType inSet includeExp) && e.state === UserExperimentStates.ACTIVE } yield e).exists)
    } yield u
    q.sortBy(_.id desc).drop(page * size).take(size).list
  }

  def pageIncludingWithoutExp(includeStates: State[User]*)(excludeExp: ExperimentType*)(page: Int = 0, size: Int = 20)(implicit session: RSession): Seq[User] = {
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

  def countIncludingWithExp(includeStates: State[User]*)(includeExp: ExperimentType*)(implicit session: RSession): Int = {
    val q = for {
      u <- rows if ((u.state inSet includeStates) &&
        (for { e <- expRepo.rows if e.userId === u.id && (e.experimentType inSet includeExp) && e.state === UserExperimentStates.ACTIVE } yield e).exists)
    } yield u
    Query(q.length).first
  }

  def countIncludingWithoutExp(includeStates: State[User]*)(excludeExp: ExperimentType*)(implicit session: RSession): Int = {
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
    user.id map { id =>
      idCache.remove(UserIdKey(id))
      basicUserCache.remove(BasicUserUserIdKey(id))
      UserProfileTab.all.foreach(v => userMetadataCache.remove(UserMetadataKey(id, v)))
      usernameCache.remove(UsernameKey(user.username))
      externalIdCache.remove(UserExternalIdKey(user.externalId))
    }
    invalidateMixpanel(user.withState(UserStates.INACTIVE))
  }

  override def invalidateCache(user: User)(implicit session: RSession) = {
    if (user.state == UserStates.ACTIVE) {
      val basicUser = BasicUser.fromUser(user)
      for (id <- user.id) {
        idCache.set(UserIdKey(id), user)
        basicUserCache.set(BasicUserUserIdKey(id), basicUser)
        usernameCache.set(UsernameKey(user.username), user)
        UserProfileTab.all.foreach(v => userMetadataCache.remove(UserMetadataKey(id, v)))
      }
      externalIdCache.set(UserExternalIdKey(user.externalId), user)
      session.onTransactionSuccess {
        invalidateMixpanel(user)
      }
    } else {
      deleteCache(user)
    }
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
      getCompiled(id).firstOption.getOrElse(throw NotFoundException(id))
    }
  }

  def getNoCache(id: Id[User])(implicit session: RSession): User = {
    getCompiled(id).firstOption.getOrElse(throw NotFoundException(id))
  }

  override def getOpt(id: ExternalId[User])(implicit session: RSession): Option[User] = {
    externalIdCache.getOrElseOpt(UserExternalIdKey(id)) {
      getByExtIdCompiled(id).firstOption
    }
  }

  def getAllIds()(implicit session: RSession): Set[Id[User]] = { //Note: Need to revisit when we have >50k users.
    (for (row <- rows) yield row.id).list.toSet
  }

  def getAllActiveIds()(implicit session: RSession): Seq[Id[User]] = {
    (for (f <- rows if f.state === UserStates.ACTIVE) yield f.id).list
  }

  def getUsersSince(seq: SequenceNumber[User], fetchSize: Int)(implicit session: RSession): Seq[User] = super.getBySequenceNumber(seq, fetchSize)

  def getUsers(ids: Seq[Id[User]])(implicit session: RSession): Map[Id[User], User] = {
    if (ids.isEmpty) Map.empty[Id[User], User]
    else {
      val valueMap = idCache.bulkGetOrElse(ids.map(UserIdKey(_)).toSet) { keys =>
        val missing = keys.map(_.id)
        val users = (for (f <- rows if f.id.inSet(missing)) yield f).list
        users.collect {
          case u if u.state != UserStates.INACTIVE =>
            (UserIdKey(u.id.get) -> u)
        }.toMap
      }
      valueMap.map { case (k, v) => (k.id -> v) }
    }
  }

  def getAllUsers(ids: Seq[Id[User]])(implicit session: RSession): Map[Id[User], User] = {
    if (ids.isEmpty) {
      Map.empty
    } else if (ids.size == 1) {
      val id = ids.head
      Map(id -> get(id))
    } else {
      val valueMap = idCache.bulkGetOrElse(ids.map(UserIdKey(_)).toSet) { keys =>
        val missing = keys.map(_.id)
        val users = (for (f <- rows if f.id.inSet(missing)) yield f).list
        users.map { u => (UserIdKey(u.id.get) -> u) }.toMap
      }
      valueMap.map { case (k, v) => (k.id -> v) }
    }
  }

  def getAllUsersByExternalId(ids: Seq[ExternalId[User]])(implicit session: RSession): Map[ExternalId[User], User] = {
    if (ids.isEmpty) {
      Map.empty
    } else if (ids.size == 1) {
      val id = ids.head
      Map(id -> get(id))
    } else {
      val valueMap = externalIdCache.bulkGetOrElse(ids.map(UserExternalIdKey(_)).toSet) { keys =>
        val missing = keys.map(_.externalId)
        val users = (for (f <- rows if f.externalId.inSet(missing)) yield f).list
        users.map { u => (UserExternalIdKey(u.externalId) -> u) }.toMap
      }
      valueMap.map { case (k, v) => (k.externalId -> v) }
    }
  }

  def getByUsername(username: Username)(implicit session: RSession): Option[User] = {
    val normalizedUsername = UsernameOps.normalize(username.value)
    usernameCache.getOrElseOpt(UsernameKey(username)) {
      getByNormalizedUsernameCompiled(normalizedUsername).firstOption
    }
  }

  private val getByNormalizedUsernameCompiled = Compiled { normalizedUsername: Column[String] =>
    for (f <- rows if f.normalizedUsername is normalizedUsername) yield f
  }

  def getRecentActiveUsers(since: DateTime)(implicit session: RSession): Seq[Id[User]] = {
    sql"select id from user u where state = 'active' and created_at > $since and not exists (select id from user_experiment x where u.id = x.user_id and x.experiment_type='fake')".as[Id[User]].list
  }
}

trait UserSeqPlugin extends SequencingPlugin

class UserSeqPluginImpl @Inject() (override val actor: ActorInstance[UserSeqActor], override val scheduling: SchedulingProperties)
  extends UserSeqPlugin

@Singleton
class UserSeqAssigner @Inject() (db: Database, repo: UserRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[User](db, repo, airbrake)

class UserSeqActor @Inject() (assigner: UserSeqAssigner, airbrake: AirbrakeNotifier)
  extends SequencingActor(assigner, airbrake)
