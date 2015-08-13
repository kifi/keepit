package com.keepit.notify.info

import com.keepit.common.path.Path
import play.api.libs.json.JsObject

case class NotificationInfo(
  path: Path,
  imageUrl: String,
  title: String,
  body: String,
  linkText: String,
  extraJson: Option[JsObject] = None)
