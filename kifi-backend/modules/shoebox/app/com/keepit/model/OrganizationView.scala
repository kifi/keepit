package com.keepit.model

import com.keepit.common.json
import com.keepit.common.mail.BasicContact
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.json.{ JsObject, Json, Writes }

final case class MaybeOrganizationMember(member: Either[BasicUser, BasicContact], access: Option[OrganizationAccess], lastInvitedAt: Option[DateTime])
final case class OrganizationFail(status: Int, message: String)

object MaybeOrganizationMember {
  implicit val writes = Writes[MaybeOrganizationMember] { maybeMember =>
    val identityFields = maybeMember.member.fold(user => Json.toJson(user), contact => Json.toJson(contact)).as[JsObject]
    val relatedFields = Json.obj("membership" -> maybeMember.access, "lastInvitedAt" -> maybeMember.lastInvitedAt)
    json.minify(identityFields ++ relatedFields)
  }
}
