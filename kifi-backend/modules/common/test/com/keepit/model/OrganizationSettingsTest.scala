package com.keepit.model

import org.specs2.mutable.Specification
import play.api.libs.json.Json

class OrganizationSettingsTest extends Specification {
  implicit val format = OrganizationSettings.dbFormat
  "OrganizationSettings" should {
    "deserialize" in {
      "handle easy inputs" in {
        val input = Json.obj(Feature.ViewMembers.value -> FeatureSetting.MEMBERS.value)
        val expected = OrganizationSettings(Map(Feature.ViewMembers -> FeatureSetting.MEMBERS))
        input.asOpt[OrganizationSettings] === Some(expected)
      }
      "handle broken inputs robustly" in {
        val input = Json.obj(Feature.ViewMembers.value -> FeatureSetting.MEMBERS.value, "totally_garbage_feature" -> "disabled")
        val expected = OrganizationSettings(Map(Feature.ViewMembers -> FeatureSetting.MEMBERS))
        input.asOpt[OrganizationSettings] === Some(expected)
      }
    }
  }

}
