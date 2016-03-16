package com.keepit.slack.models

import javax.crypto.spec.IvParameterSpec

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.crypto.Aes64BitCipher
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.json.EnumFormat
import com.keepit.common.oauth.SlackIdentity
import com.keepit.common.performance.StatsdTiming
import com.keepit.common.reflection.Enumerator
import com.keepit.common.time._
import com.keepit.slack.SlackActionFail
import com.keepit.slack.SlackActionFail._
import com.keepit.model.User
import com.keepit.social.{ IdentityUserIdCache, IdentityUserIdKey, UserIdentity }
import org.joda.time.{ DateTime, Duration }
import play.api.libs.json.{ Format, JsValue, Json }

case class SlackTeamMembershipInternRequest(
  userId: Option[Id[User]],
  slackUserId: SlackUserId,
  slackUsername: SlackUsername,
  slackTeamId: SlackTeamId,
  slackTeamName: SlackTeamName,
  token: Option[SlackUserAccessToken],
  scopes: Set[SlackAuthScope],
  slackUser: Option[SlackUserInfo])

case class InvalidSlackAccountOwnerException(requestingUserId: Id[User], membership: SlackTeamMembership)
  extends Exception(s"Slack account ${membership.slackUsername.value} in team ${membership.slackTeamName.value} already belongs to Kifi user ${membership.userId}")

