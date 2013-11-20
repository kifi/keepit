package com.keepit.shoebox

import java.io._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.logging.Logging
import com.keepit.inject._
import com.keepit.model.PhraseRepo
import com.keepit.model.{Phrase => PhraseModel}

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import play.api.Play.current

import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.search.Lang

object PhraseImporter {
  private var inProgress = false
  def startImport(implicit session: RWSession) {
    val st = session.conn.createStatement()
    val sql = """DROP INDEX follow_i_phrase_lang_state on phrase;"""
    st.executeQuery(sql)
    inProgress = true
  }
  def endImport(implicit session: RWSession) {
    val st = session.conn.createStatement()
    val sql =
      """CREATE INDEX follow_i_phrase_lang_state on phrase (phrase, lang, state);"""
    st.executeQuery(sql)
    inProgress = false
  }
  def isInProgress = inProgress
}

trait PhraseMessage
case class ImportFile(file: File) extends PhraseMessage
case object StartImport extends PhraseMessage
case object EndImport extends PhraseMessage

private class PhraseImporterActor @Inject() (
    airbrake: AirbrakeNotifier,
    dbConnection: Database,
    phraseRepo: PhraseRepo)
  extends FortyTwoActor(airbrake) with Logging {

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
    case m => throw new UnsupportedActorMessage(m)
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

class PhraseImporterImpl @Inject()(actor: ActorInstance[PhraseImporterActor]) extends PhraseImporter {
  def importFile(file: File): Unit = {
    actor.ref ! ImportFile(file)
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
    val result = removeParenths(phrase.replaceAll("_"," "),("(",")"))
    if(result.split(" ").size <= 1) None
    else Some(result.trim)
  }
}

class WikipediaFileImport {
  def importFile(infile: String) = {
    import scala.io.Source
    import java.io._
    val writer = new PrintWriter(new File(infile + "-clean"))
    val formatter = new WikipediaFormatter
    val sqlline = """INSERT INTO phrase (created_at, updated_at, phrase, source, lang, state) VALUES (NOW(), NOW(), '%s', 'wikipedia', 'en', 'active');"""
    Source.fromFile(infile).getLines.foreach { line =>
      val r = formatter.format(line)
      if(r.isDefined) {
        try {
          val sanitized = r.get.replaceAll("""\\""","""\\\\""").replaceAll("""'""","""\\'""").replaceAll(""""""","""\\"""").replaceAll("""%""","""\\%""").trim
          if(sanitized.length > 5) {
            writer.println(sqlline.format(sanitized))
          }
        } catch {
          case ex: Throwable => println(ex + "\t" + r.get)
        }
      }
    }
    writer.close
  }

}
