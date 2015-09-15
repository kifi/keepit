package com.keepit.payments

import com.keepit.common.db.ExternalId
import com.keepit.model.{ User, Name }
import com.kifi.macros.json
import play.api.libs.json.{ JsValue, Json, JsObject, Writes }

sealed abstract class PaymentRequest

case class AccountFeatureSettingsView(settingAndEnabledByName: Map[Name[PlanFeature], (Setting, Boolean)])
object AccountFeatureSettingsView {
  def apply(features: Set[PlanFeature], featureSettings: Set[FeatureSetting]): AccountFeatureSettingsView = {
    val settingsByName = FeatureSetting.toSettingByName(featureSettings)
    val settingAndEnabledByName = features.map {
      feature => feature.name -> (settingsByName(feature.name), feature.editable)
    }.toMap
    AccountFeatureSettingsView(settingAndEnabledByName)
  }

  implicit val writes = new Writes[AccountFeatureSettingsView] {
    def writes(o: AccountFeatureSettingsView): JsValue = {
      JsObject(o.settingAndEnabledByName.toSeq.map {
        case (name, (setting, editable)) => name.name -> Json.obj("setting" -> setting.value, "editable" -> editable)
      })
    }
  }
}

@json
case class SimpleAccountFeatureSettingRequest(settingByName: Set[FeatureSetting])

@json
case class SimpleAccountContactSettingRequest(id: ExternalId[User], enabled: Boolean)
