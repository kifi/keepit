package com.keepit.payments

import com.keepit.common.db.{ States, ModelWithState, Id, State }
import com.keepit.common.time._
import com.keepit.model.{ Name, OrganizationRole, Organization, User }
import com.keepit.common.mail.EmailAddress
import com.keepit.social.BasicUser

import com.kifi.macros.json
import org.apache.poi.ss.formula.functions.T

import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.util.Try

case class DollarAmount(cents: Int) extends AnyVal {
  def +(other: DollarAmount) = DollarAmount(cents + other.cents)

  override def toString = s"$$${(cents.toFloat / 100.0)}"
}

@json
case class SimpleAccountContactInfo(who: BasicUser, enabled: Boolean)

case class PaidAccount(
    id: Option[Id[PaidAccount]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[PaidAccount] = PaidAccountStates.ACTIVE,
    orgId: Id[Organization],
    planId: Id[PaidPlan],
    credit: DollarAmount,
    userContacts: Seq[Id[User]],
    emailContacts: Seq[EmailAddress],
    lockedForProcessing: Boolean = false,
    frozen: Boolean = false,
    settingsConfiguration: Map[Name[PlanFeature], Setting]) extends ModelWithState[PaidAccount] {

  def withId(id: Id[PaidAccount]): PaidAccount = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): PaidAccount = this.copy(updatedAt = now)
  def withState(state: State[PaidAccount]): PaidAccount = this.copy(state = state)
  def freeze: PaidAccount = this.copy(frozen = true) //a frozen account will not be charged anything by the payment processor until unfrozen by an admin. Intended for automatically detected data integrity issues.
}

object PaidAccountStates extends States[PaidAccount]

sealed abstract class Setting(val value: String)
@json
case class PermissionSetting(role: Option[OrganizationRole]) extends Setting(role.map(_.value).getOrElse("none"))
@json
case class BinarySetting(toggle: Boolean) extends Setting(toggle.toString)

object Setting {
  implicit val format = Format(__.read[String].map(Setting(_)), new Writes[Setting] { def writes(o: Setting): JsValue = JsString(o.value) })

  def apply(str: String): Setting = str match {
    case str if Try(OrganizationRole(str)).isSuccess => PermissionSetting(Some(OrganizationRole(str)))
    case "none" => PermissionSetting(None)
    case _ => throw new Exception("called Setting.apply with an unsupported value")
  }
}

case class FeatureSetting(name: Name[PlanFeature], setting: Setting)

object FeatureSetting {

  implicit val format: Format[FeatureSetting] = (
    (__ \ 'name).format(Name.format[PlanFeature]) and
    (__ \ 'setting).format[Setting]
  )(FeatureSetting.apply, unlift(FeatureSetting.unapply))

  def toSettingsByFeature(settings: Set[FeatureSetting]): Map[Name[PlanFeature], Setting] = {
    settings.map { case config => config.name -> config.setting }.toMap
  }

  //  def toBasePermissions(settings: Set[FeatureSettingConfig]): BasePermissions = {
  //    settings match {
  //      case permissionSettings: Set[PermissionSettingConfig] => {
  //        val settingsByFeature = permissionSettings.map {
  //          case config => config.setting.role -> PlanFeature.toPermission(config.feature) // setting enabled, use specified setting for role
  //          case config => config.feature.default.role -> PlanFeature.toPermission(config.feature) // setting disabled, use default setting for role
  //        }.groupBy(_._1).map { case (role, roleAndPermissions) => role -> roleAndPermissions.map(_._2) }
  //        BasePermissions(settingsByFeature)
  //      }
  //      case _ => throw new Exception("can't call .toBasePermissions ")
  //    }
  //  }
}
