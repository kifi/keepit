package com.keepit.search.index

import java.io.Reader
import java.lang.reflect.Constructor
import org.apache.lucene.analysis.ar.ArabicNormalizationFilter
import org.apache.lucene.analysis.ar.ArabicStemFilter
import org.apache.lucene.analysis.bg.BulgarianStemFilter
import org.apache.lucene.analysis.cz.CzechStemFilter
import org.apache.lucene.analysis.de.GermanLightStemFilter
import org.apache.lucene.analysis.de.GermanNormalizationFilter
import org.apache.lucene.analysis.el.GreekLowerCaseFilter
import org.apache.lucene.analysis.el.GreekStemFilter
import org.apache.lucene.analysis.en.EnglishMinimalStemFilter
import org.apache.lucene.analysis.en.KStemFilter
import org.apache.lucene.analysis.es.SpanishLightStemFilter
import org.apache.lucene.analysis.fa.PersianCharFilter
import org.apache.lucene.analysis.fa.PersianNormalizationFilter
import org.apache.lucene.analysis.fi.FinnishLightStemFilter
import org.apache.lucene.analysis.util.ElisionFilter
import org.apache.lucene.analysis.fr.FrenchLightStemFilter
import org.apache.lucene.analysis.hi.HindiNormalizationFilter
import org.apache.lucene.analysis.hi.HindiStemFilter
import org.apache.lucene.analysis.hu.HungarianLightStemFilter
import org.apache.lucene.analysis.id.IndonesianStemFilter
import org.apache.lucene.analysis.in.IndicNormalizationFilter
import org.apache.lucene.analysis.it.ItalianLightStemFilter
import org.apache.lucene.analysis.lv.LatvianStemFilter
import org.apache.lucene.analysis.no.NorwegianLightStemFilter
import org.apache.lucene.analysis.pt.PortugueseLightStemFilter
import org.apache.lucene.analysis.ru.RussianLightStemFilter
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.sv.SwedishLightStemFilter
import org.apache.lucene.analysis.th.ThaiWordFilter
import org.apache.lucene.analysis.tr.TurkishLowerCaseFilter
import org.apache.lucene.analysis.{Analyzer=>LAnalyzer}
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents
import org.apache.lucene.analysis.CharFilter
import org.apache.lucene.analysis.core.LowerCaseFilter
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.Tokenizer
import org.apache.lucene.analysis.util.TokenizerFactory
import org.apache.lucene.util.Version
import com.keepit.common.logging.Logging
import com.keepit.search.Lang
import LuceneVersion.version
import scala.reflect.ClassTag
import java.io.Reader
import java.io.StringReader

object LuceneVersion {
  val version = Version.LUCENE_41
}

object DefaultAnalyzer {
  import LuceneVersion.version

  private val stdAnalyzer = new Analyzer(new DefaultTokenizerFactory, Nil, None)

  val defaultAnalyzer: Analyzer = stdAnalyzer.withFilter[LowerCaseFilter] // lower case, no stopwords

