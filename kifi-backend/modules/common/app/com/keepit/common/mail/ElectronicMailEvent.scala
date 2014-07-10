package com.keepit.common.mail

import com.keepit.common.db.Id

case class ElectronicMailEvent(
  id: Option[Id[ElectronicMailEvent]],
  mailId: Option[Id[ElectronicMail]])