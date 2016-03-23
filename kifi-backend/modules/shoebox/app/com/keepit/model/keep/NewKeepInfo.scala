package com.keepit.model.keep

import com.keepit.common.crypto.PublicId
import com.keepit.model.{ NormalizedURI, KeepPermission, Keep }

case class NewKeepInfo(
  id: PublicId[Keep])

case class NewPageInfo(
  id: PublicId[NormalizedURI])

case class NewKeepViewerInfo(
  permissions: Set[KeepPermission])

case class NewKeepView(
  keep: NewKeepInfo,
  viewer: NewKeepViewerInfo)

