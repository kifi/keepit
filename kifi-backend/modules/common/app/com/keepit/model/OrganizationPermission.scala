package com.keepit.model

import com.keepit.common.json.{ EnumFormat, TraversableFormat }
import com.keepit.common.reflection.Enumerator
import play.api.libs.json._

sealed abstract class OrganizationPermission(val value: String)

object OrganizationPermission extends Enumerator[OrganizationPermission] {
  case object ADD_LIBRARIES extends OrganizationPermission("add_libraries")
  case object CREATE_SLACK_INTEGRATION extends OrganizationPermission("create_slack_integration")
  case object EDIT_ORGANIZATION extends OrganizationPermission("edit_organization")
  case object EXPORT_KEEPS extends OrganizationPermission("export_keeps")
  case object FORCE_EDIT_LIBRARIES extends OrganizationPermission("force_edit_libraries")
  case object GROUP_MESSAGING extends OrganizationPermission("group_messaging")
  case object INVITE_MEMBERS extends OrganizationPermission("invite_members")
  case object MANAGE_PLAN extends OrganizationPermission("manage_plan")
  case object MODIFY_MEMBERS extends OrganizationPermission("modify_members")
  case object PUBLISH_LIBRARIES extends OrganizationPermission("publish_libraries")
  case object REDEEM_CREDIT_CODE extends OrganizationPermission("redeem_credit_code")
  case object REMOVE_LIBRARIES extends OrganizationPermission("remove_libraries")
  case object REMOVE_MEMBERS extends OrganizationPermission("remove_members")
  case object VIEW_MEMBERS extends OrganizationPermission("view_members")
  case object VIEW_ORGANIZATION extends OrganizationPermission("view_organization")
  case object VIEW_SETTINGS extends OrganizationPermission("view_settings")
  case object VERIFY_TO_JOIN extends OrganizationPermission("verify_to_join")

  def all: Set[OrganizationPermission] = _all.toSet

  val format: Format[OrganizationPermission] = Format(
    EnumFormat.reads(get, all.map(_.value)),
    Writes { o => JsString(o.value) }
  )

  implicit val writes = Writes(format.writes)
  val reads = Reads(format.reads)
  implicit val safeSetReads = TraversableFormat.safeSetReads[OrganizationPermission](reads)

  def get(str: String) = all.find(_.value == str)
  def apply(str: String): OrganizationPermission = get(str).getOrElse(throw new Exception(s"Unknown OrganizationPermission $str"))
}
