package com.keepit.slack.models

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db.{ ModelWithState, Id, State, States }
import com.keepit.common.time._
import com.keepit.model.{ User, Organization }
import org.joda.time.DateTime

case class UnauthorizedSlackTeamOrganizationModificationException(team: Option[SlackTeam], userId: Id[User], newOrganizationId: Option[Id[Organization]])
  extends Exception(s"Unauthorized request from user $userId to connect ${team.map(_.toString) getOrElse "unkwown SlackTeam"} with organization $newOrganizationId.")

case class SlackTeam(
    id: Option[Id[SlackTeam]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[SlackTeam] = SlackTeamStates.ACTIVE,
    slackTeamId: SlackTeamId,
    slackTeamName: SlackTeamName,
    organizationId: Option[Id[Organization]]) extends ModelWithState[SlackTeam] {
  def withId(id: Id[SlackTeam]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackTeamStates.ACTIVE
  def withName(name: SlackTeamName) = this.copy(slackTeamName = name)
}

object SlackTeamStates extends States[SlackTeam]

@ImplementedBy(classOf[SlackTeamRepoImpl])
trait SlackTeamRepo extends Repo[SlackTeam] {
  def getBySlackTeamId(slackTeamId: SlackTeamId, excludeState: Option[State[SlackTeam]] = Some(SlackTeamStates.INACTIVE))(implicit session: RSession): Option[SlackTeam]
  def internSlackTeam(identity: SlackIdentifyResponse)(implicit session: RWSession): SlackTeam
}

@Singleton
class SlackTeamRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[SlackTeam] with SlackTeamRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackTeamNameColumnType = SlackDbColumnTypes.teamName(db)

  private def teamFromDbRow(id: Option[Id[SlackTeam]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[SlackTeam] = SlackTeamStates.ACTIVE,
    slackTeamId: SlackTeamId,
    slackTeamName: SlackTeamName,
    organizationId: Option[Id[Organization]]) = {
    SlackTeam(
      id,
      createdAt,
      updatedAt,
      state,
      slackTeamId,
      slackTeamName,
      organizationId
    )
  }

  private def teamToDbRow(slackTeam: SlackTeam) = Some((
    slackTeam.id,
    slackTeam.createdAt,
    slackTeam.updatedAt,
    slackTeam.state,
    slackTeam.slackTeamId,
    slackTeam.slackTeamName,
    slackTeam.organizationId
  ))

  type RepoImpl = SlackTeamTable

  class SlackTeamTable(tag: Tag) extends RepoTable[SlackTeam](db, tag, "slack_team") {
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackTeamName = column[SlackTeamName]("slack_team_name", O.NotNull)
    def organizationId = column[Option[Id[Organization]]]("organization_id", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, slackTeamId, slackTeamName, organizationId) <> ((teamFromDbRow _).tupled, teamToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === SlackTeamStates.ACTIVE)
  def table(tag: Tag) = new SlackTeamTable(tag)
  initTable()
  override def deleteCache(membership: SlackTeam)(implicit session: RSession): Unit = {}
  override def invalidateCache(membership: SlackTeam)(implicit session: RSession): Unit = {}

  def getBySlackTeamId(slackTeamId: SlackTeamId, excludeState: Option[State[SlackTeam]] = Some(SlackTeamStates.INACTIVE))(implicit session: RSession): Option[SlackTeam] = {
    rows.filter(row => row.slackTeamId === slackTeamId && row.state =!= excludeState.orNull).firstOption
  }

  def internSlackTeam(identity: SlackIdentifyResponse)(implicit session: RWSession): SlackTeam = {
    getBySlackTeamId(identity.teamId, excludeState = None) match {
      case Some(team) if team.isActive =>
        val updatedTeam = team.withName(identity.teamName)
        if (team == updatedTeam) team else save(updatedTeam)
      case inactiveTeamOpt =>
        val newTeam = SlackTeam(id = inactiveTeamOpt.flatMap(_.id), slackTeamId = identity.teamId, slackTeamName = identity.teamName, organizationId = None)
        save(newTeam)
    }
  }

}
