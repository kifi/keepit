package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ SequenceNumber, State, DbSequenceAssigner, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.time._
import securesocial.core.SocialUser
import org.joda.time.DateTime
import com.keepit.social.{ SocialNetworks, SocialNetworkType, SocialId }
import scala.reflect.ClassTag
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.util.Try

@ImplementedBy(classOf[SocialUserInfoRepoImpl])
trait SocialUserInfoRepo extends Repo[SocialUserInfo] with RepoWithDelete[SocialUserInfo] with SeqNumberFunction[SocialUserInfo] {
  def getByUser(id: Id[User])(implicit session: RSession): Seq[SocialUserInfo]
  def getByUsers(userIds: Seq[Id[User]])(implicit session: RSession): Seq[SocialUserInfo]
  def doNotUseSave(socialUserInfo: SocialUserInfo)(implicit session: RWSession): SocialUserInfo
  def getNotAuthorizedByUser(userId: Id[User])(implicit session: RSession): Seq[SocialUserInfo]
  def getSocialUserByUser(id: Id[User])(implicit session: RSession): Seq[SocialUser]
  def get(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): SocialUserInfo
  def getBySocialIds(ids: Seq[SocialId])(implicit session: RSession): Seq[SocialUserInfo]
  def getUnprocessed()(implicit session: RSession): Seq[SocialUserInfo]
  def getNeedToBeRefreshed()(implicit session: RSession): Seq[SocialUserInfo]
  def getOpt(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): Option[SocialUserInfo]
  def getSocialUserOpt(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): Option[SocialUser]
  def getSocialUserBasicInfos(ids: Seq[Id[SocialUserInfo]])(implicit session: RSession): Map[Id[SocialUserInfo], SocialUserBasicInfo]
  def getSocialUserBasicInfosByUser(userId: Id[User])(implicit session: RSession): Seq[SocialUserBasicInfo]
  def getAllUsersToRefresh()(implicit session: RSession): Seq[SocialUserInfo]
}

