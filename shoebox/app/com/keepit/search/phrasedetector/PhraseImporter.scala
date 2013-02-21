package com.keepit.search.phrasedetector

import java.io.{IOException, FileReader, LineNumberReader, File}
import com.keepit.search.Lang
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.inject._
import com.keepit.model.PhraseRepo
import com.keepit.model.{Phrase => PhraseModel}
import akka.actor.Status.Failure
import akka.actor.{ActorSystem, Props, Actor}
import akka.dispatch.Future
import akka.pattern.ask
import akka.util.duration._
import play.api.libs.json.{JsArray, JsNumber, JsString, JsObject}

import com.google.inject.{Provider, ImplementedBy, Inject}
import com.keepit.common.analytics.{EventFamilies, Events, PersistEventPlugin}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBConnection
import com.keepit.common.logging.Logging
import com.keepit.common.time._

import play.api.Play.current
import com.keepit.common.db.slick.DBSession.RSession

object PhraseImporter {
  private var inProgress = false
  def startImport(implicit session: RSession) {
    val st = session.conn.createStatement()
    val sql = """DROP INDEX phrase_i_phrase(phrase) ON phrase;"""
    st.executeQuery(sql)
    inProgress = true
  }
  def endImport(implicit session: RSession) {
    val st = session.conn.createStatement()
    val sql = """CREATE INDEX phrase_i_phrase(phrase) ON phrase;"""
    st.executeQuery(sql)
    inProgress = false
  }
  def isInProgress = inProgress
}

trait PhraseMessage
case class ImportFile(file: File) extends PhraseMessage
case object StartImport extends PhraseMessage
case object EndImport extends PhraseMessage

private[phrasedetector] class PhraseImporterActor(dbConnection: DBConnection, phraseRepo: PhraseRepo) extends Actor with Logging {
  private val GROUP_SIZE = 500

  def receive = {
    case ImportFile(file) =>
      PhraseImporter.startImport
      try {
        importFromFile(file)
      }
      finally {
        PhraseImporter.endImport
      }
  }

  def importFromFile(file: File) {
    assert(PhraseImporter.isInProgress == true)
    val fileName = file.getName.split('-')
    assert(file.isFile)
    assert(fileName.length == 2)

    val (source, lang) = fileName match {
      case Array(source, lang) => (source, Lang(lang))
    }
    for(lineGroup <- io.Source.fromFile(file).getLines.grouped(GROUP_SIZE)) {
      val phrases = lineGroup.map { line =>
        PhraseModel(phrase = line.trim, source = source, lang = lang)
      }

      dbConnection.readWrite { implicit session =>
        phraseRepo.insertAll(phrases)
      }
    }
  }
}

@ImplementedBy(classOf[PhraseImporterImpl])
trait PhraseImporter {
  def importFile(file: File): Unit
}

class PhraseImporterImpl @Inject()(system: ActorSystem, db: DBConnection, phraseRepo: PhraseRepo,
                                   persistEventPlugin: PersistEventPlugin) extends PhraseImporter {
  private val actor = system.actorOf(Props { new PhraseImporterActor(db, phraseRepo) })

  def importFile(file: File): Unit = {
    actor ! ImportFile(file)
  }
}
