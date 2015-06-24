package com.keepit.model

import java.net.URLEncoder

import com.keepit.common.strings._
import com.kifi.macros.json

@json
case class Handle(value: String) extends AnyVal {
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
  override def toString() = value
}

object Handle {
  implicit def fromUsername(username: Username) = Handle(username.value)
  implicit def fromOrganizationHandle(organizationHandle: OrganizationHandle) = Handle(organizationHandle.value)
  implicit def fromUserOrgOpt(tuple: (Username, Option[OrganizationHandle])): Handle = {
    tuple match {
      case (_, Some(handle)) => handle
      case (user, None) => user
    }
  }
}
