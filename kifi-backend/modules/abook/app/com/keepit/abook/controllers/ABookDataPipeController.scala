package com.keepit.abook.controllers

import com.keepit.common.controller.ABookServiceController
import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.abook.model.{ EContact, EmailAccount, EContactRepo, EmailAccountRepo }
import com.keepit.common.db.SequenceNumber
import play.api.libs.json.Json
import play.api.mvc.Action

class ABookDataPipeController @Inject() (
    db: Database,
    emailAccountRepo: EmailAccountRepo,
    contactRepo: EContactRepo) extends ABookServiceController {

  def getEmailAccountsChanged(seqNum: SequenceNumber[EmailAccount], fetchSize: Int) = Action { request =>
    val emailAccounts = db.readOnlyReplica(2) { implicit s =>
      emailAccountRepo.getBySequenceNumber(seqNum, fetchSize)
    }
    val ingestables = emailAccounts.map(EmailAccount.toIngestable)
    Ok(Json.toJson(ingestables))
  }

  def getContactsChanged(seqNum: SequenceNumber[EContact], fetchSize: Int) = Action { request =>
    val emailAccounts = db.readOnlyReplica(2) { implicit s =>
      contactRepo.getBySequenceNumber(seqNum, fetchSize)
    }
    val ingestables = emailAccounts.map(EContact.toIngestable)
    Ok(Json.toJson(ingestables))
  }
}