  val langAnalyzers = Map[String, Analyzer](
    "ar" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Arabic).withFilter[ArabicNormalizationFilter],
    "bg" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Bulgarian),
    "cs" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Czech),
    "da" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Danish),
    "de" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.German).withFilter[GermanNormalizationFilter],
    "el" -> stdAnalyzer.withFilter[GreekLowerCaseFilter].withStopFilter(_.Greek),
    "en" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.English),
    "es" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Spanish),
    "fa" -> stdAnalyzer.withCharFilter[PersianCharFilter].withFilter[LowerCaseFilter].withFilter[ArabicNormalizationFilter].withFilter[PersianNormalizationFilter].withStopFilter(_.Persian),
    "fi" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Finnish),
    "fr" -> stdAnalyzer.withFilter[FrenchElisionFilter].withFilter[LowerCaseFilter].withStopFilter(_.French),
    "hi" -> stdAnalyzer.withFilter[LowerCaseFilter].withFilter[IndicNormalizationFilter].withFilter[HindiNormalizationFilter].withStopFilter(_.Hindi),
    "hu" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Hungarian),
    "id" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Indonesian),
    "it" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Italian),
    "lv" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Latvian),
    "nl" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Dutch),
    "no" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Norwegian),
    "pt" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Portuguese),
    "ro" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Romanian),
    "ru" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Russian),
    "sv" -> stdAnalyzer.withFilter[LowerCaseFilter].withStopFilter(_.Swedish),
    "th" -> stdAnalyzer.withFilter[LowerCaseFilter].withFilter[ThaiWordFilter].withStopFilter(_.Thai),
    "tr" -> stdAnalyzer.withFilter[TurkishLowerCaseFilter].withStopFilter(_.Turkish)
  )

  val langAnalyzerWithStemmer = Map[String, Analyzer](
    "ar" -> langAnalyzers("ar").withFilter[ArabicStemFilter],
    "bg" -> langAnalyzers("bg").withFilter[BulgarianStemFilter],
    "cs" -> langAnalyzers("cs").withFilter[CzechStemFilter],
    "da" -> langAnalyzers("da").withStemFilter(_.Danish),
    "de" -> langAnalyzers("de").withFilter[GermanLightStemFilter],
    "el" -> langAnalyzers("el").withFilter[GreekStemFilter],
    "en" -> langAnalyzers("en").withFilter[KStemFilter].withFilter[EnglishMinimalStemFilter].withFilter[ApostropheFilter],
    "es" -> langAnalyzers("es").withFilter[SpanishLightStemFilter],
    "fi" -> langAnalyzers("fi").withFilter[FinnishLightStemFilter],
    "fr" -> langAnalyzers("fr").withFilter[FrenchLightStemFilter],
    "hi" -> langAnalyzers("hi").withFilter[HindiStemFilter],
    "hu" -> langAnalyzers("hu").withFilter[HungarianLightStemFilter],
    "id" -> langAnalyzers("id").withFilter[IndonesianStemFilter],
    "it" -> langAnalyzers("it").withFilter[ItalianLightStemFilter],
    "lv" -> langAnalyzers("lv").withFilter[LatvianStemFilter],
    "nl" -> langAnalyzers("nl").withStemFilter(_.Dutch),
    "no" -> langAnalyzers("no").withFilter[NorwegianLightStemFilter],
    "pt" -> langAnalyzers("pt").withFilter[PortugueseLightStemFilter],
    "ro" -> langAnalyzers("ro").withStemFilter(_.Romanian),
    "ru" -> langAnalyzers("ru").withFilter[RussianLightStemFilter],
    "sv" -> langAnalyzers("sv").withFilter[SwedishLightStemFilter],
    "tr" -> langAnalyzers("tr").withStemFilter(_.Turkish)
  )

  private def getAnalyzer(lang: Lang): Analyzer = langAnalyzers.getOrElse(lang.lang, defaultAnalyzer)
  private def getAnalyzerWithStemmer(lang: Lang): Option[Analyzer] = langAnalyzerWithStemmer.get(lang.lang)

  def forIndexing: Analyzer = forIndexing(Lang("en"))
  def forIndexing(lang: Lang): Analyzer = getAnalyzer(lang).withFilter[SymbolDecompounder]

  def forIndexingWithStemmer: Analyzer = forIndexingWithStemmer(Lang("en"))
  def forIndexingWithStemmer(lang: Lang): Analyzer = getAnalyzerWithStemmer(lang).getOrElse(forIndexing(lang))

  def forParsing: Analyzer = forParsing(Lang("en"))
  def forParsing(lang: Lang): Analyzer = getAnalyzer(lang)

  def forParsingWithStemmer: Analyzer = forParsingWithStemmer(Lang("en"))
  def forParsingWithStemmer(lang: Lang): Analyzer = getAnalyzerWithStemmer(lang).getOrElse(forParsing(lang))
}

class DefaultTokenizerFactory extends TokenizerFactory {
  override def create(reader: Reader): Tokenizer = {
    var tokenizer = new StandardTokenizer(version, reader)
    tokenizer.setMaxTokenLength(256)
    tokenizer
  }
}

