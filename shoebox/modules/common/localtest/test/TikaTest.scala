package test

import com.keepit.search.Article
import com.keepit.common.db.{Id, State}
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.test.EmptyApplication
import org.apache.http.HttpStatus
import scala.collection.mutable.{Map => MutableMap}
import com.keepit.search.ArticleStore
import java.net.URL
import org.apache.tika.metadata.{Metadata, HttpHeaders}
import org.apache.tika.io.TikaInputStream
import org.apache.tika.parser.html.BoilerpipeContentHandler
import java.io.OutputStreamWriter
import org.apache.tika.parser.ParseContext
import org.apache.tika.detect.DefaultDetector
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.Parser
import org.apache.tika.sax.BodyContentHandler
import org.apache.tika.sax.ToHTMLContentHandler
import org.apache.tika.sax.XHTMLContentHandler
import org.apache.tika.sax.ContentHandlerDecorator
import org.xml.sax.ContentHandler
import org.xml.sax.Attributes
import org.apache.tika.sax.WriteOutContentHandler
import org.apache.tika.sax.TeeContentHandler
import com.keepit.common.net.URINormalizer
import com.keepit.scraper.{HttpFetcher, HttpFetcherImpl}
import com.keepit.scraper.extractor.DefaultContentHandler
import com.keepit.scraper.extractor.YoutubeHandler
import org.apache.tika.parser.html.HtmlParser
import org.apache.tika.parser.html.HtmlMapper
import java.io.FileInputStream
import java.io.InputStream
import org.apache.tika.parser.pdf.PDFParser
import org.apache.tika.sax.LinkContentHandler
import com.keepit.scraper.HttpInputStream
import org.apache.tika.mime.MediaType

object TikaTest extends App {

  val fetcher: HttpFetcher = new HttpFetcherImpl(
    userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1309.0 Safari/537.17",
    connectionTimeout = 30000,
    soTimeOut = 30000,
    trustBlindly= false
  )


  //val url = "http://www1.bloomingdales.com/search/results.ognc?sortOption=*&Keyword=juicy%20couture&resultsPerPage=24&Action=sd&attrs=Department%3ADepartment%3ADresses|Color:Color:Black"
  //val url = "http://www.nytimes.com/2012/11/02/nyregion/after-hurricane-sandy-a-difficult-commute-in-new-york.html?hp"
  //val url = "http://personal.denison.edu/~lalla/online-lsh.pdf"
  //val url = "http://d.hatena.ne.jp/echizen_tm/20120801/1343837130"
  //val url = "http://www.amazon.com/Black-Friday/b/ref=bf2012_bunk_1102?ie=UTF8&node=384082011&pf_rd_m=ATVPDKIKX0DER&pf_rd_s=center-B1&pf_rd_r=0NJAA5WTS4ZMK22A99QX&pf_rd_t=101&pf_rd_p=1409843642&pf_rd_i=507846"
  //val url = "http://www.amazon.com/Play-Framework-Cookbook-Alexander-Reelsen/dp/1849515522/ref=sr_1_1?ie=UTF8&qid=1352138890&sr=8-1&keywords=play+framework"
  //val url = "http://www.dropbox.com/home"
  //val url = "http://www.nakedfishsushi.com/"
  //val url = "http://www.youtube.com/watch?v=_7eD2Gy8uKg"
  //val url = "http://www.iowadnr.gov/Fishing/TroutFishing.aspx"
  //val url = "http://www.ynet.co.il/articles/0,7340,L-4303721,00.html"
  //http://mashable.com/2013/06/11/ku-boobs-twitter/

  print("URL> ")
  val url = readLine()
  val status = fetcher.fetch(URINormalizer.normalize(url), None){ input =>
    println("start processing...")
    process(input)
  }
  println("DONE")

  def process(input: HttpInputStream) {
    val metadata = new Metadata()
    val parser = input.getContentType match {
      case Some(contentType) if (contentType startsWith "text/html") =>
        println("##### " + contentType)
        val mediaType = MediaType.parse(contentType)
        val charSetName = mediaType.getParameters.get("charset").toUpperCase
        metadata.set(HttpHeaders.CONTENT_TYPE, s"text/html; charset=${charSetName}")
   println("content-type=\"%s\"".format(metadata.get("Content-Type")))
        new HtmlParser
      case _ =>
        val detector = new DefaultDetector()
        new AutoDetectParser(detector)
        //new PDFParser
    }

    val context = new ParseContext()
    context.set(classOf[Parser], parser);

    val writeout = new WriteOutContentHandler()

    val defaultOutput = new WriteOutContentHandler(100000 * 100)

    val htmlOutput = new ToHTMLContentHandler()

    val defaultContentHandler = new DefaultContentHandler(defaultOutput, metadata)

    val linkContentHandler = new LinkContentHandler()

//    val youtubeOutput = new WriteOutContentHandler()
//    val youtubeContentHandler = new YoutubeHandler(youtubeOutput)

    //context.set(classOf[HtmlMapper], YoutubeHtmlMapper)
    try {
      parser.parse(input, new TeeContentHandler(writeout, htmlOutput, defaultContentHandler, linkContentHandler), metadata, context)
      //parser.parse(input, defaultContentHandler, metadata, context)
    } catch {
      case e =>
    }

    var txt: String = null

    println("**** CONTENT ****")
//    println("**** raw ****")
//    txt = writeout.toString
    println(txt)
    println("****")

    println("**** html ****")
    txt = htmlOutput.toString
    println(txt)
    println("****")

//    println("**** BodyContentHandler ****")
//    txt = bodyOutput.toString
//    println(txt)
//    println("****")

    println("**** defaultContentHandler ****")
    txt = defaultOutput.toString
    println(txt)
    println("****")

//    println("**** LinkContentHandler *****")
//    println(linkContentHandler.getLinks())

//    println("**** YoutubeContentHandler ****")
//    txt = youtubeOutput.toString
//    println(txt)
//    println("****")

    println("**** Metadata ****")
    println("title=\"%s\"".format(metadata.get("title")))
    println("description=\"%s\"".format(metadata.get("description")))
    println("keywords=\"%s\"".format(metadata.get("keywords")))
    println("refresh=\"%s\"".format(metadata.get("refresh")))
    println("content-type=\"%s\"".format(metadata.get("Content-Type")))
    println("content-encoding=\"%s\"".format(metadata.get("Content-Encoding")))
    println(metadata)
    println("****")
  }
}

// meta tag parsing
object Refresh {
  def unapply(refresh: String): (String, Option[String]) = {
    refresh.split(";") match {
    case Array(time) => (time.trim, None)
    case Array(time, url) => (time.trim, Some(url.trim))
    }
  }
}