@Singleton
class SocialUserInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val userCache: SocialUserInfoUserCache,
  val socialUserCache: SocialUserCache,
  val countCache: SocialUserInfoCountCache,
  val networkCache: SocialUserInfoNetworkCache,
  val basicInfoCache: SocialUserBasicInfoCache,
  val socialUserNetworkCache: SocialUserNetworkCache,
  override protected val changeListener: Option[RepoModification.Listener[SocialUserInfo]])
    extends DbRepo[SocialUserInfo] with DbRepoWithDelete[SocialUserInfo] with SeqNumberDbFunction[SocialUserInfo] with SocialUserInfoRepo {

  import db.Driver.simple._

  type RepoImpl = SocialUserInfoTable
  class SocialUserInfoTable(tag: Tag) extends RepoTable[SocialUserInfo](db, tag, "social_user_info") with SeqNumberColumn[SocialUserInfo] {
    def userId = column[Id[User]]("user_id", O.Nullable)
    def fullName = column[String]("full_name", O.NotNull)
    def socialId = column[SocialId]("social_id", O.NotNull)
    def socialHash = column[Long]("social_hash", O.Nullable)
    def networkType = column[SocialNetworkType]("network_type", O.NotNull)
    def credentials = column[SocialUser]("credentials", O.Nullable)
    def lastGraphRefresh = column[DateTime]("last_graph_refresh", O.Nullable)
    def pictureUrl = column[String]("picture_url", O.Nullable)
    def profileUrl = column[String]("profile_url", O.Nullable)
    def * = (id.?, createdAt, updatedAt, userId.?, fullName, pictureUrl.?, profileUrl.?, state, socialId,
      networkType, credentials.?, lastGraphRefresh.?, seq, socialHash.?) <> ((applyFromDbRow _).tupled, unapplyToDbRow _)
  }

  private def applyFromDbRow(id: Option[Id[SocialUserInfo]], createdAt: DateTime, updatedAt: DateTime,
    userId: Option[Id[User]], fullName: String, pictureUrl: Option[String],
    profileUrl: Option[String], state: State[SocialUserInfo], socialId: SocialId,
    networkType: SocialNetworkType, credentials: Option[SocialUser],
    lastGraphRefresh: Option[DateTime], seq: SequenceNumber[SocialUserInfo], socialHash: Option[Long]) = {
    SocialUserInfo(id, createdAt, updatedAt, userId, fullName, pictureUrl, profileUrl, state, socialId,
      networkType, credentials, lastGraphRefresh, seq)
  }

  private def unapplyToDbRow(s: SocialUserInfo) = {
    Some((s.id, s.createdAt, s.updatedAt, s.userId, s.fullName, s.pictureUrl, s.profileUrl, s.state, s.socialId,
      s.networkType, s.credentials, s.lastGraphRefresh, s.seq, Option(socialIdToSocialHash(s.socialId))))
  }

  def table(tag: Tag) = new SocialUserInfoTable(tag)
  initTable()

  private val UNPROCESSED_STATES = SocialUserInfoStates.CREATED :: SocialUserInfoStates.FETCHED_USING_FRIEND :: Nil
  private val REFRESHING_STATES = SocialUserInfoStates.FETCHED_USING_SELF :: SocialUserInfoStates.FETCH_FAIL :: Nil
  private val REFRESH_FREQUENCY = 14

  override def save(socialUserInfo: SocialUserInfo)(implicit session: RWSession): SocialUserInfo = {
    val toSave = socialUserInfo.copy(seq = deferredSeqNum())
    super.save(toSave)
  }

  def doNotUseSave(socialUserInfo: SocialUserInfo)(implicit session: RWSession): SocialUserInfo = {
    super.save(socialUserInfo)
  }

  private def socialIdToSocialHash(socialId: SocialId) = {
    Try(socialId.id.toLong).getOrElse(socialId.hashCode.toLong)
  }

  override def invalidateCache(socialUser: SocialUserInfo)(implicit session: RSession) = deleteCache(socialUser)

  override def deleteCache(socialUser: SocialUserInfo)(implicit session: RSession): Unit = {
    socialUser.userId map { userId =>
      userCache.remove(SocialUserInfoUserKey(userId))
      socialUserCache.remove(SocialUserKey(userId))
    }
    networkCache.remove(SocialUserInfoNetworkKey(socialUser.networkType, socialUser.socialId))
    socialUser.id map { id =>
      basicInfoCache.remove(SocialUserBasicInfoKey(id))
    }
    socialUserNetworkCache.remove(SocialUserNetworkKey(socialUser.networkType, socialUser.socialId))
    countCache.remove(SocialUserInfoCountKey())
  }

  override def count(implicit session: RSession): Int = {
    countCache.getOrElse(SocialUserInfoCountKey()) {
      super.count
    }
  }

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[SocialUserInfo] =
    userCache.getOrElse(SocialUserInfoUserKey(userId)) {
      (for (f <- rows if f.userId === userId && f.state =!= SocialUserInfoStates.INACTIVE) yield f).list
    }

  def getByUsers(ids: Seq[Id[User]])(implicit session: RSession): Seq[SocialUserInfo] = {
    (for (f <- rows if f.userId.inSet(ids)) yield f).list
  }

  def getBySocialIds(socialIds: Seq[SocialId])(implicit session: RSession): Seq[SocialUserInfo] = {
    (for (f <- rows if f.socialId.inSet(socialIds)) yield f).list
  }

  def getNotAuthorizedByUser(userId: Id[User])(implicit session: RSession): Seq[SocialUserInfo] =
    (for (f <- rows if f.userId === userId && f.state === SocialUserInfoStates.APP_NOT_AUTHORIZED) yield f).list

  def getSocialUserByUser(userId: Id[User])(implicit session: RSession): Seq[SocialUser] =
    socialUserCache.getOrElse(SocialUserKey(userId)) {
      (for (f <- rows if f.userId === userId && f.state =!= SocialUserInfoStates.INACTIVE) yield f).list.map(_.credentials).flatten.toSeq
    }

  def get(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): SocialUserInfo = try {
    networkCache.getOrElse(SocialUserInfoNetworkKey(networkType, id)) {
      val hashed = socialIdToSocialHash(id)
      (for (f <- rows if f.socialHash === hashed && f.socialId === id && f.networkType === networkType && f.state =!= SocialUserInfoStates.INACTIVE) yield f).first
    }
  } catch {
    case e: Throwable => throw new Exception(s"Can't get social user info for social id [$id] on network [$networkType]", e)
  }

  def getAllUsersToRefresh()(implicit session: RSession): Seq[SocialUserInfo] = {
    (for (
      f <- rows if f.userId.isNotNull && f.credentials.isNotNull
        && f.networkType.inSet(SocialNetworks.REFRESHING) && f.state.inSet(REFRESHING_STATES)
    ) yield f).list
  }

  def getUnprocessed()(implicit session: RSession): Seq[SocialUserInfo] = {
    (for (
      f <- rows if f.state.inSet(UNPROCESSED_STATES) &&
        f.userId.isNotNull && f.credentials.isNotNull &&
        f.networkType.inSet(SocialNetworks.REFRESHING) &&
        f.createdAt < clock.now.minusMinutes(15)
    ) yield f).list
  }

  def getNeedToBeRefreshed()(implicit session: RSession): Seq[SocialUserInfo] = {
    (for (
      f <- rows if f.userId.isNotNull && f.credentials.isNotNull
        && f.networkType.inSet(SocialNetworks.REFRESHING) && f.state.inSet(REFRESHING_STATES)
        && (f.lastGraphRefresh.isNull || f.lastGraphRefresh < clock.now.minusDays(REFRESH_FREQUENCY))
    ) yield f).list
  }

  def getOpt(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): Option[SocialUserInfo] =
    networkCache.getOrElseOpt(SocialUserInfoNetworkKey(networkType, id)) {
      val hashed = socialIdToSocialHash(id)
      (for (f <- rows if f.socialHash === hashed && f.socialId === id && f.networkType === networkType) yield f).firstOption
    }

  def getSocialUserOpt(id: SocialId, networkType: SocialNetworkType)(implicit session: RSession): Option[SocialUser] =
    socialUserNetworkCache.getOrElseOpt(SocialUserNetworkKey(networkType, id)) {
      val hashed = socialIdToSocialHash(id)
      (for (f <- rows if f.socialHash === hashed && f.socialId === id && f.networkType === networkType) yield f).firstOption.map(_.credentials).flatten
    }

  def getSocialUserBasicInfos(ids: Seq[Id[SocialUserInfo]])(implicit session: RSession): Map[Id[SocialUserInfo], SocialUserBasicInfo] = {
    if (ids.isEmpty) Map.empty[Id[SocialUserInfo], SocialUserBasicInfo]
    else {
      val valueMap = basicInfoCache.bulkGetOrElse(ids.map(SocialUserBasicInfoKey(_)).toSet) { keys =>
        val missing = keys.map(_.id)
        val suis = (for (f <- rows if f.id.inSet(missing)) yield f).list
        suis.collect {
          case sui if sui.state != SocialUserInfoStates.INACTIVE =>
            (SocialUserBasicInfoKey(sui.id.get) -> SocialUserBasicInfo.fromSocialUser(sui))
        }.toMap
      }
      valueMap.map { case (k, v) => (k.id -> v) }
    }
  }

  def getSocialUserBasicInfosByUser(userId: Id[User])(implicit session: RSession): Seq[SocialUserBasicInfo] = {
    val list = (for (f <- rows if f.userId === userId && f.state =!= SocialUserInfoStates.INACTIVE) yield f).list
    list.map(SocialUserBasicInfo.fromSocialUser(_))
  }
}

trait SocialUserInfoSequencingPlugin extends SequencingPlugin

class SocialUserInfoSequencingPluginImpl @Inject() (
  override val actor: ActorInstance[SocialUserInfoSequencingActor],
  override val scheduling: SchedulingProperties) extends SocialUserInfoSequencingPlugin

@Singleton
class SocialUserInfoSequenceNumberAssigner @Inject() (db: Database, repo: SocialUserInfoRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[SocialUserInfo](db, repo, airbrake)

class SocialUserInfoSequencingActor @Inject() (
  assigner: SocialUserInfoSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
