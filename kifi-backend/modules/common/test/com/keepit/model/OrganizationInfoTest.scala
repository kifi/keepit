package com.keepit.model

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.common.store.ImagePath
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class OrganizationInfoTest extends Specification {

  "OrganizationInfo" should {
    "format basic organization views correctly" in {
      val orgId = PublicId[Organization]("asdf")
      val userId = ExternalId[User]()
      val orgHandle = OrganizationHandle("my basic org")
      val orgName = "My Basic Org"
      val orgDescription = Some("my description")
      val avatarPath = ImagePath("my image path")
      val basicOrg = BasicOrganization(orgId, userId, orgHandle, orgName, orgDescription, avatarPath)
      val viewerInfo = OrganizationViewerInfo(None, Set.empty, None)
      val basicOrgView = BasicOrganizationView(basicOrg, viewerInfo)
      Json.toJson(basicOrgView) === Json.obj("id" -> orgId, "ownerId" -> userId, "handle" -> orgHandle, "name" -> orgName, "description" -> orgDescription, "avatarPath" -> avatarPath, "viewer" -> viewerInfo)
    }
  }

}
