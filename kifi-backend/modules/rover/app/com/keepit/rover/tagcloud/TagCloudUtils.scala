package com.keepit.rover.tagcloud

import com.keepit.common.queue.messages.SuggestedSearchTerms
import com.keepit.rover.article.{ Article, EmbedlyArticle }
import com.keepit.rover.article.content.{ EmbedlyContent, ArticleContentExtractor }
import scala.collection.mutable

object TagCloudGenerator {

  import WordCountHelper.{ wordCount, topKcounts }
  import NGramHelper._

  val TOP_K = 50

  def generate(corpus: LibraryCorpus): SuggestedSearchTerms = {
    val (keywordCnts, entityCnts, docs) = extractFromCorpus(corpus)
    val (twoGrams, threeGrams) = (NGramHelper.generateNGramsForCorpus(docs, 2), NGramHelper.generateNGramsForCorpus(docs, 3))
    val multiGrams = combineGrams(twoGrams, threeGrams)
    val multiGramsIndex = buildMultiGramIndex(multiGrams.keySet)
    val topKeywords = topKcounts(keywordCnts, TOP_K)

    val oneGramEntities: WordCounts = (topKeywords.keySet.intersect(entityCnts.keySet)).map { k => k -> (topKeywords(k) max entityCnts(k)) }.toMap

    val keyGrams: WordCounts = topKeywords.keys.map { key =>
      multiGramsIndex.getOrElse(key, Set()).map { gram => (gram, multiGrams(gram)) }.take(3) // find superString that contain the one-gram keyword, and return freq with them
    }.flatten.toMap

    val result = topKcounts(oneGramEntities ++ keyGrams, TOP_K)
    SuggestedSearchTerms(result.map { case (w, c) => (w, c * 1f) })
  }

  // return: EmbedlyKeywords, EmbedlyEnitites, ArticleContents
  def extractFromCorpus(corpus: LibraryCorpus): (WordCounts, WordCounts, Seq[String]) = {
    val (keywords, entities, contents) = corpus.articles.values.map { articles =>
      val extractor = ArticleContentExtractor(articles)
      val embedlyArticle: Option[EmbedlyContent] = extractor.getByKind(EmbedlyArticle).asInstanceOf[Option[EmbedlyContent]]
      val keywords = embedlyArticle.map { _.keywords }.getOrElse(Seq())
      val entities = embedlyArticle.map { _.entities }.getOrElse(Seq()).map { _.name.toLowerCase }
      val content = extractor.content.getOrElse("").toLowerCase
      (keywords, entities, content)
    }.unzip3

    (wordCount(keywords.flatten), wordCount(entities.flatten), contents.toSeq)
  }

}

object WordCountHelper {
  type WordCounts = Map[String, Int]

  def wordCount(tokenStream: Iterable[String]): WordCounts = {
    val wc = mutable.Map[String, Int]().withDefaultValue(0)
    tokenStream.foreach { word =>
      val key = word.toLowerCase
      wc(key) = wc(key) + 1
    }
    wc.toMap
  }

  def topKcounts(wordCounts: WordCounts, topK: Int): WordCounts = {
    wordCounts.toArray.sortBy(-_._2).take(topK).toMap
  }
}

