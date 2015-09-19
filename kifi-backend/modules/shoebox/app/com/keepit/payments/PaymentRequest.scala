package com.keepit.payments

import com.keepit.common.db.ExternalId
import com.keepit.model.{ User }
import com.kifi.macros.json
import play.api.libs.json._
import play.api.mvc.Results.Status
import play.api.http.Status._

import scala.util.control.NoStackTrace

sealed abstract class PaymentRequest

case class AccountFeatureSettingsRequest(featureSettings: Set[FeatureSetting]) extends PaymentRequest
object AccountFeatureSettingsRequest {
  implicit val reads = new Reads[AccountFeatureSettingsRequest] {
    def reads(json: JsValue): JsResult[AccountFeatureSettingsRequest] = {
      json.validate[JsObject].map { obj =>
        val featureSettings = obj.fieldSet.map {
          case (key: String, value: JsValue) if Feature.get(key).isDefined && Feature.get(key).get.verify(value.as[String]) => FeatureSetting(key, value.as[String])
        }.toSet
        AccountFeatureSettingsRequest(featureSettings)
      }
    }
  }
}

case class AccountFeatureSettingsResponse(clientFeatures: Set[ClientFeature], planKind: PaidPlan.Kind) extends PaymentRequest
object AccountFeatureSettingsResponse {
  def apply(features: Set[PlanFeature], featureSettings: Set[FeatureSetting], planKind: PaidPlan.Kind): AccountFeatureSettingsResponse = {
    val clientFeatures = features.map {
      case PlanFeature(name, _, editable) if featureSettings.exists(_.name == name) =>
        ClientFeature(name, featureSettings.find(_.name == name).get.setting, editable)
      case PlanFeature(name, _, _) => throw new Exception(s"PlanFeature.name=$name not found in FeatureSettings, possible mismatch between PaidPlan and PaidAccount")
    }
    AccountFeatureSettingsResponse(clientFeatures, planKind)
  }

  implicit val writes = new Writes[AccountFeatureSettingsResponse] {
    def writes(o: AccountFeatureSettingsResponse): JsValue = {
      val settingsJson = JsObject(o.clientFeatures.map {
        case ClientFeature(name, setting, editable) => name -> Json.obj("setting" -> setting, "editable" -> editable)
      }.toSeq)
      Json.obj("kind" -> o.planKind, "settings" -> settingsJson)
    }
  }
}

@json
case class SimpleAccountContactSettingRequest(id: ExternalId[User], enabled: Boolean) extends PaymentRequest

sealed abstract class PaymentFail(val status: Int, val message: String) extends Exception(message) with NoStackTrace {
  def asErrorResponse = Status(status)(Json.obj("error" -> message))
}

object PaymentFail {
  case object INSUFFICIENT_PERMISSIONS extends PaymentFail(FORBIDDEN, "insufficient_permissions")
}
