package com.keepit.model

import com.keepit.common.json.TraversableFormat
import org.specs2.mutable.Specification
import play.api.libs.json.{ Writes, Format, Reads, Json }

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
    "let me do cool things" in {
      "robustly handle js arrays" in {
        val myFormat = TraversableFormat.safeArrayReads[OrganizationRole]
        Json.arr("member", "admin", "garbage").as[Seq[OrganizationRole]](myFormat) === Seq(OrganizationRole.MEMBER, OrganizationRole.ADMIN)
      }
      "robustly handle simple js objects" in {
        val myFormat = TraversableFormat.safeObjectReads[OrganizationRole, Feature]
        val input = Json.obj("member" -> "view_organization", "garbage_role" -> "view_members", "admin" -> "garbage_feature")
        val expected = Map(OrganizationRole.MEMBER -> Feature.ViewOrganization)
        input.as[Map[OrganizationRole, Feature]](myFormat) === expected
      }
      "robustly handle complex js objects" in {
        implicit def settingsReads(f: Feature): Reads[FeatureSetting] = f.settingReads
        val myFormat = TraversableFormat.safeConditionalObjectReads[Feature, FeatureSetting]
        val input = Json.obj("view_organization" -> "members", "create_slack_integration" -> "anyone")
        val expected = Map(Feature.ViewOrganization -> FeatureSetting.MEMBERS)
        input.as[Map[Feature, FeatureSetting]](myFormat) === expected
      }
    }
  }

}
