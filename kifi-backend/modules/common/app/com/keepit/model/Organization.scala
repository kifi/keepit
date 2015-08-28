package com.keepit.model

import java.net.URLEncoder
import javax.crypto.spec.IvParameterSpec

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.{ ModelWithPublicId, ModelWithPublicIdCompanion }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.strings._
import com.keepit.common.time._
import com.kifi.macros.json
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.{ QueryStringBindable, PathBindable }

import scala.concurrent.duration.Duration
import scala.util.Try

case class Organization(
    id: Option[Id[Organization]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[Organization] = OrganizationStates.ACTIVE,
    seq: SequenceNumber[Organization] = SequenceNumber.ZERO,
    name: String,
    description: Option[String],
    ownerId: Id[User],
    primaryHandle: Option[PrimaryOrganizationHandle],
    site: Option[String],
    basePermissions: BasePermissions = Organization.defaultBasePermissions) extends ModelWithPublicId[Organization] with ModelWithState[Organization] with ModelWithSeqNumber[Organization] {

  override def withId(id: Id[Organization]): Organization = this.copy(id = Some(id))
  override def withUpdateTime(now: DateTime): Organization = this.copy(updatedAt = now)
  def withState(newState: State[Organization]): Organization = this.copy(state = newState)
  def withName(newName: String): Organization = this.copy(name = newName)
  def withDescription(newDescription: Option[String]): Organization = this.copy(description = newDescription)
  def withOwner(newOwner: Id[User]): Organization = this.copy(ownerId = newOwner)
  def withBasePermissions(newBasePermissions: BasePermissions): Organization = {
    this.copy(basePermissions = new BasePermissions(basePermissions.permissionsMap ++ newBasePermissions.permissionsMap))
  }
  def withSite(newSite: Option[String]): Organization = this.copy(site = newSite)
  def hiddenFromNonmembers: Organization = {
    this.withBasePermissions(BasePermissions(Map(None -> Set())))
  }

  def abbreviatedName = this.name.abbreviate(33)

  def getNonmemberPermissions = basePermissions.forNonmember
  def getRolePermissions(role: OrganizationRole) = basePermissions.forRole(role)

  def newMembership(userId: Id[User], role: OrganizationRole): OrganizationMembership = {
    OrganizationMembership(organizationId = id.get, userId = userId, role = role, permissions = getRolePermissions(role))
  }

  def modifiedMembership(membership: OrganizationMembership, newRole: OrganizationRole): OrganizationMembership =
    membership.copy(role = newRole, permissions = getRolePermissions(newRole))

  def handle: OrganizationHandle = {
    primaryHandle match {
      case Some(h) => h.original
      case None => throw Organization.UndefinedOrganizationHandleException(this) // rare occurrence, .handle should be safe to use
    }
  }

  def toIngestableOrganization = IngestableOrganization(id, state, seq, name, description, ownerId, Try(this.handle).toOption)

  def isActive: Boolean = state == OrganizationStates.ACTIVE
  def isInactive: Boolean = state == OrganizationStates.INACTIVE
  def sanitizeForDelete = this.copy(
    state = OrganizationStates.INACTIVE,
    name = RandomStringUtils.randomAlphanumeric(20),
    primaryHandle = None,
    basePermissions = Organization.totallyInvisiblePermissions,
    description = None
  )
}

object Organization extends ModelWithPublicIdCompanion[Organization] {
  implicit val primaryHandleFormat = PrimaryOrganizationHandle.jsonAnnotationFormat

  protected val publicIdPrefix = "o"
  protected val publicIdIvSpec = new IvParameterSpec(Array(62, 91, 74, 34, 82, -77, 19, -35, -118, 3, 112, -59, -70, 94, 101, -115))

  val defaultBasePermissions: BasePermissions =
    BasePermissions(Map(
      None -> Set(OrganizationPermission.VIEW_ORGANIZATION),

      Some(OrganizationRole.ADMIN) -> OrganizationPermission.all,

      Some(OrganizationRole.MEMBER) -> Set(
        OrganizationPermission.VIEW_ORGANIZATION,
        OrganizationPermission.ADD_LIBRARIES
      )
    ))
  val totallyInvisiblePermissions: BasePermissions =
    BasePermissions(OrganizationRole.allOpts.map(_ -> Set.empty[OrganizationPermission]).toMap)

  implicit val format: Format[Organization] = (
    (__ \ 'id).formatNullable[Id[Organization]] and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format(State.format[Organization]) and
    (__ \ 'seq).format(SequenceNumber.format[Organization]) and
    (__ \ 'name).format[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'ownerId).format(Id.format[User]) and
    (__ \ 'handle).formatNullable[PrimaryOrganizationHandle] and
    (__ \ 'site).formatNullable[String] and
    (__ \ 'basePermissions).format[BasePermissions]
  )(Organization.apply, unlift(Organization.unapply))

  def applyFromDbRow(
    id: Option[Id[Organization]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[Organization],
    seq: SequenceNumber[Organization],
    name: String,
    description: Option[String],
    ownerId: Id[User],
    organizationHandle: Option[OrganizationHandle],
    normalizedOrganizationHandle: Option[OrganizationHandle],
    site: Option[String],
    basePermissions: BasePermissions) = {
    val primaryOrganizationHandle = for {
      original <- organizationHandle
      normalized <- normalizedOrganizationHandle
    } yield PrimaryOrganizationHandle(original, normalized)
    Organization(id, createdAt, updatedAt, state, seq, name, description, ownerId, primaryOrganizationHandle, site, basePermissions)
  }

  def unapplyToDbRow(org: Organization) = {
    Some((org.id,
      org.createdAt,
      org.updatedAt,
      org.state,
      org.seq,
      org.name,
      org.description,
      org.ownerId,
      org.primaryHandle.map(_.original),
      org.primaryHandle.map(_.normalized),
      org.site,
      org.basePermissions))
  }

  case class UndefinedOrganizationHandleException(org: Organization) extends Exception(s"no handle found for $org")
}

case class IngestableOrganization(id: Option[Id[Organization]], state: State[Organization], seq: SequenceNumber[Organization], name: String, description: Option[String], ownerId: Id[User], handle: Option[OrganizationHandle])

object IngestableOrganization {
  implicit val format = (
    (__ \ 'id).formatNullable[Id[Organization]] and
    (__ \ 'state).format[State[Organization]] and
    (__ \ 'seq).format[SequenceNumber[Organization]] and
    (__ \ 'name).format[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'ownerId).format[Id[User]] and
    (__ \ 'handle).formatNullable[OrganizationHandle]
  )(IngestableOrganization.apply _, unlift(IngestableOrganization.unapply))
}

object OrganizationStates extends States[Organization]

@json
case class OrganizationHandle(value: String) extends AnyVal {
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
}

object OrganizationHandle {
  implicit def pathBinder = new PathBindable[OrganizationHandle] {
    override def bind(key: String, value: String): Either[String, OrganizationHandle] = Right(OrganizationHandle(value))
    override def unbind(key: String, handle: OrganizationHandle): String = handle.value
  }
}

@json
case class PrimaryOrganizationHandle(original: OrganizationHandle, normalized: OrganizationHandle)

case class BasePermissions(permissionsMap: Map[Option[OrganizationRole], Set[OrganizationPermission]]) {
  def forRole(role: OrganizationRole): Set[OrganizationPermission] = permissionsMap(Some(role))
  def forNonmember: Set[OrganizationPermission] = permissionsMap(None)

  // Return a BasePermissions where "role" has added and removed permissions
  def modified(roleOpt: Option[OrganizationRole], added: Set[OrganizationPermission], removed: Set[OrganizationPermission]): BasePermissions =
    BasePermissions(permissionsMap.updated(roleOpt, permissionsMap(roleOpt) ++ added -- removed))
}

object BasePermissions {
  implicit val format: Format[BasePermissions] = new Format[BasePermissions] {
    def reads(json: JsValue): JsResult[BasePermissions] = {
      json.validate[JsObject].map { obj =>
        val permissionsMap = (for ((k, v) <- obj.value) yield {
          val roleOpt = if (k == "none") None else Some(OrganizationRole(k))
          val permissions = v.as[Set[OrganizationPermission]]
          roleOpt -> permissions
        }).toMap
        BasePermissions(permissionsMap)
      }
    }
    def writes(bp: BasePermissions): JsValue = {
      val jsonMap = for ((roleOpt, permissions) <- bp.permissionsMap) yield {
        val k = roleOpt.map(_.value).getOrElse("none")
        val v = Json.toJson(permissions)
        k -> v
      }
      JsObject(jsonMap.toSeq)
    }
  }
}

@json
case class OrgTrackingValues(
  libraryCount: Int,
  keepCount: Int,
  inviteCount: Int,
  collabLibCount: Int)

case class OrganizationKey(id: Id[Organization]) extends Key[Organization] {
  override val version = 4
  val namespace = "organization_by_id"
  def toKey(): String = id.id.toString
}

class OrganizationCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[OrganizationKey, Organization](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class PrimaryOrgForUserKey(id: Id[User]) extends Key[Id[Organization]] {
  override val version = 1
  val namespace = "primary_org_user"
  def toKey(): String = id.id.toString
}

class PrimaryOrgForUserCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[PrimaryOrgForUserKey, Id[Organization]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class OrgTrackingValuesKey(id: Id[Organization]) extends Key[OrgTrackingValues] {
  override val version = 1
  val namespace = "org_tracking_values"
  def toKey(): String = id.id.toString
}

class OrgTrackingValuesCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[OrgTrackingValuesKey, OrgTrackingValues](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

