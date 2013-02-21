package com.keepit.search.phrasedetector

import java.io.{IOException, FileReader, LineNumberReader, File}
import com.keepit.search.Lang
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.inject._
import com.keepit.model.PhraseRepoImpl
import com.keepit.model.{Phrase => PhraseModel}
import com.keepit.common.db.slick.DBConnection

import play.api.Play.current

class PhraseImporter(source: String, dataDirectory: Option[File]) extends Logging {
  def importFromFile() {
    dataDirectory.foreach{ dir =>
      var id = -1
      if (dir.exists) {
        log.info("loading phrases from: %s".format(dir.toString))
        val phraseRepo = inject[PhraseRepoImpl]
        dir.listFiles.map { file =>
          val lang = Lang(file.getName)
          val reader = new LineNumberReader(new FileReader(file))
          for {
            file <- dir.listFiles.toIterator if file.isFile
            lineGroup <- io.Source.fromFile(file).getLines.grouped(500)
          } {
            val lang = Lang(file.getName)
            inject[DBConnection].readWrite { implicit session =>
              lineGroup.foreach { line =>
                phraseRepo.save(PhraseModel(phrase = line, source = source, lang = lang))
              }
            }
          }
        }
      }
    }
  }
}
