package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.crypto._
import com.keepit.serializer.SocialUserSerializer
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import ru.circumflex.orm._
import securesocial.core.SocialUser
import play.api.libs.json._
import com.keepit.common.social.SocialNetworkType
import com.keepit.common.social.SocialNetworks
import com.keepit.common.social.SocialUserRawInfo
import com.keepit.common.social.SocialNetworks
import com.keepit.common.social.SocialId

case class SocialUserInfo(
  id: Option[Id[SocialUserInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Option[Id[User]] = None,
  fullName: String,
  state: State[SocialUserInfo] = SocialUserInfoStates.CREATED,
  socialId: SocialId,
  networkType: SocialNetworkType,
  credentials: Option[SocialUser] = None,
  lastGraphRefresh: Option[DateTime] = Some(currentDateTime)
) extends Model[SocialUserInfo] {
  def withId(id: Id[SocialUserInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def reset() = copy(state = SocialUserInfoStates.CREATED, credentials = None)
  def withUser(user: User) = copy(userId = Some(user.id.get))//want to make sure the user has an id, fail hard if not!
  def withCredentials(credentials: SocialUser) = copy(credentials = Some(credentials))//want to make sure the user has an id, fail hard if not!
  def withState(state: State[SocialUserInfo]) = copy(state = state)
  def withLastGraphRefresh(lastGraphRefresh : Option[DateTime] = Some(currentDateTime)) = copy(lastGraphRefresh = lastGraphRefresh)
  def save(implicit conn: Connection): SocialUserInfo = {
    val entity = SocialUserInfoEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
}

@ImplementedBy(classOf[SocialUserInfoRepoImpl])
trait SocialUserInfoRepo extends Repo[SocialUserInfo] {
  def getByUser(id: Id[User])(implicit session: RSession): Seq[SocialUserInfo]
  def get(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): SocialUserInfo
}

@Singleton
class SocialUserInfoRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[SocialUserInfo] with SocialUserInfoRepo {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[SocialUserInfo](db, "social_user_info") {
    def userId = column[Id[User]]("user_id", O.Nullable)
    def fullName = column[String]("full_name", O.NotNull)
    def socialId = column[SocialId]("social_id", O.NotNull)
    def networkType = column[SocialNetworkType]("network_type", O.NotNull)
    def credentials = column[SocialUser]("credentials", O.Nullable)
    def lastGraphRefresh = column[DateTime]("last_graph_refresh", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId.? ~ fullName ~ state ~ socialId ~ networkType ~ credentials.? ~ lastGraphRefresh.? <> (SocialUserInfo, SocialUserInfo.unapply _)
  }

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[SocialUserInfo] =
    (for(f <- table if f.userId === userId) yield f).list

  def get(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): SocialUserInfo =
    (for(f <- table if f.socialId === id && f.networkType === networkType) yield f).first

}

object SocialUserInfoCxRepo {

  def all(implicit conn: Connection): Seq[SocialUserInfo] =
    SocialUserInfoEntity.all.map(_.view)

  def get(id: Id[SocialUserInfo])(implicit conn: Connection): SocialUserInfo =
    getOpt(id).getOrElse(throw NotFoundException(id))

  def get(id: SocialId, networkType: SocialNetworkType)(implicit conn: Connection): SocialUserInfo =
    getOpt(id, networkType).getOrElse(throw new Exception("not found %s:%s".format(id, networkType)))

  def getByUser(userId: Id[User])(implicit conn: Connection): Seq[SocialUserInfo] =
    (SocialUserInfoEntity AS "u").map { u => SELECT (u.*) FROM u WHERE (u.userId EQ userId) list }.map(_.view)

  def getOpt(id: SocialId, networkType: SocialNetworkType)(implicit conn: Connection): Option[SocialUserInfo] =
    (SocialUserInfoEntity AS "u").map { u => SELECT (u.*) FROM u WHERE ((u.socialId EQ id.id) AND (u.networkType EQ networkType.name)) unique }.map(_.view)

  def getOpt(id: Id[SocialUserInfo])(implicit conn: Connection): Option[SocialUserInfo] =
    SocialUserInfoEntity.get(id).map(_.view)

  def page(page: Int = 0, size: Int = 20)(implicit conn: Connection): Seq[SocialUserInfo] =
    (SocialUserInfoEntity AS "u").map { u => SELECT (u.*) FROM u ORDER_BY (u.id DESC) OFFSET (page * size) LIMIT size list }.map(_.view)

  def count(implicit conn: Connection): Long =
    (SocialUserInfoEntity AS "u").map(u => SELECT(COUNT(u.id)).FROM(u).unique).get

  def getUnprocessed()(implicit conn: Connection): Seq[SocialUserInfo] = {
    val UNPROCESSED_STATE = SocialUserInfoStates.CREATED :: SocialUserInfoStates.FETCHED_USING_FRIEND :: Nil
    (SocialUserInfoEntity AS "u").map { u => SELECT (u.*) FROM u WHERE ((u.state IN(UNPROCESSED_STATE)) AND (u.credentials IS_NOT_NULL)) list }.map(_.view)
  }

  def getNeedToBeRefreshed()(implicit conn: Connection): Seq[SocialUserInfo] = {
    (SocialUserInfoEntity AS "u").map { u => SELECT (u.*) FROM u WHERE ( ((u.lastGraphRefresh IS_NULL) OR u.lastGraphRefresh.LT(new DateTime().minusMinutes(5)) )  AND (u.userId IS_NOT_NULL) AND (u.credentials IS_NOT_NULL ) ) list }.map(_.view)
  }
}

object SocialUserInfoStates {
  val CREATED = State[SocialUserInfo]("created")
  val FETCHED_USING_FRIEND = State[SocialUserInfo]("fetched_using_friend")
  val FETCHED_USING_SELF = State[SocialUserInfo]("fetched_using_self")
  val FETCHE_FAIL = State[SocialUserInfo]("fetch_fail")
  val INACTIVE = State[SocialUserInfo]("inactive")
}

private[model] class SocialUserInfoEntity extends Entity[SocialUserInfo, SocialUserInfoEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val userId = "user_id".ID[User]
  val fullName = "full_name".VARCHAR(512).NOT_NULL
  val state = "state".STATE[SocialUserInfo].NOT_NULL(SocialUserInfoStates.CREATED)
  val socialId = "social_id".VARCHAR(32).NOT_NULL
  val networkType = "network_type".VARCHAR(32).NOT_NULL
  val credentials = "credentials".VARCHAR(2048)
  val lastGraphRefresh = "last_graph_refresh".JODA_TIMESTAMP

  def relation = SocialUserInfoEntity

  def view(implicit conn: Connection): SocialUserInfo = SocialUserInfo(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    fullName = fullName(),
    state = state(),
    userId = userId.value,
    socialId = SocialId(socialId()),
    networkType = networkType() match {
      case SocialNetworks.FACEBOOK.name => SocialNetworks.FACEBOOK
      case _ => throw new RuntimeException("unknown network type %s".format(networkType()))
    },
    credentials = credentials.map{ s => new SocialUserSerializer().reads(Json.parse(s)) },
    lastGraphRefresh = lastGraphRefresh.value
  )
}

private[model] object SocialUserInfoEntity extends SocialUserInfoEntity with EntityTable[SocialUserInfo, SocialUserInfoEntity] {
  override def relationName = "social_user_info"

  def apply(view: SocialUserInfo): SocialUserInfoEntity = {
    val user = new SocialUserInfoEntity
    user.id.set(view.id)
    user.createdAt := view.createdAt
    user.updatedAt := view.updatedAt
    user.userId.set(view.userId)
    user.fullName := view.fullName
    user.state := view.state
    user.socialId := view.socialId.id
    user.networkType := view.networkType.name
    user.credentials.set(view.credentials.map{ s => new SocialUserSerializer().writes(s).toString() })
    user.lastGraphRefresh.set(view.lastGraphRefresh)
    user
  }
}


