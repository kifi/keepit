package com.keepit.normalizer

import org.specs2.mutable.Specification
import com.keepit.model.Normalization
import com.keepit.common.net.URI

class SchemeNormalizerTest extends Specification {

  def parseAndNormalize(normalizer: URINormalizer, url: String) = URI.safelyParse(url).map(normalizer).flatMap(_.safelyToString()).get

  "SchemeNormalizer" should {

    "refuse to normalize to canonical or og" in {
      SchemeNormalizer(Normalization.CANONICAL) should throwA(new IllegalArgumentException("requirement failed"))
      SchemeNormalizer(Normalization.OPENGRAPH) should throwA(new IllegalArgumentException("requirement failed"))
    }

    "normalize to https://" in {
      val normalizer = SchemeNormalizer(Normalization.HTTPS)
      parseAndNormalize(normalizer, "http://www.imdb.com/title/tt0081505/") === "https://imdb.com/title/tt0081505/"
      parseAndNormalize(normalizer, "http://imdb.com/title/tt0066921/") === "https://imdb.com/title/tt0066921/"
      parseAndNormalize(normalizer, "http://m.imdb.com/title/tt0057012/") === "https://imdb.com/title/tt0057012/"
      parseAndNormalize(normalizer, "https://secure.imdb.com/title/tt0062622/") === "https://secure.imdb.com/title/tt0062622/"
    }
    
    "normalize to https://www" in {
      val normalizer = SchemeNormalizer(Normalization.HTTPSWWW)
      parseAndNormalize(normalizer, "http://www.imdb.com/title/tt0081505/") === "https://www.imdb.com/title/tt0081505/"
      parseAndNormalize(normalizer, "http://imdb.com/title/tt0066921/") === "https://www.imdb.com/title/tt0066921/"
      parseAndNormalize(normalizer, "http://m.imdb.com/title/tt0057012/") === "https://www.imdb.com/title/tt0057012/"
      parseAndNormalize(normalizer, "https://secure.imdb.com/title/tt0062622/") === "https://www.secure.imdb.com/title/tt0062622/"
    }

    "normalize to http://" in {
      val normalizer = SchemeNormalizer(Normalization.HTTP)
      parseAndNormalize(normalizer, "http://www.imdb.com/title/tt0081505/") === "http://imdb.com/title/tt0081505/"
      parseAndNormalize(normalizer, "http://imdb.com/title/tt0066921/") === "http://imdb.com/title/tt0066921/"
      parseAndNormalize(normalizer, "http://m.imdb.com/title/tt0057012/") === "http://imdb.com/title/tt0057012/"
      parseAndNormalize(normalizer, "https://secure.imdb.com/title/tt0062622/") === "http://secure.imdb.com/title/tt0062622/"
    }

    "normalize to http://" in {
      val normalizer = SchemeNormalizer(Normalization.HTTPWWW)
      parseAndNormalize(normalizer, "http://www.imdb.com/title/tt0081505/") === "http://www.imdb.com/title/tt0081505/"
      parseAndNormalize(normalizer, "http://imdb.com/title/tt0066921/") === "http://www.imdb.com/title/tt0066921/"
      parseAndNormalize(normalizer, "http://m.imdb.com/title/tt0057012/") === "http://www.imdb.com/title/tt0057012/"
      parseAndNormalize(normalizer, "https://secure.imdb.com/title/tt0062622/") === "http://www.secure.imdb.com/title/tt0062622/"
    }

    "normalize to https://m" in {
      val normalizer = SchemeNormalizer(Normalization.HTTPSM)
      parseAndNormalize(normalizer, "http://www.imdb.com/title/tt0081505/") === "https://m.imdb.com/title/tt0081505/"
      parseAndNormalize(normalizer, "http://imdb.com/title/tt0066921/") === "https://m.imdb.com/title/tt0066921/"
      parseAndNormalize(normalizer, "http://m.imdb.com/title/tt0057012/") === "https://m.imdb.com/title/tt0057012/"
      parseAndNormalize(normalizer, "https://secure.imdb.com/title/tt0062622/") === "https://m.secure.imdb.com/title/tt0062622/"
    }

    "normalize to https://m" in {
      val normalizer = SchemeNormalizer(Normalization.HTTPM)
      parseAndNormalize(normalizer, "http://www.imdb.com/title/tt0081505/") === "http://m.imdb.com/title/tt0081505/"
      parseAndNormalize(normalizer, "http://imdb.com/title/tt0066921/") === "http://m.imdb.com/title/tt0066921/"
      parseAndNormalize(normalizer, "http://m.imdb.com/title/tt0057012/") === "http://m.imdb.com/title/tt0057012/"
      parseAndNormalize(normalizer, "https://secure.imdb.com/title/tt0062622/") === "http://m.secure.imdb.com/title/tt0062622/"
    }
  }
}
