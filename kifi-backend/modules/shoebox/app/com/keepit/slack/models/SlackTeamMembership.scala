package com.keepit.slack.models

import javax.crypto.spec.IvParameterSpec

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.crypto.Aes64BitCipher
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.json.EnumFormat
import com.keepit.common.core.anyExtensionOps
import com.keepit.common.oauth.SlackIdentity
import com.keepit.common.performance.StatsdTiming
import com.keepit.common.reflection.Enumerator
import com.keepit.common.time._
import com.keepit.export.FullExportRequestRepo
import com.keepit.slack.SlackActionFail
import com.keepit.model.User
import com.keepit.social.{ IdentityUserIdCache, IdentityUserIdKey, UserIdentity }
import org.joda.time.{ DateTime, Duration }
import play.api.libs.json.{ Format, JsValue, Json }

case class SlackTeamMembershipInternRequest(
  userId: Option[Id[User]],
  slackUserId: SlackUserId,
  slackTeamId: SlackTeamId,
  tokenWithScopes: Option[SlackTokenWithScopes],
  slackUser: Option[SlackUserInfo])

case class InvalidSlackAccountOwnerException(requestingUserId: Id[User], membership: SlackTeamMembership)
  extends Exception(s"Slack account ${membership.slackUserId} in team ${membership.slackTeamId.value} already belongs to Kifi user ${membership.userId}")

sealed abstract class SlackAccountKind(val kind: String)
object SlackAccountKind {
  case object User extends SlackAccountKind("user")
  case object Bot extends SlackAccountKind("bot")

  private val all: Set[SlackAccountKind] = Set(User, Bot)
  private def fromString(kind: String): Option[SlackAccountKind] = all.find(_.kind equalsIgnoreCase kind)
  def apply(kind: String): SlackAccountKind = fromString(kind) getOrElse { throw new IllegalArgumentException(s"Unknown SlackTeamMembershipKind: $kind") }
}

object SlackTeamMembership {
  private val ivSpec = new IvParameterSpec(Array(47, 64, 35, 93, -9, -110, 78, 70, -113, 109, 41, -76, -89, -95, 59, -51))
  private val cryptoPrefix = "stm_crypt_"
  private val cipher = Aes64BitCipher("asdf", ivSpec)
  private val regex = """^([^ ]+)_([^ ]+)$""".r
  def encodeTeamAndUser(slackTeamId: SlackTeamId, slackUserId: SlackUserId): String = {
    cryptoPrefix + cipher.encrypt(s"${slackTeamId.value}_${slackUserId.value}")
  }
  def decodeTeamAndUser(hash: String): Option[(SlackTeamId, SlackUserId)] = {
    cipher.decrypt(hash.stripPrefix(cryptoPrefix)) match {
      case regex(teamId, userId) => Some((SlackTeamId(teamId), SlackUserId(userId)))
      case _ => None
    }
  }
}

