package com.keepit.commanders

import com.google.inject.ImplementedBy
import com.keepit.common.db.Id
import com.keepit.common.store.ImageSize
import com.keepit.model.{ Organization, OrganizationAvatar }

@ImplementedBy(classOf[OrganizationAvatarCommanderImpl])
trait OrganizationAvatarCommander {
  def getBestImage(orgId: Id[Organization], imageSize: ImageSize): Option[OrganizationAvatar]
}

class OrganizationAvatarCommanderImpl extends OrganizationAvatarCommander {
  def getBestImage(orgId: Id[Organization], imageSize: ImageSize): Option[OrganizationAvatar] = {
    None // TODO: do this.
  }
}
