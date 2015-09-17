package com.keepit.payments

import com.keepit.common.db.ExternalId
import com.keepit.model.{ User, Name }
import com.kifi.macros.json
import play.api.data.validation.ValidationError
import play.api.libs.json._

sealed abstract class PaymentRequest

case class AccountFeatureSettingsRequest(featureSettings: Set[FeatureSetting])
object AccountFeatureSettingsRequest {
  implicit val reads = new Reads[AccountFeatureSettingsRequest] {
    def reads(json: JsValue): JsResult[AccountFeatureSettingsRequest] = {
      json.validate[JsObject].map { obj =>
        val featureSettings = obj.fields.map {
          case (key, value) if Feature.get(key).isDefined && Feature.get(key).get.verify(value.as[String]) => FeatureSetting(key, value.as[String])
        }.toSet
        AccountFeatureSettingsRequest(featureSettings)
      }
    }
  }
}

case class AccountFeatureSettingsResponse(clientFeatures: Set[ClientFeature])
object AccountFeatureSettingsResponse {
  def apply(features: Set[PlanFeature], featureSettings: Set[FeatureSetting]): AccountFeatureSettingsResponse = {
    val clientFeatures = features.map {
      case PlanFeature(name, _, editable) if featureSettings.exists(_.featureName == name) =>
        ClientFeature(name, featureSettings.find(_.featureName == name).get.setting, editable)
      case _ => throw new Exception("PlanFeature.name not found in FeatureSettings, possible mismatch between PaidPlan and PaidAccount")
    }
    AccountFeatureSettingsResponse(clientFeatures)
  }

  implicit val writes = new Writes[AccountFeatureSettingsResponse] {
    def writes(o: AccountFeatureSettingsResponse): JsValue = {
      JsObject(o.clientFeatures.map {
        case ClientFeature(name, setting, editable) => name -> Json.obj("setting" -> setting, "editable" -> editable)
      }.toSeq)
    }
  }
}

@json
case class SimpleAccountFeatureSettingRequest(featureSettings: Set[FeatureSetting])

@json
case class SimpleAccountContactSettingRequest(id: ExternalId[User], enabled: Boolean)
