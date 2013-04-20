package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import play.api.libs.json._
import java.net.URI
import java.security.MessageDigest
import scala.collection.mutable
import play.api.mvc.QueryStringBindable
import play.api.mvc.JavascriptLitteral
import com.keepit.common.service.FortyTwoServices
import scala.slick.lifted.BaseTypeMapper
import scala.slick.driver.BasicProfile

case class Invitation(
  id: Option[Id[Invitation]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Invitation] = ExternalId(),
  senderUserId: Option[Id[User]],
  recipientSocialUserId: Id[SocialUserInfo],
  state: State[Invitation] = InvitationStates.ACTIVE
) extends ModelWithExternalId[Invitation] {
  def withId(id: Id[Invitation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[Invitation]) = copy(state = state)
}

@ImplementedBy(classOf[InvitationRepoImpl])
trait InvitationRepo extends Repo[Invitation] with ExternalIdColumnFunction[Invitation] {
  def invitationsPage(page: Int = 0, size: Int = 20, showState: Option[State[Invitation]] = None)
    (implicit session: RSession): Seq[(Option[Invitation], SocialUserInfo)]
  def getByUser(urlId: Id[User])(implicit session: RSession): Seq[Invitation]
  def getByRecipient(socialUserInfoId: Id[SocialUserInfo])(implicit session: RSession): Option[Invitation]
}

@Singleton
class InvitationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val userRepo: UserRepoImpl,
    val socialUserInfoRepo: SocialUserInfoRepoImpl,
    val clock: Clock)
  extends DbRepo[Invitation] with InvitationRepo with ExternalIdColumnDbFunction[Invitation] {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[Invitation](db, "invitation") with ExternalIdColumn[Invitation] {
    def senderUserId = column[Option[Id[User]]]("sender_user_id", O.NotNull)
    def recipientSocialUserId = column[Id[SocialUserInfo]]("recipient_social_user_id", O.NotNull)

    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ senderUserId ~ recipientSocialUserId ~ state <> (Invitation, Invitation.unapply _)
  }

  private implicit val userIdTypeMapper = userRepo.idMapper
  private implicit val userStateMapper = userRepo.stateTypeMapper

  def invitationsPage(page: Int = 0, size: Int = 20, showState: Option[State[Invitation]] = None)
      (implicit session: RSession): Seq[(Option[Invitation], SocialUserInfo)] = {
    val showPending = !showState.exists(_ != InvitationStates.ACCEPTED)
    val s: String =
      s"""
        | select
        |   invitation.id as invitation_id, social_user_info.id as social_user_id
        | from social_user_info
        |   left join user on user.id = social_user_info.user_id
        |   left join invitation on invitation.recipient_social_user_id = social_user_info.id
        | where
        |   (user.state = 'pending' and $showPending) or
        |   (invitation.id is not null and (invitation.state = '${showState.orNull}' or ${showState.isEmpty}))
        | limit $size
        | offset ${size * page};
      """.stripMargin
    val rs = session.getPreparedStatement(s).executeQuery()
    val results = new mutable.ArrayBuffer[(Option[Id[Invitation]], Id[SocialUserInfo])]
    while (rs.next()) {
      results += Option(rs.getLong("invitation_id")).filterNot(_ => rs.wasNull()).map(Id[Invitation]) ->
          Id[SocialUserInfo](rs.getLong("social_user_id"))
    }
    results.map {
      case (Some(invId), suid) => (Some(get(invId)), socialUserInfoRepo.get(suid))
      case (None, suid) => (None, socialUserInfoRepo.get(suid))
    }
  }

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Invitation] =
    (for(b <- table if b.senderUserId === userId && b.state =!= InvitationStates.INACTIVE) yield b).list

  def getByRecipient(socialUserInfoId: Id[SocialUserInfo])(implicit session: RSession): Option[Invitation] = {
    (for(b <- table if b.recipientSocialUserId === socialUserInfoId) yield b).take(1).firstOption
  }

}

object InvitationStates extends States[Invitation] {
  val ACCEPTED = State[Invitation]("accepted")
  val ADMIN_REJECTED = State[Invitation]("admin_rejected")
  val ADMIN_ACCEPTED = State[Invitation]("admin_accepted")
  val JOINED = State[Invitation]("joined")
}
