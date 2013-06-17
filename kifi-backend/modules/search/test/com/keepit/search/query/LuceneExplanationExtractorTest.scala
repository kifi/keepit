package com.keepit.search.query

import org.specs2.mutable.Specification
import org.apache.lucene.search.ComplexExplanation

class LuceneExplanationExtractorTest extends Specification {

  "LuceneExplanationExtractor" should {
    "correctly extract Lucene Explanation scores" in {
      var multi = new ComplexExplanation()
      multi.setValue(1.2f); multi.setDescription("multiplicative boost, product of")

      var additative = new ComplexExplanation()
      additative.setValue(1.8f); additative.setDescription(" additive boost, product of")

      var percent = new ComplexExplanation()
      percent.setValue(0.8f); percent.setDescription(" percentMatch(100.0% = 49.476944/49.476944), sum of")

      var weight = new ComplexExplanation()
      weight.setValue(0.2f); weight.setDescription("weight(t:machine in 2050) [anon$2], result of")

      var sv = new ComplexExplanation()
      sv.setValue(0.7f); sv.setDescription("semantic vector (sv:translate,sv:translation,sv:machine), sum of")

      var phraseProx = new ComplexExplanation()
      phraseProx.setValue(0.3f); phraseProx.setDescription("phrase proximity(cs:machine,cs:translate), product of")

      var phraseProx2 = new ComplexExplanation()
      phraseProx2.setValue(1.3f); phraseProx2.setDescription("phrase proximity(cs:machine,cs:translate), product of")    // score overwrites the previous one

      additative.addDetail(percent)
      additative.addDetail(weight)

      multi.addDetail(additative)
      multi.addDetail(phraseProx)
      multi.addDetail(phraseProx2)
      multi.addDetail(sv)

      multi.getDetails().size === 4
      additative.getDetails().size === 2

      val m = LuceneExplanationExtractor.extractNamedScores(multi)
      m("multiplicative boost") === 1.2f
      m("additive boost") === 1.8f
      m("percentMatch") === 0.8f
      m("semantic vector") === 0.7f
      m("phrase proximity") === 1.3f
      m.getOrElse("weight", -1.0f) === -1.0f
    }
  }

}
