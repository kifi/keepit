package com.keepit.model

import com.keepit.common.json.TraversableFormat
import play.api.libs.json._
import play.api.libs.functional.syntax._

sealed abstract class OrganizationPermission(val value: String)

object OrganizationPermission {
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

  def all: Set[OrganizationPermission] = Set(
    ADD_LIBRARIES,
    CREATE_SLACK_INTEGRATION,
    EDIT_ORGANIZATION,
    EXPORT_KEEPS,
    FORCE_EDIT_LIBRARIES,
    GROUP_MESSAGING,
    INVITE_MEMBERS,
    MANAGE_PLAN,
    MODIFY_MEMBERS,
    PUBLISH_LIBRARIES,
    REDEEM_CREDIT_CODE,
    REMOVE_LIBRARIES,
    REMOVE_MEMBERS,
    VIEW_MEMBERS,
    VIEW_ORGANIZATION,
    VIEW_SETTINGS
  )

  val format: Format[OrganizationPermission] =
    Format(__.read[String].map(OrganizationPermission(_)), new Writes[OrganizationPermission] {
      def writes(o: OrganizationPermission) = JsString(o.value)
    })

  implicit val writes = Writes(format.writes)
  val reads = Reads(format.reads)
  implicit val safeSetReads = TraversableFormat.safeSetReads[OrganizationPermission](reads)

  def apply(str: String): OrganizationPermission = {
    str match {
      case ADD_LIBRARIES.value => ADD_LIBRARIES
      case CREATE_SLACK_INTEGRATION.value => CREATE_SLACK_INTEGRATION
      case EDIT_ORGANIZATION.value => EDIT_ORGANIZATION
      case EXPORT_KEEPS.value => EXPORT_KEEPS
      case "edit_libraries" => FORCE_EDIT_LIBRARIES
      case FORCE_EDIT_LIBRARIES.value => FORCE_EDIT_LIBRARIES
      case GROUP_MESSAGING.value => GROUP_MESSAGING
      case INVITE_MEMBERS.value => INVITE_MEMBERS
      case MANAGE_PLAN.value => MANAGE_PLAN
      case MODIFY_MEMBERS.value => MODIFY_MEMBERS
      case PUBLISH_LIBRARIES.value => PUBLISH_LIBRARIES
      case REDEEM_CREDIT_CODE.value => REDEEM_CREDIT_CODE
      case "move_org_libraries" => REMOVE_LIBRARIES
      case REMOVE_LIBRARIES.value => REMOVE_LIBRARIES
      case REMOVE_MEMBERS.value => REMOVE_MEMBERS
      case VIEW_MEMBERS.value => VIEW_MEMBERS
      case VIEW_ORGANIZATION.value => VIEW_ORGANIZATION
      case VIEW_SETTINGS.value => VIEW_SETTINGS
    }
  }
}
