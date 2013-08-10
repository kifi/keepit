package com.keepit.controllers.shoebox

import com.keepit.model.{CommentRepo, Comment}
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id
import com.keepit.eliza.ElizaServiceClient

import play.api.libs.json.{Json, JsObject}
import play.api.mvc.Action

import com.google.inject.Inject


class ShoeboxChatMigrationController @Inject() (
    commentRepo: CommentRepo,
    db: Database,
    eliza: ElizaServiceClient
  )   
  extends ShoeboxServiceController  {


  def migrateToEliza() = Action {
    log.warn("MIGRATE: Starting migration to Eliza!")
    val threads = getThreads()
    log.warn(s"MIGRATE: Got ${threads.length} threads to migrate.")
    var i : Int = 0
    threads.foreach{ thread =>
      eliza.importThread(thread)
      i = i + 1
      log.warn(s"MIGRATE: Migrated thread $i out of ${threads.length}")
    }
    Ok("")
  }


  def getThreads() : Seq[JsObject]= {

    val rootMsgs = db.readOnly{ implicit session => commentRepo.getAllRootMessages() }
    rootMsgs.map{ comment =>
      Json.obj(
        "uriId" -> comment.uriId.id,
        "participants" -> db.readOnly{ implicit session => commentRepo.getParticipantsUserIds(comment.id.get).map(_.id) },
        "extId" -> comment.externalId, 
        "messages" -> getMessages(comment.id.get)
      )
    }
  }

  def getMessages(threadId: Id[Comment]) : Seq[JsObject] = {
    val comments = db.readOnly { implicit session => commentRepo.getChildren(threadId) }
    comments.map{ comment =>
      Json.obj(
        "from" -> comment.userId.id,
        "created_at" -> comment.createdAt,
        "text" -> comment.text
      )
    }
  }



}
