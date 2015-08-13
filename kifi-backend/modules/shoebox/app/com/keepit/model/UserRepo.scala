package com.keepit.model

import com.google.inject.{ Provider, Inject, Singleton, ImplementedBy }
import com.keepit.commanders.{ UserProfileTab, UserMetadataKey, UserMetadataCache, HandleOps }
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
import com.keepit.common.time._

@ImplementedBy(classOf[UserRepoImpl])
trait UserRepo extends Repo[User] with RepoWithDelete[User] with ExternalIdColumnFunction[User] with SeqNumberFunction[User] {
  def allExcluding(excludeStates: State[User]*)(implicit session: RSession): Seq[User]
  def allActiveTimes()(implicit session: RSession): Seq[DateTime]
  def pageIncluding(includeStates: State[User]*)(page: Int, size: Int)(implicit session: RSession): Seq[User]
  def pageIncludingWithExp(includeStates: State[User]*)(includeExp: UserExperimentType*)(page: Int, size: Int)(implicit session: RSession): Seq[User]
  def pageIncludingWithoutExp(includeStates: State[User]*)(excludeExp: UserExperimentType*)(page: Int, size: Int)(implicit session: RSession): Seq[User]
  def countIncluding(includeStates: State[User]*)(implicit session: RSession): Int
  def countIncludingWithExp(includeStates: State[User]*)(includeExp: UserExperimentType*)(implicit session: RSession): Int
  def countIncludingWithoutExp(includeStates: State[User]*)(excludeExp: UserExperimentType*)(implicit session: RSession): Int
  def countNewUsers(implicit session: RSession): Int
  def getNoCache(id: Id[User])(implicit session: RSession): User
  def getAllIds()(implicit session: RSession): Set[Id[User]] //Note: Need to revisit when we have >50k users.
  def getAllActiveIds()(implicit session: RSession): Seq[Id[User]]
  def getUsersSince(seq: SequenceNumber[User], fetchSize: Int)(implicit session: RSession): Seq[User]
  def getUsers(ids: Seq[Id[User]])(implicit session: RSession): Map[Id[User], User]
  def getAllUsers(ids: Seq[Id[User]])(implicit session: RSession): Map[Id[User], User]
  def getByExternalId(id: ExternalId[User])(implicit session: RSession): User
  def getAllUsersByExternalId(ids: Seq[ExternalId[User]])(implicit session: RSession): Map[ExternalId[User], User]
  def getByUsername(username: Username)(implicit session: RSession): Option[User]
  def getRecentActiveUsers(since: DateTime = currentDateTime.minusDays(1))(implicit session: RSession): Seq[Id[User]]
  def countUsersWithPotentialOrgs()(implicit session: RSession): Int
  def pageUsersWithPotentialOrgs(page: Int, size: Int)(implicit session: RSession): Seq[User]
  def countLinkedInUsersWithoutOrgs()(implicit session: RSession): Int
  def pageLinkedInUsersWithoutOrgs(page: Int, size: Int)(implicit session: RSession): Seq[User]
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
    def pictureName = column[Option[String]]("picture_name", O.Nullable)
    def userPictureId = column[Option[Id[UserPicture]]]("user_picture_id", O.Nullable)
    def username = column[Option[Username]]("username", O.Nullable)
    def normalizedUsername = column[Option[Username]]("normalized_username", O.Nullable)
    def * = (id.?, createdAt, updatedAt, externalId, firstName, lastName, state, pictureName, userPictureId, seq, username, normalizedUsername) <> ((User.applyFromDbRow _).tupled, User.unapplyToDbRow)
  }

  def table(tag: Tag) = new UserTable(tag)
  initTable()

  def allActiveTimes()(implicit session: RSession): Seq[DateTime] = {
    StaticQuery.queryNA[(DateTime)](s"""select created_at from user u where state = 'active' and not exists (select id from user_experiment x where u.id = x.user_id and x.experiment_type = 'fake');""").list
  }

  override def save(user: User)(implicit session: RWSession): User = {
    val toSave = user.copy(seq = deferredSeqNum())
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

  def pageIncludingWithExp(includeStates: State[User]*)(includeExp: UserExperimentType*)(page: Int = 0, size: Int = 20)(implicit session: RSession): Seq[User] = {
    val q = for {
      u <- rows if ((u.state inSet includeStates) &&
        (for { e <- expRepo.rows if e.userId === u.id && (e.experimentType inSet includeExp) && e.state === UserExperimentStates.ACTIVE } yield e).exists)
    } yield u
    q.sortBy(_.id desc).drop(page * size).take(size).list
  }

  def pageIncludingWithoutExp(includeStates: State[User]*)(excludeExp: UserExperimentType*)(page: Int = 0, size: Int = 20)(implicit session: RSession): Seq[User] = {
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

  def countIncludingWithExp(includeStates: State[User]*)(includeExp: UserExperimentType*)(implicit session: RSession): Int = {
    val q = for {
      u <- rows if ((u.state inSet includeStates) &&
        (for { e <- expRepo.rows if e.userId === u.id && (e.experimentType inSet includeExp) && e.state === UserExperimentStates.ACTIVE } yield e).exists)
    } yield u
    Query(q.length).first
  }

  def countIncludingWithoutExp(includeStates: State[User]*)(excludeExp: UserExperimentType*)(implicit session: RSession): Int = {
    val q = for {
      u <- rows if ((u.state inSet includeStates) &&
        !(for { e <- expRepo.rows if u.id === e.userId && (e.experimentType inSet excludeExp) && e.state === UserExperimentStates.ACTIVE } yield e).exists)
    } yield u
    Query(q.length).first
  }

  def countNewUsers(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"select count(distinct u.id) from kifi_installation ki, user u where ki.user_id = u.id".as[Int].first
  }

  override def deleteCache(user: User)(implicit session: RSession): Unit = {
    user.id foreach { id =>
      idCache.remove(UserIdKey(id))
      UserProfileTab.all.foreach(v => userMetadataCache.remove(UserMetadataKey(id, v)))
      externalIdCache.remove(UserExternalIdKey(user.externalId))
      user.primaryUsername.foreach { username =>
        usernameCache.remove(UsernameKey(username.original))
        basicUserCache.remove(BasicUserUserIdKey(id))
      }
    }
    invalidateMixpanel(user.withState(UserStates.INACTIVE))
  }

  override def invalidateCache(user: User)(implicit session: RSession) = {
    if (user.state == UserStates.ACTIVE) {
      for (id <- user.id) {
        idCache.set(UserIdKey(id), user)
        UserProfileTab.all.foreach(v => userMetadataCache.remove(UserMetadataKey(id, v)))
        user.primaryUsername.foreach { username =>
          usernameCache.set(UsernameKey(username.original), user)
          basicUserCache.set(BasicUserUserIdKey(id), BasicUser.fromUser(user))
        }
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

  def getByExternalId(id: ExternalId[User])(implicit session: RSession): User = {
    getAllUsersByExternalId(Seq(id)).values.head
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
    val normalizedUsername = Username(HandleOps.normalize(username.value))
    usernameCache.getOrElseOpt(UsernameKey(username)) {
      getByNormalizedUsernameCompiled(normalizedUsername).firstOption
    }
  }

  private val getByNormalizedUsernameCompiled = Compiled { normalizedUsername: Column[Username] =>
    for (f <- rows if f.normalizedUsername === normalizedUsername) yield f
  }

  def getRecentActiveUsers(since: DateTime)(implicit session: RSession): Seq[Id[User]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"select id from user u where state = 'active' and created_at > $since and not exists (select id from user_experiment x where u.id = x.user_id and x.experiment_type='fake')".as[Id[User]].list
  }

  def countUsersWithPotentialOrgs()(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""
      select count(distinct cur_user.id) from user as cur_user
      inner join (
        select user_2 as related_id, user_1 as cur_id from user_connection
        union all
          select user_1 as related_id, user_1 as cur_id from user_connection
      ) as relation
      on relation.cur_id = cur_user.id
      inner join user on user.id = cur_user.id
      where user.state = 'active'
      and not exists (
        select user_id, organization_id from organization_membership where organization_membership.user_id = cur_user.id
        union all
          select user_id, organization_id from organization_membership_candidate where organization_membership_candidate.user_id = cur_user.id
      ) and exists (
        select user_id, organization_id from organization_membership where organization_membership.user_id = relation.related_id
        union all
          select user_id, organization_id from organization_membership_candidate where organization_membership_candidate.user_id = relation.related_id
      ) and not exists (
        select user_id from user_value where name = 'ignore_for_potential_organizations' and value = 'true' and user_id = cur_user.id
      ) and not exists (
        select user_id from user_experiment where experiment_type = 'fake' and state = 'active' and user_id = cur_user.id
      ) and (select count(bookmark.user_id) from bookmark where bookmark.user_id = cur_user.id) >= 10;
      """.as[Int].first
  }

  def pageUsersWithPotentialOrgs(page: Int, size: Int)(implicit session: RSession): Seq[User] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val ids = sql"""
      select distinct cur_user.id from user as cur_user
      inner join (
        select user_2 as related_id, user_1 as cur_id from user_connection
        union all
          select user_1 as related_id, user_1 as cur_id from user_connection
      ) as relation
      on relation.cur_id = cur_user.id
      inner join user on user.id = cur_user.id
      where user.state = 'active'
      and not exists (
        select user_id, organization_id from organization_membership where organization_membership.user_id = cur_user.id
        union all
          select user_id, organization_id from organization_membership_candidate where organization_membership_candidate.user_id = cur_user.id
      ) and exists (
        select user_id, organization_id from organization_membership where organization_membership.user_id = relation.related_id
        union all
          select user_id, organization_id from organization_membership_candidate where organization_membership_candidate.user_id = relation.related_id
      ) and not exists (
        select user_id from user_value where name = 'ignore_for_potential_organizations' and value = 'true' and user_id = cur_user.id
      ) and not exists (
        select user_id from user_experiment where experiment_type = 'fake' and state = 'active' and user_id = cur_user.id
      ) and (select count(bookmark.user_id) from bookmark where bookmark.user_id = cur_user.id) >= 10
      limit $size offset ${size * page};
      """.as[Id[User]].list

    val userMaps = getUsers(ids)

    ids.map(id => userMaps(id))
  }

  def countLinkedInUsersWithoutOrgs()(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""
      select count(user.id) from social_user_info
        inner join user on user.id = social_user_info.user_id
        where user.state = 'active'
        and social_user_info.network_type = 'linkedin'
        and social_user_info.user_id is not null
        and social_user_info.user_id not in (select user_id from organization_membership_candidate )
        and social_user_info.user_id not in (select user_id from organization_membership)
        and not exists (
          select user_id from user_value where name = 'ignore_for_potential_organizations' and value = 'true' and user_id = social_user_info.user_id
        ) and not exists (
          select user_id from user_experiment where experiment_type = 'fake' and state = 'active' and user_id = social_user_info.user_id
        ) and (select count(bookmark.user_id) from bookmark where bookmark.user_id = social_user_info.user_id) >= 10;
      """.as[Int].first
  }

  def pageLinkedInUsersWithoutOrgs(page: Int, size: Int)(implicit session: RSession): Seq[User] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation

    val ids = sql"""
      select user.id from social_user_info
        inner join user on user.id = social_user_info.user_id
        where user.state = 'active'
        and social_user_info.network_type = 'linkedin'
        and social_user_info.user_id is not null
        and social_user_info.user_id not in (select user_id from organization_membership_candidate )
        and social_user_info.user_id not in (select user_id from organization_membership)
        and not exists (
          select user_id from user_value where name = 'ignore_for_potential_organizations' and value = 'true' and user_id = social_user_info.user_id
        ) and not exists (
          select user_id from user_experiment where experiment_type = 'fake' and state = 'active' and user_id = social_user_info.user_id
        ) and (select count(bookmark.user_id) from bookmark where bookmark.user_id = social_user_info.user_id) >= 10
        limit $size offset ${size * page};
      """.as[Id[User]].list

    val userMaps = getUsers(ids)

    ids.map(id => userMaps(id))
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