class Analyzer(tokenizerFactory: TokenizerFactory,
               factories: List[TokenFilterFactory],
               charFilterConstructor: Option[Constructor[CharFilter]]) extends LAnalyzer with Logging {
  import LuceneVersion.version

  def withFilter[T <: TokenFilter](implicit m : ClassTag[T]): Analyzer = {
    try {
      val constructor = m.runtimeClass.getConstructor(classOf[Version], classOf[TokenStream]).asInstanceOf[Constructor[TokenStream]]
      withFilter(WrapperTokenFilterFactory(constructor, version))
    } catch {
      case ex: NoSuchMethodException =>
        try {
          val constructor = m.runtimeClass.getConstructor(classOf[TokenStream]).asInstanceOf[Constructor[TokenStream]]
          withFilter(WrapperTokenFilterFactory(constructor))
        } catch {
          case ex: NoSuchMethodException => log.error("failed to find a filter constructor: %s".format(m.runtimeClass.toString))
          this
        }
    }
  }

  def withStopFilter(f: StopFilterFactories=>TokenFilterFactory): Analyzer = withFilter(f(TokenFilterFactories.stopFilter))
  def withStemFilter(f: StemFilterFactories=>TokenFilterFactory): Analyzer = withFilter(f(TokenFilterFactories.stemFilter))

  def withFilter(factory: TokenFilterFactory): Analyzer = new Analyzer(tokenizerFactory, factory::factories, charFilterConstructor)

  def withCharFilter[T <: CharFilter](implicit m : ClassTag[T]): Analyzer = {
    val constructor = m.runtimeClass.getConstructor(classOf[Reader]).asInstanceOf[Constructor[CharFilter]]
    new Analyzer(tokenizerFactory, factories, Some(constructor))
  }

  override protected def initReader(fieldName: String, reader: Reader): Reader = {
    charFilterConstructor match {
      case Some(charFilterConstructor) => charFilterConstructor.newInstance(reader)
      case None => reader
    }
  }

  override def createComponents(fieldName: String, reader: Reader): TokenStreamComponents = {
    val filters = factories.reverse
    val tokenizer = tokenizerFactory.create(reader)
    val tokenStream = filters.foldLeft(tokenizer.asInstanceOf[TokenStream]){ (tokenStream, filter) => filter(tokenStream) }
    new TokenStreamComponents(tokenizer, tokenStream)
  }

  def createLazyTokenStream(field: String, text: String) = new LazyTokenStream(field, text, this)
}

class LazyTokenStream(field: String, text: String, analyzer: Analyzer) extends TokenStream {
  private[this] val termAttr = addAttribute(classOf[CharTermAttribute])
  private[this] val posIncrAttr = addAttribute(classOf[PositionIncrementAttribute])

  private[this] var baseTokenStream: TokenStream = null
  private[this] var baseTermAttr: CharTermAttribute = null
  private[this] var basePosIncrAttr: PositionIncrementAttribute = null

  override def incrementToken(): Boolean = {
    if (baseTokenStream == null) {
      baseTokenStream = analyzer.tokenStream(field, new StringReader(text))
      baseTokenStream.reset()
      baseTermAttr = baseTokenStream.getAttribute(classOf[CharTermAttribute])
      if (baseTokenStream.hasAttribute(classOf[PositionIncrementAttribute]))
        basePosIncrAttr = baseTokenStream.getAttribute(classOf[PositionIncrementAttribute])
    }
    val more = baseTokenStream.incrementToken()
    if (more) {
      termAttr.setEmpty
      termAttr.append(baseTermAttr)
      if (basePosIncrAttr != null) posIncrAttr.setPositionIncrement(basePosIncrAttr.getPositionIncrement)
    }
    more
  }
  override def reset() {
    if (baseTokenStream != null) baseTokenStream.reset()
  }
  override def close() {
    if (baseTokenStream != null) baseTokenStream.close()
  }
}
