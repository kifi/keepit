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


case class Invitation(
  id: Option[Id[Invitation]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Invitation] = ExternalId(),
  senderUserId: Id[User],
  recipientSocialUserId: Id[SocialUserInfo],
  state: State[Invitation] = InvitationStates.ACTIVE
) extends ModelWithExternalId[Invitation] {
  def withId(id: Id[Invitation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

}

@ImplementedBy(classOf[InvitationRepoImpl])
trait InvitationRepo extends Repo[Invitation] with ExternalIdColumnFunction[Invitation] {
  def getByUser(urlId: Id[User])(implicit session: RSession): Seq[Invitation]
  def getByRecipient(socialUserInfoId: Id[SocialUserInfo])(implicit session: RSession): Option[Invitation]
}

@Singleton
class InvitationRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[Invitation] with InvitationRepo with ExternalIdColumnDbFunction[Invitation] {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[Invitation](db, "invitation") with ExternalIdColumn[Invitation] {
    def senderUserId = column[Id[User]]("sender_user_id", O.NotNull)
    def recipientSocialUserId = column[Id[SocialUserInfo]]("recipient_social_user_id", O.NotNull)

    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ senderUserId ~ recipientSocialUserId ~ state <> (Invitation, Invitation.unapply _)
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
  val JOINED = State[Invitation]("joined")
}
