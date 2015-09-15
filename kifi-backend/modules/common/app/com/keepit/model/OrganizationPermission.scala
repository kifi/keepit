package com.keepit.model

import play.api.libs.json._
import play.api.libs.functional.syntax._

sealed abstract class OrganizationPermission(val value: String)

object OrganizationPermission {
  case object VIEW_ORGANIZATION extends OrganizationPermission("view_organization")
  case object EDIT_ORGANIZATION extends OrganizationPermission("edit_organization")
  case object VIEW_MEMBERS extends OrganizationPermission("view_members")
  case object INVITE_MEMBERS extends OrganizationPermission("invite_members")
  case object MODIFY_MEMBERS extends OrganizationPermission("modify_members")
  case object REMOVE_MEMBERS extends OrganizationPermission("remove_members")
  case object ADD_LIBRARIES extends OrganizationPermission("add_libraries")
  case object PUBLISH_LIBRARIES extends OrganizationPermission("publish_libraries")
  case object REMOVE_LIBRARIES extends OrganizationPermission("remove_libraries")
  case object FORCE_EDIT_LIBRARIES extends OrganizationPermission("force_edit_libraries")
  case object GROUP_MESSAGING extends OrganizationPermission("group_messaging")
  case object MOVE_ORG_LIBRARIES extends OrganizationPermission("move_org_libraries")
  case object EXPORT_KEEPS extends OrganizationPermission("export_keeps")

  def all: Set[OrganizationPermission] = Set(
    VIEW_ORGANIZATION,
    VIEW_MEMBERS,
    EDIT_ORGANIZATION,
    INVITE_MEMBERS,
    MODIFY_MEMBERS,
    REMOVE_MEMBERS,
    ADD_LIBRARIES,
    PUBLISH_LIBRARIES,
    REMOVE_LIBRARIES,
    FORCE_EDIT_LIBRARIES,
    GROUP_MESSAGING,
    MOVE_ORG_LIBRARIES,
    EXPORT_KEEPS
  )

  implicit val format: Format[OrganizationPermission] =
    Format(__.read[String].map(OrganizationPermission(_)), new Writes[OrganizationPermission] {
      def writes(o: OrganizationPermission) = JsString(o.value)
    })

  def apply(str: String): OrganizationPermission = {
    str match {
      case VIEW_ORGANIZATION.value => VIEW_ORGANIZATION
      case VIEW_MEMBERS.value => VIEW_MEMBERS
      case EDIT_ORGANIZATION.value => EDIT_ORGANIZATION
      case INVITE_MEMBERS.value => INVITE_MEMBERS
      case MODIFY_MEMBERS.value => MODIFY_MEMBERS
      case REMOVE_MEMBERS.value => REMOVE_MEMBERS
      case ADD_LIBRARIES.value => ADD_LIBRARIES
      case PUBLISH_LIBRARIES.value => PUBLISH_LIBRARIES
      case REMOVE_LIBRARIES.value => REMOVE_LIBRARIES
      case "edit_libraries" => FORCE_EDIT_LIBRARIES // for temp backwards compatibility
      case FORCE_EDIT_LIBRARIES.value => FORCE_EDIT_LIBRARIES
      case GROUP_MESSAGING.value => GROUP_MESSAGING
      case MOVE_ORG_LIBRARIES.value => MOVE_ORG_LIBRARIES
      case EXPORT_KEEPS.value => EXPORT_KEEPS
    }
  }

}
case class BasePermissions(permissionsMap: PermissionsMap) {
  def forRole(role: OrganizationRole): Set[OrganizationPermission] = permissionsMap(Some(role))
  def forNonmember: Set[OrganizationPermission] = permissionsMap(None)

  def withPermissions(roleAndPermissions: (Option[OrganizationRole], Set[OrganizationPermission])*): BasePermissions =
    this.copy(permissionsMap overwriteWith PermissionsMap(roleAndPermissions.toMap))

  // Return a BasePermissions where "role" has added and removed permissions
  def modified(roleOpt: Option[OrganizationRole], added: Set[OrganizationPermission], removed: Set[OrganizationPermission]): BasePermissions =
    this.copy(permissionsMap ++ PermissionsMap.just(roleOpt -> added) -- PermissionsMap.just(roleOpt -> removed))

  def applyPermissionsDiff(pdiff: PermissionsDiff): BasePermissions =
    this.copy(permissionsMap ++ pdiff.added -- pdiff.removed)

  def addPermission(kv: (Option[OrganizationRole], OrganizationPermission)) =
    this.applyPermissionsDiff(PermissionsDiff(added = PermissionsMap.just(kv._1 -> Set(kv._2))))

  def removePermission(kv: (Option[OrganizationRole], OrganizationPermission)) =
    this.applyPermissionsDiff(PermissionsDiff(removed = PermissionsMap.just(kv._1 -> Set(kv._2))))

  def diff(that: BasePermissions): PermissionsDiff = PermissionsDiff(added = this.permissionsMap -- that.permissionsMap, that.permissionsMap -- this.permissionsMap)
}
object BasePermissions {
  def apply(pm: Map[Option[OrganizationRole], Set[OrganizationPermission]]): BasePermissions = BasePermissions(PermissionsMap(pm))
  def apply(kvs: (Option[OrganizationRole], Set[OrganizationPermission])*): BasePermissions = BasePermissions(PermissionsMap(kvs.toMap))
  implicit val format: Format[BasePermissions] =
    Format(__.read[PermissionsMap].map(BasePermissions(_)), new Writes[BasePermissions] {
      def writes(bp: BasePermissions) = Json.toJson(bp.permissionsMap)
    })
}

