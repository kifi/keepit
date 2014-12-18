package com.keepit.scraper.embedly

import com.keepit.common.strings.UTF8
import java.io.File
import org.specs2.mutable.Specification
import play.api.libs.json._

class EmbedlyTest extends Specification {
  "Embedly json" should {
    "correctly parsed" in {
      val json = Json.parse(io.Source.fromFile(new File("test/data/sample_embedly.json"), UTF8).mkString)
      val extEmbInfo = json.validate[EmbedlyInfo].get
      extEmbInfo.lang === Some("English")
      extEmbInfo.keywords.map { key => (key.score, key.name) }.take(3) === Seq((120, "oneplus"), (66, "devices"), (53, "nexus"))
      extEmbInfo.entities.map { ent => (ent.count, ent.name) }.take(2) === Seq((4, "Google"), (1, "RAM"))
    }
  }
}