case class SlackTeamMembership(
  id: Option[Id[SlackTeamMembership]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackTeamMembership] = SlackTeamMembershipStates.ACTIVE,
  seq: SequenceNumber[SlackTeamMembership] = SequenceNumber.ZERO,
  userId: Option[Id[User]],
  slackUserId: SlackUserId,
  slackUsername: Option[SlackUsername],
  slackTeamId: SlackTeamId,
  kind: SlackAccountKind,
  tokenWithScopes: Option[SlackTokenWithScopes],
  slackUser: Option[SlackUserInfo],
  privateChannelsLastSyncedAt: Option[DateTime] = None,
  // Personal digest scheduling nonsense
  lastPersonalDigestAt: Option[DateTime] = None,
  lastProcessingAt: Option[DateTime] = None,
  lastProcessedAt: Option[DateTime] = None,
  personalDigestSetting: SlackPersonalDigestSetting = SlackPersonalDigestSetting.Defer,
  nextPersonalDigestAt: DateTime = currentDateTime, // only consider sending a personal digest after this time ("availableForPersonalDigestsAfter")
  lastIngestedMessageTimestamp: Option[SlackTimestamp] = None)
    extends ModelWithState[SlackTeamMembership] with ModelWithSeqNumber[SlackTeamMembership] {
  def withId(id: Id[SlackTeamMembership]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackTeamMembershipStates.ACTIVE
  def isBot: Boolean = kind == SlackAccountKind.Bot
  def scopes: Set[SlackAuthScope] = tokenWithScopes.map(_.scopes) getOrElse Set.empty
  def token: Option[SlackUserAccessToken] = tokenWithScopes.map(_.token)
  def getTokenIncludingScopes(requiredScopes: Set[SlackAuthScope]): Option[SlackUserAccessToken] = tokenWithScopes.collect {
    case SlackTokenWithScopes(token, scopes) if requiredScopes subsetOf scopes => token
  }
  def unnotifiedSince: DateTime = lastPersonalDigestAt getOrElse createdAt

  def withIngestedMessage(msg: SlackMessage) = {
    require(this.slackUserId == msg.userId)
    this.copy(
      slackUsername = if (slackUsername.isDefined && lastIngestedMessageTimestamp.exists(_ >= msg.timestamp)) slackUsername else Some(msg.username),
      lastIngestedMessageTimestamp = Some(lastIngestedMessageTimestamp.filter(_ >= msg.timestamp).getOrElse(msg.timestamp))
    )
  }

  def withPrivateChannelsSyncedAt(time: DateTime) = privateChannelsLastSyncedAt match {
    case Some(lastSync) if lastSync isAfter time => this
    case _ => this.copy(privateChannelsLastSyncedAt = Some(time))
  }

  // These modify the personal digest setting, so they should be initiated by user actions
  def withNoNextPersonalDigest = this.copy(personalDigestSetting = SlackPersonalDigestSetting.Off)
  def withNextPersonalDigestAt(time: DateTime) = this.copy(personalDigestSetting = SlackPersonalDigestSetting.On, nextPersonalDigestAt = time)
  // This just schedules the digest, it doesn't guarantee that it will happen
  def scheduledForDigestAtLatest(time: DateTime) = this.copy(nextPersonalDigestAt = nextPersonalDigestAt min time)

  def revoked = this.copy(tokenWithScopes = None)
  def sanitizeForDelete = this.copy(userId = None, tokenWithScopes = None, state = SlackTeamMembershipStates.INACTIVE)
}

sealed abstract class SlackPersonalDigestSetting(val value: String)
object SlackPersonalDigestSetting extends Enumerator[SlackPersonalDigestSetting] {
  case object On extends SlackPersonalDigestSetting("on")
  case object Off extends SlackPersonalDigestSetting("off")
  case object Defer extends SlackPersonalDigestSetting("defer")

  val all = _all.toSet
  def fromStr(str: String) = all.find(_.value == str)
  implicit val format: Format[SlackPersonalDigestSetting] = EnumFormat.format(fromStr, _.value, domain = all.map(_.value))
}
object SlackTeamMembershipStates extends States[SlackTeamMembership]

@ImplementedBy(classOf[SlackTeamMembershipRepoImpl])
trait SlackTeamMembershipRepo extends Repo[SlackTeamMembership] with SeqNumberFunction[SlackTeamMembership] {
  def getBySlackTeam(slackTeamId: SlackTeamId, excludeKind: Option[SlackAccountKind] = Some(SlackAccountKind.Bot))(implicit session: RSession): Set[SlackTeamMembership]
  def getBySlackTeamAndUser(slackTeamId: SlackTeamId, slackUserId: SlackUserId, excludeState: Option[State[SlackTeamMembership]] = Some(SlackTeamMembershipStates.INACTIVE))(implicit session: RSession): Option[SlackTeamMembership]
  def getBySlackTeamAndUsername(slackTeamId: SlackTeamId, slackUsername: SlackUsername, excludeState: Option[State[SlackTeamMembership]] = Some(SlackTeamMembershipStates.INACTIVE))(implicit session: RSession): Option[SlackTeamMembership]

  def internMembership(request: SlackTeamMembershipInternRequest)(implicit session: RWSession): (SlackTeamMembership, Boolean)
  def internWithMessage(slackTeam: SlackTeam, message: SlackMessage)(implicit session: RWSession): (SlackTeamMembership, Boolean)
  def getBySlackIdentities(identities: Set[(SlackTeamId, SlackUserId)])(implicit session: RSession): Map[(SlackTeamId, SlackUserId), SlackTeamMembership]
  def getByToken(token: SlackUserAccessToken)(implicit session: RSession): Option[SlackTeamMembership]
  def getByUserId(userId: Id[User])(implicit session: RSession): Seq[SlackTeamMembership]
  def getByUserIdAndSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit session: RSession): Option[SlackTeamMembership]

  def getRipeForPersonalDigest(limit: Int, overrideProcessesOlderThan: DateTime, now: DateTime)(implicit session: RSession): Seq[Id[SlackTeamMembership]]
  def markAsProcessingPersonalDigest(id: Id[SlackTeamMembership], overrideProcessesOlderThan: DateTime)(implicit session: RWSession): Boolean
  def updateLastPersonalDigest(id: Id[SlackTeamMembership])(implicit session: RWSession): Unit
  def finishProcessing(id: Id[SlackTeamMembership], delayUntilNextPush: Duration)(implicit session: RWSession): Unit
  def deactivate(model: SlackTeamMembership)(implicit session: RWSession): Unit

  //admin
  def getAllByIds(ids: Set[Id[SlackTeamMembership]])(implicit session: RSession): Set[SlackTeamMembership]
  def getByUserIdsAndSlackTeams(keys: Map[Id[User], SlackTeamId])(implicit session: RSession): Seq[SlackTeamMembership]
  def getMembershipsOfKifiUsersWhoHaventExported(fromId: Option[Id[SlackTeamMembership]])(implicit session: RSession): Seq[SlackTeamMembership]
}

