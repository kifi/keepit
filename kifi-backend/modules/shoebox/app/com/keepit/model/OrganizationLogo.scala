package com.keepit.model

import com.keepit.common.db.{States, ModelWithState, State, Id}
import com.keepit.common.time._
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import play.api.libs.json.JsObject

case class OrganizationLogo(
                        id: Option[Id[OrganizationLogo]] = None,
                        createdAt: DateTime = currentDateTime,
                        updatedAt: DateTime = currentDateTime,
                        organizationId: Id[Organization],
                        uploadedBy: Id[User],
                        name: String, // the filename of the picture stored in S3, no extension (all are .jpg)
                        origin: OrganizationLogoSource, // this should really only be user_upload for an organization.
                        state: State[OrganizationLogo] = OrganizationLogoStates.ACTIVE,
                        attributes: Option[JsObject]) extends ModelWithState[OrganizationLogo] {
  def withId(id: Id[OrganizationLogo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[OrganizationLogo]) = copy(state = state)
}

object OrganizationLogo {
  def generateNewFilename: String = RandomStringUtils.randomAlphanumeric(5)
}

object OrganizationLogoStates extends States[OrganizationLogo]

case class OrganizationLogoSource(name: String)
object OrganizationLogoSource {
  val FACEBOOK = UserPictureSource("facebook")
  val LINKEDIN = UserPictureSource("linkedin")
  val USER_UPLOAD = UserPictureSource("user_upload")
}