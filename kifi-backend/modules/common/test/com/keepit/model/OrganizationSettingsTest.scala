package com.keepit.model

import org.specs2.mutable.Specification
import play.api.libs.json.Json

class OrganizationSettingsTest extends Specification {
  implicit val format = OrganizationSettings.dbFormat
  "OrganizationSettings" should {
    "deserialize" in {
      "handle easy inputs" in {
        val input = Json.obj(StaticFeature.ViewMembers.value -> StaticFeatureSetting.MEMBERS.value)
        val expected = OrganizationSettings(Map(StaticFeature.ViewMembers -> StaticFeatureSetting.MEMBERS))
        input.asOpt[OrganizationSettings] === Some(expected)
      }
      "handle broken inputs robustly" in {
        val input = Json.obj(StaticFeature.ViewMembers.value -> StaticFeatureSetting.MEMBERS.value, "totally_garbage_feature" -> "disabled")
        val expected = OrganizationSettings(Map(StaticFeature.ViewMembers -> StaticFeatureSetting.MEMBERS))
        input.asOpt[OrganizationSettings] === Some(expected)
      }
    }
  }

}