object NGramHelper {
  val punct = """[!"#$%&'()*\+\,\-\.\/:;<=>?@\[\]^_`\{\|\}~]"""
  val space = """\s"""
  // 744 common stopwords (partly from MySQL)
  val stopwords: Set[String] = Set("a", "a\'s", "able", "about", "above", "according", "accordingly", "across", "actually", "after", "afterwards", "again", "against", "ain\'t", "all", "allow", "allows", "almost", "alone", "along", "already", "also", "although", "always", "am", "among", "amongst", "an", "and", "another", "any", "anybody", "anyhow", "anyone", "anything", "anyway", "anyways", "anywhere", "apart", "appear", "appreciate", "appropriate", "are", "area", "areas", "aren\'t", "around", "as", "aside", "ask", "asked", "asking", "asks", "associated", "at", "available", "away", "awfully", "b", "back", "backed", "backing", "backs", "be", "became", "because", "become", "becomes", "becoming", "been", "before", "beforehand", "began", "behind", "being", "beings", "believe", "below", "beside", "besides", "best", "better", "between", "beyond", "big", "both", "brief", "but", "by", "c", "c\'mon", "c\'s", "came", "can", "can\'t", "cannot", "cant", "case", "cases", "cause", "causes", "certain", "certainly", "changes", "clear", "clearly", "co", "com", "come", "comes", "concerning", "consequently", "consider", "considering", "contain", "containing", "contains", "corresponding", "could", "couldn\'t", "course", "currently", "d", "definitely", "described", "despite", "did", "didn\'t", "differ", "different", "differently", "do", "does", "doesn\'t", "doing", "don\'t", "done", "down", "downed", "downing", "downs", "downwards", "during", "e", "each", "early", "edu", "eg", "eight", "either", "else", "elsewhere", "end", "ended", "ending", "ends", "enough", "entirely", "especially", "et", "etc", "even", "evenly", "ever", "every", "everybody", "everyone", "everything", "everywhere", "ex", "exactly", "example", "except", "f", "face", "faces", "fact", "facts", "far", "felt", "few", "fifth", "find", "finds", "first", "five", "followed", "following", "follows", "for", "former", "formerly", "forth", "four", "from", "full", "fully", "further", "furthered", "furthering", "furthermore", "furthers", "g", "gave", "general", "generally", "get", "gets", "getting", "give", "given", "gives", "go", "goes", "going", "gone", "good", "goods", "got", "gotten", "great", "greater", "greatest", "greetings", "group", "grouped", "grouping", "groups", "h", "had", "hadn\'t", "happens", "hardly", "has", "hasn\'t", "have", "haven\'t", "having", "he", "he\'s", "hello", "help", "hence", "her", "here", "here\'s", "hereafter", "hereby", "herein", "hereupon", "hers", "herself", "hi", "high", "higher", "highest", "him", "himself", "his", "hither", "hopefully", "how", "howbeit", "however", "i", "i\'d", "i\'ll", "i\'m", "i\'ve", "ie", "if", "ignored", "immediate", "important", "in", "inasmuch", "inc", "indeed", "indicate", "indicated", "indicates", "inner", "insofar", "instead", "interest", "interested", "interesting", "interests", "into", "inward", "is", "isn\'t", "it", "it\'d", "it\'ll", "it\'s", "its", "itself", "j", "just", "k", "keep", "keeps", "kept", "kind", "knew", "know", "known", "knows", "l", "large", "largely", "last", "lately", "later", "latest", "latter", "latterly", "least", "less", "lest", "let", "let\'s", "lets", "like", "liked", "likely", "little", "long", "longer", "longest", "look", "looking", "looks", "ltd", "m", "made", "mainly", "make", "making", "man", "many", "may", "maybe", "me", "mean", "meanwhile", "member", "members", "men", "merely", "might", "more", "moreover", "most", "mostly", "mr", "mrs", "much", "must", "my", "myself", "n", "name", "namely", "nd", "near", "nearly", "necessary", "need", "needed", "needing", "needs", "neither", "never", "nevertheless", "new", "newer", "newest", "next", "nine", "no", "nobody", "non", "none", "noone", "nor", "normally", "not", "nothing", "novel", "now", "nowhere", "number", "numbers", "o", "obviously", "of", "off", "often", "oh", "ok", "okay", "old", "older", "oldest", "on", "once", "one", "ones", "only", "onto", "open", "opened", "opening", "opens", "or", "order", "ordered", "ordering", "orders", "other", "others", "otherwise", "ought", "our", "ours", "ourselves", "out", "outside", "over", "overall", "own", "p", "part", "parted", "particular", "particularly", "parting", "parts", "per", "perhaps", "place", "placed", "places", "please", "plus", "point", "pointed", "pointing", "points", "possible", "present", "presented", "presenting", "presents", "presumably", "probably", "problem", "problems", "provides", "put", "puts", "q", "que", "quite", "qv", "r", "rather", "rd", "re", "really", "reasonably", "regarding", "regardless", "regards", "relatively", "respectively", "right", "room", "rooms", "s", "said", "same", "saw", "say", "saying", "says", "second", "secondly", "seconds", "see", "seeing", "seem", "seemed", "seeming", "seems", "seen", "sees", "self", "selves", "sensible", "sent", "serious", "seriously", "seven", "several", "shall", "she", "should", "shouldn\'t", "show", "showed", "showing", "shows", "side", "sides", "since", "six", "small", "smaller", "smallest", "so", "some", "somebody", "somehow", "someone", "something", "sometime", "sometimes", "somewhat", "somewhere", "soon", "sorry", "specified", "specify", "specifying", "state", "states", "still", "sub", "such", "sup", "sure", "t", "t\'s", "take", "taken", "tell", "tends", "th", "than", "thank", "thanks", "thanx", "that", "that\'s", "thats", "the", "their", "theirs", "them", "themselves", "then", "thence", "there", "there\'s", "thereafter", "thereby", "therefore", "therein", "theres", "thereupon", "these", "they", "they\'d", "they\'ll", "they\'re", "they\'ve", "thing", "things", "think", "thinks", "third", "this", "thorough", "thoroughly", "those", "though", "thought", "thoughts", "three", "through", "throughout", "thru", "thus", "to", "today", "together", "too", "took", "toward", "towards", "tried", "tries", "truly", "try", "trying", "turn", "turned", "turning", "turns", "twice", "two", "u", "un", "under", "unfortunately", "unless", "unlikely", "until", "unto", "up", "upon", "us", "use", "used", "useful", "uses", "using", "usually", "v", "value", "various", "very", "via", "viz", "vs", "w", "want", "wanted", "wanting", "wants", "was", "wasn\'t", "way", "ways", "we", "we\'d", "we\'ll", "we\'re", "we\'ve", "welcome", "well", "wells", "went", "were", "weren\'t", "what", "what\'s", "whatever", "when", "whence", "whenever", "where", "where\'s", "whereafter", "whereas", "whereby", "wherein", "whereupon", "wherever", "whether", "which", "while", "whither", "who", "who\'s", "whoever", "whole", "whom", "whose", "why", "will", "willing", "wish", "with", "within", "without", "won\'t", "wonder", "work", "worked", "working", "works", "would", "wouldn\'t", "x", "y", "year", "years", "yes", "yet", "you", "you\'d", "you\'ll", "you\'re", "you\'ve", "young", "younger", "youngest", "your", "yours", "yourself", "yourselves", "z", "zero", "january", "faburary", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december", "calendar", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "time", "space", "day", "month", "hour", "year", "week")

  // configs
  val NGRAM_MIN_FREQ = 2 // appears at least 2 times in a single doc

  type WordCounts = Map[String, Int]

  def generateNGramsForCorpus(docs: Seq[String], ngram: Int): WordCounts = {
    val wc = mutable.Map[String, Int]().withDefaultValue(0)
    docs.foreach { doc =>
      generateNGrams(doc, ngram).foreach { case (word, cnt) => wc(word) = wc(word) + cnt }
    }
    wc.toMap
  }

  def isValidGrams(grams: Seq[String]): Boolean = {
    val gramSet = grams.toSet
    gramSet.size == grams.size && gramSet.intersect(stopwords).isEmpty
  }

  def generateNGrams(txt: String, ngram: Int, thresh: Int = NGRAM_MIN_FREQ): WordCounts = {
    val chunks = getChunks(txt.toLowerCase)
    val wc = mutable.Map[String, Int]().withDefaultValue(0)
    chunks.foreach { chunck =>
      val tokens = chunck.split(space).filter { _.trim.size > 1 }
      tokens.sliding(ngram).foreach { grams =>
        if (grams.size == ngram && isValidGrams(grams)) {
          val gram = grams.mkString(" ")
          wc(gram) = wc(gram) + 1
        }
      }
    }
    wc.filter(_._2 >= thresh).toMap
  }

  def getChunks(txt: String): Seq[String] = {
    txt.split(punct).map { _.trim }.filter(_ != "")
  }

  def buildMultiGramIndex(multigrams: Set[String]): Map[String, Set[String]] = {
    val index = mutable.Map[String, Set[String]]().withDefaultValue(Set())
    multigrams.foreach { gram =>
      gram.split(" ").foreach { token =>
        index(token) = index(token) + gram
      }
    }
    index.toMap
  }

  // if any two gram is 'likely' to be substring of a three gram, drop the 2-gram, pick the 3-gram. Other 3grams are not used.
  def combineGrams(twoGrams: WordCounts, threeGrams: WordCounts): WordCounts = {
    val index = buildMultiGramIndex(threeGrams.keySet)
    val toAdd = mutable.Map[String, Int]()
    val toDrop = mutable.Set[String]()
    twoGrams.keys.foreach { twoGram =>
      val parts = twoGram.split(" ")
      assert(parts.size >= 2)
      val candidates = index.getOrElse(parts(0), Set()).intersect(index.getOrElse(parts(1), Set())).filter { x => x.contains(twoGram) }
      candidates.map { cand => cand -> threeGrams(cand) }
        .filter {
          case (cand, m) =>
            val n = twoGrams(twoGram)
            // heursitc: if there are enought sample suggesting that the 2gram often co-occur with the 3gram, 2gram may be partial. need to be replaced.
            m >= 5 && (m * 1.0 / n >= 0.6)
        }.toArray.sortBy(-_._2)
        .headOption.foreach { case (threeGram, cnt) => toAdd(threeGram) = cnt; toDrop.add(twoGram) }
    }

    twoGrams.filter { case (word, cnt) => !toDrop.contains(word) } ++ toAdd
  }
}
