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

import com.keepit.common.db.slick.DBSession.{RWSession, RSession}

object PhraseImporter {
  private var inProgress = false
  def startImport(implicit session: RWSession) {
    val st = session.conn.createStatement()
    val sql = """DROP INDEX phrase_i_phrase(phrase) ON phrase;"""
    st.executeQuery(sql)
    inProgress = true
  }
  def endImport(implicit session: RWSession) {
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
      dbConnection.readWrite(implicit s => PhraseImporter.startImport)
      try {
        importFromFile(file)
      }
      finally {
        dbConnection.readWrite(implicit s => PhraseImporter.endImport)
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

trait PhraseFormatter
class WikipediaFormatter extends PhraseFormatter {
  def format(phrase: String) = {
    def removeParenths(str: String, groupingSymbol: (String, String)) = {
      val begin = str.indexOf(groupingSymbol._1)
      val end = str.indexOf(groupingSymbol._2, begin)
      if(begin>=0 && end>0) str.substring(0,begin) + str.substring(end+1)
      else str
    }
    removeParenths(phrase.replaceAll("_"," "),("(",")")).trim
  }
}
