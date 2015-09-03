package com.keepit.model

import play.api.libs.json._
import play.api.libs.functional.syntax._

sealed abstract class OrganizationPermission(val value: String)

object OrganizationPermission {
  case object VIEW_ORGANIZATION extends OrganizationPermission("view_organization")
  case object EDIT_ORGANIZATION extends OrganizationPermission("edit_organization")
  case object INVITE_MEMBERS extends OrganizationPermission("invite_members")
  case object MODIFY_MEMBERS extends OrganizationPermission("modify_members")
  case object REMOVE_MEMBERS extends OrganizationPermission("remove_members")
  case object ADD_LIBRARIES extends OrganizationPermission("add_libraries")
  case object REMOVE_LIBRARIES extends OrganizationPermission("remove_libraries")
  case object FORCE_EDIT_LIBRARIES extends OrganizationPermission("force_edit_libraries")

  def all: Set[OrganizationPermission] = Set(
    VIEW_ORGANIZATION,
    EDIT_ORGANIZATION,
    INVITE_MEMBERS,
    MODIFY_MEMBERS,
    REMOVE_MEMBERS,
    ADD_LIBRARIES,
    REMOVE_LIBRARIES,
    FORCE_EDIT_LIBRARIES
  )

  implicit val format: Format[OrganizationPermission] =
    Format(__.read[String].map(OrganizationPermission(_)), new Writes[OrganizationPermission] {
      def writes(o: OrganizationPermission) = JsString(o.value)
    })

  def apply(str: String): OrganizationPermission = {
    str match {
      case VIEW_ORGANIZATION.value => VIEW_ORGANIZATION
      case EDIT_ORGANIZATION.value => EDIT_ORGANIZATION
      case INVITE_MEMBERS.value => INVITE_MEMBERS
      case MODIFY_MEMBERS.value => MODIFY_MEMBERS
      case REMOVE_MEMBERS.value => REMOVE_MEMBERS
      case ADD_LIBRARIES.value => ADD_LIBRARIES
      case REMOVE_LIBRARIES.value => REMOVE_LIBRARIES
      case "edit_libraries" => FORCE_EDIT_LIBRARIES // for temp backwards compatibility
      case FORCE_EDIT_LIBRARIES.value => FORCE_EDIT_LIBRARIES
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
    this.copy(permissionsMap ++ PermissionsMap(roleOpt -> added) -- PermissionsMap(roleOpt -> removed))

  def applyPermissionsDiff(pdiff: PermissionsDiff): BasePermissions =
    this.copy(permissionsMap ++ pdiff.added -- pdiff.removed)

  def addPermission(kv: (Option[OrganizationRole], OrganizationPermission)) =
    this.applyPermissionsDiff(PermissionsDiff(added = PermissionsMap(kv._1 -> Set(kv._2))))

  def removePermission(kv: (Option[OrganizationRole], OrganizationPermission)) =
    this.applyPermissionsDiff(PermissionsDiff(removed = PermissionsMap(kv._1 -> Set(kv._2))))

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
  def apply(kvs: (Option[OrganizationRole], Set[OrganizationPermission])*): PermissionsMap = PermissionsMap(kvs.toMap)
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