case class PermissionsDiff(added: PermissionsMap = PermissionsMap.empty, removed: PermissionsMap = PermissionsMap.empty)
object PermissionsDiff {
  val empty = PermissionsDiff()
  implicit val format: Format[PermissionsDiff] = (
    (__ \ 'add).formatNullable[PermissionsMap] and
    (__ \ 'remove).formatNullable[PermissionsMap]
  )(PermissionsDiff.fromOptions, PermissionsDiff.toOptions)

  def justAdd(kvs: (Option[OrganizationRole], Set[OrganizationPermission])*): PermissionsDiff = PermissionsDiff(added = PermissionsMap(kvs.toMap))
  def justRemove(kvs: (Option[OrganizationRole], Set[OrganizationPermission])*): PermissionsDiff = PermissionsDiff(removed = PermissionsMap(kvs.toMap))

  def fromOptions(addedOpt: Option[PermissionsMap], removedOpt: Option[PermissionsMap]): PermissionsDiff =
    PermissionsDiff(addedOpt.getOrElse(PermissionsMap.empty), removedOpt.getOrElse(PermissionsMap.empty))

  def toOptions(pdiff: PermissionsDiff): (Option[PermissionsMap], Option[PermissionsMap]) =
    (if (pdiff.added.isEmpty) None else Some(pdiff.added),
      if (pdiff.removed.isEmpty) None else Some(pdiff.removed))
}

case class PermissionsMap(pm: Map[Option[OrganizationRole], Set[OrganizationPermission]]) {
  def ++(that: PermissionsMap) = {
    val allKeys = pm.keySet ++ that.pm.keySet
    val newPM = allKeys.map { k => k -> (find(k) ++ that.find(k)) }.toMap
    PermissionsMap(newPM)
  }
  def --(that: PermissionsMap) = {
    val allKeys = pm.keySet ++ that.pm.keySet
    val newPM = allKeys.map { k => k -> (find(k) -- that.find(k)) }.toMap
    PermissionsMap(newPM)
  }
  def overwriteWith(that: PermissionsMap) = {
    PermissionsMap(pm ++ that.pm)
  }

  def find(roleOpt: Option[OrganizationRole]): Set[OrganizationPermission] = pm.getOrElse(roleOpt, Set.empty)
  def apply(roleOpt: Option[OrganizationRole]) = find(roleOpt)
  def isEmpty: Boolean = pm.isEmpty
}
object PermissionsMap {
  val empty: PermissionsMap = PermissionsMap(Map.empty[Option[OrganizationRole], Set[OrganizationPermission]])
  def just(kvs: (Option[OrganizationRole], Set[OrganizationPermission])*): PermissionsMap = PermissionsMap(kvs.toMap)
  implicit val format: Format[PermissionsMap] = new Format[PermissionsMap] {
    def reads(json: JsValue): JsResult[PermissionsMap] = {
      json.validate[JsObject].map { obj =>
        val pm = (for ((k, v) <- obj.value) yield {
          val roleOpt = if (k == "none") None else Some(OrganizationRole(k))
          val permissions = v.as[Set[OrganizationPermission]]
          roleOpt -> permissions
        }).toMap
        PermissionsMap(pm)
      }
    }
    def writes(permissionsMap: PermissionsMap): JsValue = {
      val jsonMap = for ((roleOpt, permissions) <- permissionsMap.pm) yield {
        val k = roleOpt.map(_.value).getOrElse("none")
        val v = Json.toJson(permissions)
        k -> v
      }
      JsObject(jsonMap.toSeq)
    }
  }
}
