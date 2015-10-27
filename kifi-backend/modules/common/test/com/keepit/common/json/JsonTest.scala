package com.keepit.common.json

import com.keepit.model.{ Feature, FeatureSetting, OrganizationRole }
import org.specs2.mutable.Specification
import play.api.libs.json.{ Json, Reads }

class JsonTest extends Specification {
  "json package" should {
    "robustly deserialize collections" in {
      "robustly handle js arrays" in {
        val myFormat = TraversableFormat.safeArrayReads[OrganizationRole]
        Json.arr("member", "admin", "garbage").as[Seq[OrganizationRole]](myFormat) === Seq(OrganizationRole.MEMBER, OrganizationRole.ADMIN)
      }
      "robustly handle simple js objects" in {
        val myFormat = TraversableFormat.safeObjectReads[OrganizationRole, Feature](OrganizationRole.reads, Feature.reads)
        val input = Json.obj("member" -> "view_organization", "garbage_role" -> "view_members", "admin" -> "garbage_feature")
        val expected = Map(OrganizationRole.MEMBER -> Feature.ViewOrganization)
        input.as[Map[OrganizationRole, Feature]](myFormat) === expected
      }
      "robustly handle complex js objects" in {
        def settingsReads(f: Feature): Reads[FeatureSetting] = f.settingReads
        val myFormat = TraversableFormat.safeConditionalObjectReads[Feature, FeatureSetting](Feature.reads, settingsReads)
        val input = Json.obj("view_organization" -> "members", "create_slack_integration" -> "anyone")
        val expected = Map(Feature.ViewOrganization -> FeatureSetting.MEMBERS)
        input.as[Map[Feature, FeatureSetting]](myFormat) === expected
      }
    }
  }

}