case class SlackTokenWithScopes(token: SlackUserAccessToken, scopes: Set[SlackAuthScope])
object SlackTokenWithScopes {
  def unapply(stm: SlackTeamMembership): Option[(SlackUserAccessToken, Set[SlackAuthScope])] = stm.token.map(_ -> stm.scopes)
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
  def toUserIdentity(membership: SlackTeamMembership): UserIdentity = {
    UserIdentity(
      SlackIdentity(
        membership.slackTeamId,
        membership.slackTeamName,
        membership.token,
        membership.scopes,
        membership.slackUserId,
        membership.slackUsername,
        membership.slackUser
      ),
      membership.userId
    )
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
  slackUsername: SlackUsername,
  slackTeamId: SlackTeamId,
  slackTeamName: SlackTeamName,
  token: Option[SlackUserAccessToken],
  scopes: Set[SlackAuthScope],
  slackUser: Option[SlackUserInfo],
  // Personal digest scheduling nonsense
  lastPersonalDigestAt: Option[DateTime] = None,
  lastProcessingAt: Option[DateTime] = None,
  lastProcessedAt: Option[DateTime] = None,
  personalDigestSetting: SlackPersonalDigestSetting = SlackPersonalDigestSetting.Defer,
  nextPersonalDigestAt: Option[DateTime] = None,
  lastIngestedMessageTimestamp: Option[SlackTimestamp] = None)
    extends ModelWithState[SlackTeamMembership] with ModelWithSeqNumber[SlackTeamMembership] {
  def withId(id: Id[SlackTeamMembership]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackTeamMembershipStates.ACTIVE
  def tokenWithScopes: Option[SlackTokenWithScopes] = token.map(SlackTokenWithScopes(_, scopes))
  def getTokenIncludingScopes(requiredScopes: Set[SlackAuthScope]): Option[SlackUserAccessToken] = if (requiredScopes subsetOf scopes) token else None
  def unnotifiedSince: DateTime = lastPersonalDigestAt getOrElse createdAt

  def withIngestedMessage(msg: SlackMessage) = {
    require(this.slackUserId == msg.userId)
    this.copy(
      slackUsername = if (lastIngestedMessageTimestamp.exists(_ >= msg.timestamp)) slackUsername else msg.username,
      lastIngestedMessageTimestamp = Some(lastIngestedMessageTimestamp.filter(_ >= msg.timestamp).getOrElse(msg.timestamp))
    )
  }

  // These modify the personal digest setting, so they should be initiated by user actions
  def withNoNextPersonalDigest = this.copy(personalDigestSetting = SlackPersonalDigestSetting.Off, nextPersonalDigestAt = None)
  def withNextPersonalDigestAt(time: DateTime) = this.copy(personalDigestSetting = SlackPersonalDigestSetting.On, nextPersonalDigestAt = Some(time))
  // This just schedules the digest, it doesn't guarantee that it will happen
  def scheduledForDigestAtLatest(time: DateTime) = this.copy(nextPersonalDigestAt = Some(nextPersonalDigestAt.filter(_ isBefore time).getOrElse(time)))

  def revoked = this.copy(token = None, scopes = Set.empty)
  def sanitizeForDelete = this.copy(userId = None, token = None, scopes = Set.empty, state = SlackTeamMembershipStates.INACTIVE)
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
  def getBySlackTeam(slackTeamId: SlackTeamId)(implicit session: RSession): Set[SlackTeamMembership]
  def getBySlackTeamAndUser(slackTeamId: SlackTeamId, slackUserId: SlackUserId, excludeState: Option[State[SlackTeamMembership]] = Some(SlackTeamMembershipStates.INACTIVE))(implicit session: RSession): Option[SlackTeamMembership]

  def internMembership(request: SlackTeamMembershipInternRequest)(implicit session: RWSession): (SlackTeamMembership, Boolean)
  def internWithMessage(slackTeam: SlackTeam, message: SlackMessage)(implicit session: RWSession): (SlackTeamMembership, Boolean)
  def getBySlackIdentities(identities: Set[(SlackTeamId, SlackUserId)])(implicit session: RSession): Map[(SlackTeamId, SlackUserId), SlackTeamMembership]
  def getByToken(token: SlackUserAccessToken)(implicit session: RSession): Option[SlackTeamMembership]
  def getByUserId(userId: Id[User])(implicit session: RSession): Seq[SlackTeamMembership]
  def getByUserIdAndSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit session: RSession): Option[SlackTeamMembership]

  def getRipeForPersonalDigest(limit: Int, overrideProcessesOlderThan: DateTime, now: DateTime, vipTeams: Set[SlackTeamId])(implicit session: RSession): Seq[Id[SlackTeamMembership]]
  def markAsProcessing(id: Id[SlackTeamMembership], overrideProcessesOlderThan: DateTime)(implicit session: RWSession): Boolean
  def updateLastPersonalDigest(id: Id[SlackTeamMembership])(implicit session: RWSession): Unit
  def finishProcessing(id: Id[SlackTeamMembership], delayUntilNextPush: Duration)(implicit session: RWSession): Unit

  def deactivate(model: SlackTeamMembership)(implicit session: RWSession): Unit

  //admin
  def getAllByIds(ids: Set[Id[SlackTeamMembership]])(implicit session: RSession): Set[SlackTeamMembership]
  def getByUserIdsAndSlackTeams(keys: Map[Id[User], SlackTeamId])(implicit session: RSession): Seq[SlackTeamMembership]
}

@Singleton
class SlackTeamMembershipRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    slackTeamRepo: SlackTeamRepo, // implicit dependency based in manual SQL query
    userIdentityCache: IdentityUserIdCache) extends DbRepo[SlackTeamMembership] with SeqNumberDbFunction[SlackTeamMembership] with SlackTeamMembershipRepo {
  // Don't put a cache on the whole model. There's a bunch of
  // scheduling garbage that is changed via update statements.

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val slackUsernameColumnType = SlackDbColumnTypes.username(db)
  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackTeamNameColumnType = SlackDbColumnTypes.teamName(db)
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
    slackUsername: SlackUsername,
    slackTeamId: SlackTeamId,
    slackTeamName: SlackTeamName,
    token: Option[SlackUserAccessToken],
    scopes: JsValue,
    slackUser: Option[JsValue],
    lastPersonalDigestAt: Option[DateTime],
    lastProcessingAt: Option[DateTime],
    lastProcessedAt: Option[DateTime],
    personalDigestSetting: SlackPersonalDigestSetting,
    nextPersonalDigestAt: Option[DateTime],
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
      slackTeamName,
      token,
      scopes.as[Set[SlackAuthScope]],
      slackUser.map(_.as[SlackUserInfo]),
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
    membership.slackTeamName,
    membership.token,
    Json.toJson(membership.scopes),
    membership.slackUser.map(Json.toJson(_)),
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
    def slackUsername = column[SlackUsername]("slack_username", O.NotNull)
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackTeamName = column[SlackTeamName]("slack_team_name", O.NotNull)
    def token = column[Option[SlackUserAccessToken]]("token", O.Nullable)
    def scopes = column[JsValue]("scopes", O.NotNull)
    def slackUser = column[Option[JsValue]]("slack_user", O.Nullable)
    def lastPersonalDigestAt = column[Option[DateTime]]("last_personal_digest_at", O.Nullable)
    def lastProcessingAt = column[Option[DateTime]]("last_processing_at", O.Nullable)
    def lastProcessedAt = column[Option[DateTime]]("last_processed_at", O.Nullable)
    def personalDigestSetting = column[SlackPersonalDigestSetting]("personal_digest_setting", O.NotNull)
    def nextPersonalDigestAt = column[Option[DateTime]]("next_personal_digest_at", O.Nullable)
    def lastIngestedMessageTimestamp = column[Option[SlackTimestamp]]("last_ingested_message_timestamp", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, seq, userId, slackUserId, slackUsername, slackTeamId, slackTeamName, token, scopes, slackUser, lastPersonalDigestAt, lastProcessingAt, lastProcessedAt, personalDigestSetting, nextPersonalDigestAt, lastIngestedMessageTimestamp) <> ((membershipFromDbRow _).tupled, membershipToDbRow _)

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

  def getBySlackTeam(slackTeamId: SlackTeamId)(implicit session: RSession): Set[SlackTeamMembership] = {
    activeRows.filter(row => row.slackTeamId === slackTeamId).list.toSet
  }
  def getBySlackTeamAndUser(slackTeamId: SlackTeamId, slackUserId: SlackUserId, excludeState: Option[State[SlackTeamMembership]] = Some(SlackTeamMembershipStates.INACTIVE))(implicit session: RSession): Option[SlackTeamMembership] = {
    rows.filter(row => row.slackTeamId === slackTeamId && row.slackUserId === slackUserId && row.state =!= excludeState.orNull).firstOption
  }
  def internMembership(request: SlackTeamMembershipInternRequest)(implicit session: RWSession): (SlackTeamMembership, Boolean) = {
    getBySlackTeamAndUser(request.slackTeamId, request.slackUserId, excludeState = None) match {
      case Some(membership) if membership.isActive =>
        membership.userId.foreach { ownerId =>
          request.userId.foreach { requesterId =>
            if (requesterId != ownerId) throw SlackActionFail.MembershipExists(requesterId, ownerId, request.slackTeamId, request.slackTeamName, request.slackUserId, membership)
          }
        }
        val updated = membership.copy(
          slackUsername = request.slackUsername,
          slackTeamName = request.slackTeamName,
          userId = request.userId orElse membership.userId,
          token = request.token orElse membership.token,
          scopes = request.scopes,
          slackUser = request.slackUser orElse membership.slackUser
        )
        if (updated == membership) (membership, false) else (save(updated), (updated.userId != membership.userId))
      case inactiveMembershipOpt =>
        val newMembership = SlackTeamMembership(
          id = inactiveMembershipOpt.map(_.id.get),
          userId = request.userId,
          slackUserId = request.slackUserId,
          slackUsername = request.slackUsername,
          slackTeamId = request.slackTeamId,
          slackTeamName = request.slackTeamName,
          token = request.token,
          scopes = request.scopes,
          slackUser = request.slackUser,
          nextPersonalDigestAt = None
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
          slackUsername = message.username,
          slackTeamId = slackTeam.slackTeamId,
          slackTeamName = slackTeam.slackTeamName,
          token = None,
          scopes = Set.empty,
          slackUser = None,
          lastIngestedMessageTimestamp = Some(message.timestamp),
          nextPersonalDigestAt = None
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
  def getRipeForPersonalDigest(limit: Int, overrideProcessesOlderThan: DateTime, now: DateTime, vipTeams: Set[SlackTeamId])(implicit session: RSession): Seq[Id[SlackTeamMembership]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val vipTeamsStr = vipTeams.map(id => s"'${id.value}'").mkString("(", ",", ")") // I hate myself and everyone else
    sql"""
    SELECT stm.id
    FROM slack_team_membership stm INNER JOIN slack_team st ON (stm.slack_team_id = st.slack_team_id)
    WHERE st.slack_team_id IN #$vipTeamsStr AND
          (st.no_personal_digests_until IS NOT NULL AND st.no_personal_digests_until < $now) AND
          stm.id = ( SELECT sub.id FROM slack_team_membership sub
                     WHERE sub.slack_team_id = stm.slack_team_id AND
                           sub.state = 'active' AND
                           sub.personal_digest_setting != 'off' AND
                           (sub.next_personal_digest_at IS NOT NULL AND sub.next_personal_digest_at < $now) AND
                           (sub.last_processing_at IS NULL OR sub.last_processing_at < $overrideProcessesOlderThan)
                     ORDER BY sub.next_personal_digest_at ASC, sub.id ASC
                     LIMIT 1
                   )
    ORDER BY stm.next_personal_digest_at ASC, stm.id ASC
    LIMIT $limit
    """.as[Id[SlackTeamMembership]].list
  }
  def markAsProcessing(id: Id[SlackTeamMembership], overrideProcessesOlderThan: DateTime)(implicit session: RWSession): Boolean = {
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
      .update((now, None, Some(now), Some(now plus delayUntilNextPush)))
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
}