@Singleton
class SlackTeamMembershipRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    slackTeamRepo: SlackTeamRepo, // implicit dependency based in manual SQL query
    exportRequestRepo: FullExportRequestRepo,
    userIdentityCache: IdentityUserIdCache) extends DbRepo[SlackTeamMembership] with SeqNumberDbFunction[SlackTeamMembership] with SlackTeamMembershipRepo {
  // Don't put a cache on the whole model. There's a bunch of
  // scheduling garbage that is changed via update statements.

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val slackUsernameColumnType = SlackDbColumnTypes.username(db)
  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackTeamNameColumnType = SlackDbColumnTypes.teamName(db)
  implicit val slackTeamMembershipKindType = MappedColumnType.base[SlackAccountKind, String](_.kind, SlackAccountKind(_))
  implicit val tokenColumnType = MappedColumnType.base[SlackUserAccessToken, String](_.token, SlackUserAccessToken(_))
  implicit val scopesFormat = SlackAuthScope.dbFormat
  implicit val slackMessageTimestampColumnType = SlackDbColumnTypes.timestamp(db)
  implicit val personalDigestSettingMapper = MappedColumnType.base[SlackPersonalDigestSetting, String](_.value, str => SlackPersonalDigestSetting.fromStr(str).get)

  private def membershipFromDbRow(
    id: Option[Id[SlackTeamMembership]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[SlackTeamMembership],
    seq: SequenceNumber[SlackTeamMembership],
    userId: Option[Id[User]],
    slackUserId: SlackUserId,
    slackUsername: Option[SlackUsername],
    slackTeamId: SlackTeamId,
    kind: SlackAccountKind,
    tokenOpt: Option[SlackUserAccessToken],
    scopes: JsValue,
    slackUser: Option[JsValue],
    privateChannelsLastSyncedAt: Option[DateTime],
    lastPersonalDigestAt: Option[DateTime],
    lastProcessingAt: Option[DateTime],
    lastProcessedAt: Option[DateTime],
    personalDigestSetting: SlackPersonalDigestSetting,
    nextPersonalDigestAt: DateTime,
    lastIngestedMessageTimestamp: Option[SlackTimestamp]) = {
    SlackTeamMembership(
      id,
      createdAt,
      updatedAt,
      state,
      seq,
      userId,
      slackUserId,
      slackUsername,
      slackTeamId,
      kind,
      tokenOpt.map(token => SlackTokenWithScopes(token, scopes.as[Set[SlackAuthScope]])),
      slackUser.map(_.as[SlackUserInfo]),
      privateChannelsLastSyncedAt,
      lastPersonalDigestAt = lastPersonalDigestAt,
      lastProcessingAt = lastProcessingAt,
      lastProcessedAt = lastProcessedAt,
      personalDigestSetting = personalDigestSetting,
      nextPersonalDigestAt = nextPersonalDigestAt,
      lastIngestedMessageTimestamp = lastIngestedMessageTimestamp
    )
  }

  private def membershipToDbRow(membership: SlackTeamMembership) = Some((
    membership.id,
    membership.createdAt,
    membership.updatedAt,
    membership.state,
    membership.seq,
    membership.userId,
    membership.slackUserId,
    membership.slackUsername,
    membership.slackTeamId,
    membership.kind,
    membership.tokenWithScopes.map(_.token),
    Json.toJson(membership.tokenWithScopes.map(_.scopes).getOrElse(Set.empty)),
    membership.slackUser.map(Json.toJson(_)),
    membership.privateChannelsLastSyncedAt,
    membership.lastPersonalDigestAt,
    membership.lastProcessingAt,
    membership.lastProcessedAt,
    membership.personalDigestSetting,
    membership.nextPersonalDigestAt,
    membership.lastIngestedMessageTimestamp
  ))

  type RepoImpl = SlackTeamMembershipTable

  class SlackTeamMembershipTable(tag: Tag) extends RepoTable[SlackTeamMembership](db, tag, "slack_team_membership") with SeqNumberColumn[SlackTeamMembership] {
    def userId = column[Option[Id[User]]]("user_id", O.Nullable)
    def slackUserId = column[SlackUserId]("slack_user_id", O.NotNull)
    def slackUsername = column[Option[SlackUsername]]("slack_username", O.Nullable)
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def kind = column[SlackAccountKind]("kind", O.NotNull)
    def token = column[Option[SlackUserAccessToken]]("token", O.Nullable)
    def scopes = column[JsValue]("scopes", O.NotNull)
    def slackUser = column[Option[JsValue]]("slack_user", O.Nullable)
    def privateChannelsLastSyncedAt = column[Option[DateTime]]("private_channels_last_synced_at", O.Nullable)
    def lastPersonalDigestAt = column[Option[DateTime]]("last_personal_digest_at", O.Nullable)
    def lastProcessingAt = column[Option[DateTime]]("last_processing_at", O.Nullable)
    def lastProcessedAt = column[Option[DateTime]]("last_processed_at", O.Nullable)
    def personalDigestSetting = column[SlackPersonalDigestSetting]("personal_digest_setting", O.NotNull)
    def nextPersonalDigestAt = column[DateTime]("next_personal_digest_at", O.NotNull)
    def lastIngestedMessageTimestamp = column[Option[SlackTimestamp]]("last_ingested_message_timestamp", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, seq, userId, slackUserId, slackUsername, slackTeamId, kind, token, scopes, slackUser, privateChannelsLastSyncedAt, lastPersonalDigestAt, lastProcessingAt, lastProcessedAt, personalDigestSetting, nextPersonalDigestAt, lastIngestedMessageTimestamp) <> ((membershipFromDbRow _).tupled, membershipToDbRow _)

    def availableForProcessing(overrideDate: DateTime) = lastProcessingAt.isEmpty || lastProcessingAt < overrideDate
  }

  private def activeRows = rows.filter(row => row.state === SlackTeamMembershipStates.ACTIVE)
  def table(tag: Tag) = new SlackTeamMembershipTable(tag)
  initTable()

  override def save(membership: SlackTeamMembership)(implicit session: RWSession): SlackTeamMembership = {
    super.save(membership.copy(seq = sequence.incrementAndGet()))
  }

  override def deleteCache(membership: SlackTeamMembership)(implicit session: RSession): Unit = {
    userIdentityCache.remove(IdentityUserIdKey(membership.slackTeamId, membership.slackUserId))
  }

  override def invalidateCache(membership: SlackTeamMembership)(implicit session: RSession): Unit = deleteCache(membership)

  def getBySlackTeam(slackTeamId: SlackTeamId, excludeKind: Option[SlackAccountKind] = Some(SlackAccountKind.Bot))(implicit session: RSession): Set[SlackTeamMembership] = {
    activeRows.filter(row => row.slackTeamId === slackTeamId && row.kind =!= excludeKind.orNull).list.toSet
  }
  def getBySlackTeamAndUser(slackTeamId: SlackTeamId, slackUserId: SlackUserId, excludeState: Option[State[SlackTeamMembership]] = Some(SlackTeamMembershipStates.INACTIVE))(implicit session: RSession): Option[SlackTeamMembership] = {
    rows.filter(row => row.slackTeamId === slackTeamId && row.slackUserId === slackUserId && row.state =!= excludeState.orNull).firstOption
  }

  def getBySlackTeamAndUsername(slackTeamId: SlackTeamId, slackUsername: SlackUsername, excludeState: Option[State[SlackTeamMembership]] = Some(SlackTeamMembershipStates.INACTIVE))(implicit session: RSession): Option[SlackTeamMembership] = {
    rows.filter(row => row.slackTeamId === slackTeamId && row.slackUsername === slackUsername && row.state =!= excludeState.orNull).firstOption
  }

  def internMembership(request: SlackTeamMembershipInternRequest)(implicit session: RWSession): (SlackTeamMembership, Boolean) = {
    getBySlackTeamAndUser(request.slackTeamId, request.slackUserId, excludeState = None) match {
      case Some(membership) if membership.isActive =>
        membership.userId.foreach { ownerId =>
          request.userId.foreach { requesterId =>
            if (requesterId != ownerId) throw SlackActionFail.MembershipAlreadyConnected(requesterId, ownerId, request.slackTeamId, request.slackUserId, membership)
          }
        }
        val updated = membership.copy(
          slackUsername = request.slackUser.collect { case fullInfo: FullSlackUserInfo => fullInfo.username } orElse membership.slackUsername,
          kind = if (request.slackUser.exists(_.bot)) SlackAccountKind.Bot else membership.kind,
          userId = request.userId orElse membership.userId,
          tokenWithScopes = request.tokenWithScopes orElse membership.tokenWithScopes,
          slackUser = request.slackUser.filter(_.isFull) orElse membership.slackUser.filter(_.isFull) orElse request.slackUser orElse membership.slackUser
        )
        if (updated == membership) (membership, false) else (save(updated), (updated.userId != membership.userId))
      case inactiveMembershipOpt =>
        val newMembership = SlackTeamMembership(
          id = inactiveMembershipOpt.map(_.id.get),
          userId = request.userId,
          slackUserId = request.slackUserId,
          slackUsername = request.slackUser.collect { case fullInfo: FullSlackUserInfo => fullInfo.username },
          slackTeamId = request.slackTeamId,
          kind = if (request.slackUser.exists(_.bot)) SlackAccountKind.Bot else SlackAccountKind.User,
          tokenWithScopes = request.tokenWithScopes,
          slackUser = request.slackUser
        )
        (save(newMembership), true)
    }
  }

  def internWithMessage(slackTeam: SlackTeam, message: SlackMessage)(implicit session: RWSession): (SlackTeamMembership, Boolean) = {
    getBySlackTeamAndUser(slackTeam.slackTeamId, message.userId, excludeState = None) match {
      case Some(existingMembership) if existingMembership.isActive =>
        val updated = existingMembership.withIngestedMessage(message)
        (if (updated != existingMembership) save(updated) else existingMembership, false)
      case inactiveMembershipOpt =>
        val newMembership = SlackTeamMembership(
          id = inactiveMembershipOpt.map(_.id.get),
          userId = None,
          slackUserId = message.userId,
          slackUsername = Some(message.username),
          slackTeamId = slackTeam.slackTeamId,
          kind = SlackAccountKind.User,
          tokenWithScopes = None,
          slackUser = None,
          lastIngestedMessageTimestamp = Some(message.timestamp)
        )
        (save(newMembership), true)
    }
  }

  def getBySlackIdentities(identities: Set[(SlackTeamId, SlackUserId)])(implicit session: RSession): Map[(SlackTeamId, SlackUserId), SlackTeamMembership] = {
    // This query looks up memberships from the db by slack user id only (so we could get some extra values back).
    // We then pare down to the correct values in-memory
    val slackUserIds = identities.map(_._2)
    activeRows.filter(stm => stm.slackUserId.inSet(slackUserIds)).list.groupBy(stm => (stm.slackTeamId, stm.slackUserId)).collect {
      case (k, Seq(v)) if identities.contains(k) => k -> v
    }
  }
  def getByToken(token: SlackUserAccessToken)(implicit session: RSession): Option[SlackTeamMembership] = {
    activeRows.filter(_.token === token).firstOption
  }

  def getByUserId(userId: Id[User])(implicit session: RSession): Seq[SlackTeamMembership] = {
    activeRows.filter(_.userId === userId).list
  }

  def getByUserIdAndSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit session: RSession): Option[SlackTeamMembership] = {
    activeRows.filter(r => r.userId === userId && r.slackTeamId === slackTeamId).firstOption
  }

  @StatsdTiming("SlackTeamMembershipRepo.getRipeForPersonalDigest")
  def getRipeForPersonalDigest(limit: Int, overrideProcessesOlderThan: DateTime, now: DateTime)(implicit session: RSession): Seq[Id[SlackTeamMembership]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""
    SELECT stm.id
    FROM slack_team st INNER JOIN slack_team_membership stm ON st.slack_team_id = stm.slack_team_id
    WHERE st.state = 'active' AND st.no_personal_digests_until < $now
      AND stm.id = (SELECT sub.id FROM slack_team_membership sub
                    WHERE sub.slack_team_id = st.slack_team_id
                      AND sub.state = 'active'
                      AND sub.personal_digest_setting != 'off'
                      AND sub.next_personal_digest_at < $now
                      AND (sub.last_processing_at IS NULL OR sub.last_processing_at < $overrideProcessesOlderThan)
                    ORDER BY sub.next_personal_digest_at ASC, sub.id ASC
                    LIMIT 1)
    ORDER BY stm.next_personal_digest_at ASC, stm.id ASC
    LIMIT $limit
    """.as[Id[SlackTeamMembership]].list
  }
  def markAsProcessingPersonalDigest(id: Id[SlackTeamMembership], overrideProcessesOlderThan: DateTime)(implicit session: RWSession): Boolean = {
    val now = clock.now
    activeRows
      .filter(stm => stm.id === id && stm.availableForProcessing(overrideProcessesOlderThan))
      .map(r => (r.updatedAt, r.lastProcessingAt))
      .update((now, Some(now))) > 0
  }
  def updateLastPersonalDigest(id: Id[SlackTeamMembership])(implicit session: RWSession): Unit = {
    val now = clock.now
    rows.filter(_.id === id).map(stm => (stm.updatedAt, stm.lastPersonalDigestAt)).update(now, Some(now))
  }

  def finishProcessing(id: Id[SlackTeamMembership], delayUntilNextPush: Duration)(implicit session: RWSession): Unit = {
    val now = clock.now
    rows
      .filter(stm => stm.id === id)
      .map(stm => (stm.updatedAt, stm.lastProcessingAt, stm.lastProcessedAt, stm.nextPersonalDigestAt))
      .update((now, None, Some(now), now plus delayUntilNextPush))
  }

  def deactivate(model: SlackTeamMembership)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }

  def getAllByIds(ids: Set[Id[SlackTeamMembership]])(implicit session: RSession): Set[SlackTeamMembership] = activeRows.filter(r => r.id.inSet(ids)).list.toSet

  def getByUserIdsAndSlackTeams(teamByUserId: Map[Id[User], SlackTeamId])(implicit session: RSession): Seq[SlackTeamMembership] = {
    val userIds = teamByUserId.keySet
    activeRows.filter(r => r.userId.inSet(userIds))
      .list
      .filter { stm => stm.userId.isDefined && stm.slackTeamId == teamByUserId(stm.userId.get) }
  }

  def getMembershipsOfKifiUsersWhoHaventExported(fromId: Option[Id[SlackTeamMembership]])(implicit session: RSession): Seq[SlackTeamMembership] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val ids = sql"""
      select stm.id from slack_team_membership stm
      where state = 'active' and user_id is not null and slack_username is not null and id >= ${fromId.map(_.id).getOrElse(0L)}
      and not exists (select id from export_request er where er.user_id = stm.user_id);
    """.as[Id[SlackTeamMembership]].list

    getAllByIds(ids.toSet).toSeq.sortBy(_.id.get.id)
  }
}

